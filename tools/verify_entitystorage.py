#!/usr/bin/env python3
"""Compare entity query results with sharded storage disabled and enabled.

The query grid crosses a 64-block cell boundary and covers boxes, radii, sorting, and limits.
Save a vanilla-storage run, then compare the same query battery against sharded storage.

Usage:
    python tools/verify_entitystorage.py --save baseline.json      # with sharding off
    python tools/verify_entitystorage.py --compare baseline.json   # with sharding on
"""

import argparse
import json
import re
import sys
import time

from rcon import Rcon

# Cells are 64 blocks at sectionShift 2, so 512 is a cell boundary in both axes.
BOUNDARY = 512
GRID_MIN = BOUNDARY - 40
GRID_MAX = BOUNDARY + 40
GRID_STEP = 4

FLOOR_Y = 3
SPAWN_Y = 4

FORCE_MIN = (GRID_MIN - 32, GRID_MIN - 32)
FORCE_MAX = (GRID_MAX + 32, GRID_MAX + 32)

FAILURES = []


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {label}" + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def setup(rcon):
    print("preparing the world")
    for rule, value in [
        ("doMobSpawning", "false"),
        ("doDaylightCycle", "false"),
        ("doWeatherCycle", "false"),
        ("doFireTick", "false"),
        ("randomTickSpeed", "0"),
        ("doTileDrops", "false"),
    ]:
        rcon.command(f"gamerule {rule} {value}")

    rcon.command("execute in minecraft:overworld run forceload remove all")
    rcon.command("kill @e[type=item]")
    rcon.command(
        f"execute in minecraft:overworld run forceload add "
        f"{FORCE_MIN[0]} {FORCE_MIN[1]} {FORCE_MAX[0]} {FORCE_MAX[1]}")
    time.sleep(2.0)

    # Keep items fixed in the otherwise empty superflat world.
    rcon.command(
        f"execute in minecraft:overworld run fill "
        f"{FORCE_MIN[0]} {FLOOR_Y} {FORCE_MIN[1]} {FORCE_MAX[0]} {FLOOR_Y} {FORCE_MAX[1]} "
        f"minecraft:stone")
    # Other checks reuse this world, so clear their blocks one layer at a time. A multi-layer fill
    # exceeds Minecraft's 32,768-block command limit.
    for y in range(FLOOR_Y + 1, FLOOR_Y + 9):
        rcon.command(
            f"execute in minecraft:overworld run fill "
            f"{FORCE_MIN[0]} {y} {FORCE_MIN[1]} {FORCE_MAX[0]} {y} {FORCE_MAX[1]} minecraft:air")

    probe = rcon.command(
        f"execute in minecraft:overworld if block {BOUNDARY} {FLOOR_Y} {BOUNDARY} minecraft:stone")
    check("the grid has a floor under it", "Test passed" in probe, probe.strip())
    clear = rcon.command(
        f"execute in minecraft:overworld if block {BOUNDARY} {SPAWN_Y} {BOUNDARY} minecraft:air")
    check("the space above the floor is clear", "Test passed" in clear, clear.strip())


def spawn_grid(rcon):
    # NoGravity keeps boundary and radius queries repeatable.
    coords = list(range(GRID_MIN, GRID_MAX + 1, GRID_STEP))
    total = len(coords) ** 2
    print(f"spawning {total} items on a {len(coords)}x{len(coords)} grid across the cell "
          f"boundary at {BOUNDARY}")
    for x in coords:
        for z in coords:
            rcon.command(
                f"execute in minecraft:overworld run summon minecraft:item {x} {SPAWN_Y} {z} "
                '{Item:{id:"minecraft:diamond",Count:1b},Age:-30000s,PickupDelay:32767s,'
                'NoGravity:1b}')
    time.sleep(1.5)
    return total


def count(rcon, selector):
    """Count a selector without removing its entities."""
    rcon.command("scoreboard objectives add optimalq dummy")
    rcon.command(
        f"execute in minecraft:overworld store result score #q optimalq if entity {selector}")
    out = rcon.command("scoreboard players get #q optimalq")
    match = re.search(r"has (-?\d+) ", out)
    if not match:
        raise RuntimeError(f"could not count {selector}: {out}")
    return int(match.group(1))


def battery():
    """Build queries that cover each shard boundary path."""
    queries = []

    def box(name, x, z, dx, dz, y=SPAWN_Y - 2, dy=6):
        queries.append((name,
                        f"@e[type=item,x={x},y={y},z={z},dx={dx},dy={dy},dz={dz}]"))

    # Wholly inside one cell, on each side of both boundaries.
    box("inside-cell-low-low", BOUNDARY - 40, BOUNDARY - 40, 20, 20)
    box("inside-cell-high-high", BOUNDARY + 8, BOUNDARY + 8, 20, 20)
    box("inside-cell-low-high", BOUNDARY - 40, BOUNDARY + 8, 20, 20)

    # Straddle one boundary, then both.
    box("straddle-x", BOUNDARY - 10, BOUNDARY - 40, 20, 20)
    box("straddle-z", BOUNDARY - 40, BOUNDARY - 10, 20, 20)
    box("straddle-both", BOUNDARY - 10, BOUNDARY - 10, 20, 20)

    # Sitting exactly on the boundary, and one block either side of it.
    for offset in (-1, 0, 1):
        box(f"on-boundary{offset:+d}", BOUNDARY + offset, BOUNDARY + offset, 1, 1)

    # Cover degenerate and sub-block boxes.
    box("zero-width-at-boundary", BOUNDARY, BOUNDARY, 0, 0)
    box("thin-slab-across-x", BOUNDARY - 1, GRID_MIN, 2, GRID_MAX - GRID_MIN)
    box("thin-slab-across-z", GRID_MIN, BOUNDARY - 1, GRID_MAX - GRID_MIN, 2)

    # Spanning every cell the grid touches.
    box("whole-grid", GRID_MIN - 4, GRID_MIN - 4,
        (GRID_MAX - GRID_MIN) + 8, (GRID_MAX - GRID_MIN) + 8)
    box("beyond-grid", GRID_MIN - 200, GRID_MIN - 200,
        (GRID_MAX - GRID_MIN) + 400, (GRID_MAX - GRID_MIN) + 400)

    # Empty regions must not pick up a neighboring shard.
    box("far-empty", BOUNDARY + 2000, BOUNDARY + 2000, 40, 40)

    # Radius selectors take a different path into the same storage.
    for radius in (1, 5, 12, 40, 90):
        queries.append((f"distance-{radius}",
                        f"@e[type=item,x={BOUNDARY},y={SPAWN_Y},z={BOUNDARY},"
                        f"distance=..{radius}]"))

    # Sorted and limited selectors stop iteration early, so shard order matters.
    for limit in (1, 3, 10):
        queries.append((f"nearest-{limit}",
                        f"@e[type=item,x={BOUNDARY},y={SPAWN_Y},z={BOUNDARY},"
                        f"sort=nearest,limit={limit}]"))

    # Keep the population assertion scoped to this grid; also record the level-wide count.
    queries.append(("all-items",
                    f"@e[type=item,x={FORCE_MIN[0]},y=-64,z={FORCE_MIN[1]},"
                    f"dx={FORCE_MAX[0] - FORCE_MIN[0]},dy=384,dz={FORCE_MAX[1] - FORCE_MIN[1]}]"))
    queries.append(("all-items-levelwide", "@e[type=item]"))
    return queries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--save", metavar="FILE", help="write results for a later comparison")
    parser.add_argument("--compare", metavar="FILE", help="compare against a saved run")
    parser.add_argument("--skip-setup", action="store_true", help="reuse the existing grid")
    args = parser.parse_args()

    with Rcon(timeout=180.0) as rcon:
        print("optimal entity storage differential check\n")

        sharded = "shardEntityStorage=true" in rcon.command("optimal regions").replace(" ", "")
        print(f"sharded storage reported by the server: {sharded}\n")

        if not args.skip_setup:
            setup(rcon)
            expected_total = spawn_grid(rcon)
        else:
            expected_total = None

        print("\nrunning the query battery")
        results = {}
        for name, selector in battery():
            results[name] = count(rcon, selector)
            print(f"    {name:26s} {results[name]:5d}")

        if expected_total is not None:
            check("every spawned item is present", results["all-items"] == expected_total,
                  f"expected {expected_total}, found {results['all-items']}")
            check("no stray items outside the grid area",
                  results["all-items-levelwide"] == results["all-items"],
                  f"{results['all-items-levelwide'] - results['all-items']} item(s) elsewhere "
                  f"would mask items that failed to spawn")
            check("a box covering the grid finds every item",
                  results["whole-grid"] == expected_total,
                  f"expected {expected_total}, found {results['whole-grid']}")
        check("an empty area really is empty", results["far-empty"] == 0,
              f"found {results['far-empty']}")
        check("straddling both boundaries finds more than either side alone",
              results["straddle-both"] > 0, f"found {results['straddle-both']}")

        print("\ncleaning up")
        rcon.command("kill @e[type=item]")
        rcon.command("execute in minecraft:overworld run forceload remove all")

    if args.save:
        with open(args.save, "w", encoding="utf-8") as handle:
            json.dump({"sharded": sharded, "results": results}, handle, indent=2, sort_keys=True)
        print(f"\nsaved {len(results)} results to {args.save}")

    if args.compare:
        with open(args.compare, encoding="utf-8") as handle:
            saved = json.load(handle)
        print(f"\ncomparing against {args.compare} "
              f"(sharded={saved['sharded']} -> sharded={sharded})")
        differences = []
        for name in sorted(set(saved["results"]) | set(results)):
            before = saved["results"].get(name)
            now = results.get(name)
            if before != now:
                differences.append(f"{name}: {before} -> {now}")
        for line in differences:
            print(f"    DIFF {line}")
        check("every query returns exactly what it did before sharding", not differences,
              f"{len(differences)} of {len(results)} queries differ")

    print("\n" + "=" * 68)
    if FAILURES:
        print(f"{len(FAILURES)} check(s) FAILED")
        for name in FAILURES:
            print(f"  - {name}")
        return 1
    print("all entity storage checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
