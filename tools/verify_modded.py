#!/usr/bin/env python3
"""Run third-party mobs and block entities through parallel region ticking.

Two loaded regions ensure worker dispatch. The check watches for serial fallback, ownership
violations, lost entities, and removed block entities.

Usage:
    python tools/verify_modded.py [--mobs 120] [--window 20]
"""

import argparse
import json
import random
import re
import sys
import time

from rcon import Rcon

AREA_SPACING = 2048
AREA_ORIGIN = (512, 512)
HALF = 14
FLOOR_Y = 3
SPAWN_Y = 5
TEST_TAG = "optimal_modded"

# Chosen to exercise different tick paths: land AI, water AI, flying AI, and swarms.
CANDIDATE_MOBS = [
    "friendsandfoes:copper_golem", "friendsandfoes:crab", "friendsandfoes:glare",
    "friendsandfoes:iceologer", "friendsandfoes:illusioner", "friendsandfoes:moobloom",
    "friendsandfoes:rascal", "friendsandfoes:tuff_golem", "friendsandfoes:wildfire",
    "naturalist:deer", "naturalist:boar", "naturalist:bear", "naturalist:duck",
    "naturalist:snake", "naturalist:butterfly", "naturalist:firefly", "naturalist:rhino",
    "mekanism:robit",
    # Vanilla types with distinct AI, so the run still exercises something if no modded entity
    # persists in the bench world.
    "minecraft:zombie", "minecraft:skeleton", "minecraft:cow", "minecraft:villager",
    "minecraft:spider",
]

# Machines that tick as block entities even with no power or recipe.
CANDIDATE_BLOCKS = [
    "mekanism:enrichment_chamber", "mekanism:metallurgic_infuser", "mekanism:energy_cube_basic",
    "mekanism:basic_energy_cube", "mekanism:electric_pump", "mekanism:basic_bin",
    "mekanism:crusher", "mekanism:electrolytic_separator",
    "mekanismgenerators:heat_generator", "mekanismgenerators:solar_generator",
    "mekanismgenerators:wind_generator", "mekanismgenerators:bio_generator",
    "mekanismgenerators:gas_burning_generator", "mekanismgenerators:advanced_solar_generator",
]

FAILURES = []
SKIPPED = []


def skip(label, detail):
    """Record optional coverage that is unavailable in this environment."""
    print(f"  [SKIP] {label}  ({detail})")
    SKIPPED.append(f"{label}: {detail}")


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {label}" + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def area_center(index):
    return (AREA_ORIGIN[0] + index * AREA_SPACING, AREA_ORIGIN[1])


def probe_mobs(rcon):
    """Return installed mob ids that survive for several ticks."""
    # Give probe entities a floor.
    rcon.command(
        f"execute in minecraft:overworld run fill -8 {FLOOR_Y} -8 8 {FLOOR_Y} 8 minecraft:stone")
    for y in (FLOOR_Y + 1, FLOOR_Y + 2):
        rcon.command(
            f"execute in minecraft:overworld run fill -8 {y} -8 8 {y} 8 minecraft:air")

    usable = []
    for entity in CANDIDATE_MOBS:
        rcon.command(f"kill @e[type={entity}]")
        out = rcon.command(
            f"execute in minecraft:overworld run summon {entity} 0 {SPAWN_Y} 0 "
            '{PersistenceRequired:1b}')
        if "Unable to summon" in out or "Unknown" in out or "error" in out.lower():
            continue
        usable.append(entity)

    time.sleep(4.0)
    survivors = []
    for entity in usable:
        # Ignore matching entities left elsewhere in the world.
        here = count_entities(
            rcon, f"@e[type={entity},x=-8,y={FLOOR_Y},z=-8,dx=16,dy=8,dz=16]")
        if here > 0:
            survivors.append(entity)
        rcon.command(f"kill @e[type={entity}]")
    vanished = [e for e in usable if e not in survivors]
    if vanished:
        print(f"    did not persist here, skipped: {', '.join(vanished)}")
    return survivors


def probe_blocks(rcon):
    usable = []
    for index, block in enumerate(CANDIDATE_BLOCKS):
        x = 4 + index
        rcon.command(f"execute in minecraft:overworld run setblock {x} 100 4 {block}")
        out = rcon.command(f"execute in minecraft:overworld if block {x} 100 4 {block}")
        data = rcon.command(f"execute in minecraft:overworld run data get block {x} 100 4")
        if "Test passed" in out and "following block data" in data:
            usable.append(block)
        rcon.command(f"execute in minecraft:overworld run setblock {x} 100 4 minecraft:air")
    return usable


def build(rcon, index, blocks):
    cx, cz = area_center(index)
    lo, hi = (cx - 32, cz - 32), (cx + 32, cz + 32)
    rcon.command(
        f"execute in minecraft:overworld run forceload add {lo[0]} {lo[1]} {hi[0]} {hi[1]}")
    time.sleep(1.0)
    rcon.command(
        f"execute in minecraft:overworld run fill "
        f"{lo[0]} {FLOOR_Y} {lo[1]} {hi[0]} {FLOOR_Y} {hi[1]} minecraft:stone")
    for y in (FLOOR_Y + 1, FLOOR_Y + 2, FLOOR_Y + 3):
        rcon.command(
            f"execute in minecraft:overworld run fill "
            f"{lo[0]} {y} {lo[1]} {hi[0]} {y} {hi[1]} minecraft:air")

    placed = 0
    if blocks:
        for dx in range(-HALF, HALF + 1, 2):
            for dz in range(-HALF, HALF + 1, 2):
                block = blocks[(dx + dz) % len(blocks)]
                rcon.command(
                    f"execute in minecraft:overworld run setblock "
                    f"{cx + dx} {FLOOR_Y + 1} {cz + dz} {block}")
                placed += 1
    return placed


def spawn(rcon, index, mobs, count):
    cx, cz = area_center(index)
    rng = random.Random(20260818 + index)
    for i in range(count):
        entity = mobs[i % len(mobs)]
        x = rng.randint(cx - HALF, cx + HALF)
        z = rng.randint(cz - HALF, cz + HALF)
        rcon.command(
            f"execute in minecraft:overworld run summon {entity} {x} {SPAWN_Y} {z} "
            f'{{PersistenceRequired:1b,Invulnerable:1b,Tags:["{TEST_TAG}"]}}')


def count_entities(rcon, selector):
    rcon.command("scoreboard objectives add optimalmod dummy")
    # Unknown registry ids fail before storing a result, so reset the score first.
    rcon.command("scoreboard players set #m optimalmod 0")
    rcon.command(
        f"execute in minecraft:overworld store result score #m optimalmod if entity {selector}")
    out = rcon.command("scoreboard players get #m optimalmod")
    match = re.search(r"has (-?\d+) ", out)
    return int(match.group(1)) if match else -1


def status(rcon):
    out = rcon.command("optimal regions")
    mode = re.search(r"execution: (.+)", out)
    counters = re.search(
        r"deferred to main thread: (\d+)/(\d+) entity callback\(s\), (\d+)/(\d+) level write", out)
    return (mode.group(1).strip() if mode else "unknown",
            tuple(int(v) for v in counters.groups()) if counters else (0, 0, 0, 0))


def dispatched_regions(rcon):
    """Return the number of regions dispatched on the last tick."""
    out = rcon.command("optimal regions")
    overworld = out.split("minecraft:overworld", 1)
    if len(overworld) < 2:
        return 0
    found = re.search(r"parallel: (\d+) region\(s\) dispatched", overworld[1])
    return int(found.group(1)) if found else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mobs", type=int, default=120, help="mobs per area")
    parser.add_argument("--window", type=float, default=20.0)
    args = parser.parse_args()

    with Rcon(timeout=600.0) as rcon:
        mode, before = status(rcon)
        print(f"optimal modded compatibility check\nexecution: {mode}\n")

        for rule, value in [
            ("doMobSpawning", "false"), ("doDaylightCycle", "false"),
            ("doWeatherCycle", "false"), ("doFireTick", "false"),
            ("randomTickSpeed", "0"), ("mobGriefing", "false"),
        ]:
            rcon.command(f"gamerule {rule} {value}")
        rcon.command("time set midnight")  # Keep undead mobs from burning.
        rcon.command("execute in minecraft:overworld run forceload remove all")
        rcon.command("kill @e[type=!player]")
        rcon.command("execute in minecraft:overworld run forceload add -16 -16 16 16")
        time.sleep(1.5)

        print("probing which modded content this install actually has")
        mobs = probe_mobs(rcon)
        blocks = probe_blocks(rcon)
        print(f"    usable mobs:   {', '.join(mobs) if mobs else 'NONE'}")
        print(f"    usable blocks: {', '.join(blocks) if blocks else 'NONE'}")
        rcon.command("execute in minecraft:overworld run forceload remove all")

        modded_mobs = [m for m in mobs if not m.startswith("minecraft:")]
        if modded_mobs:
            check("found modded mobs that persist in this world", True,
                  ", ".join(modded_mobs))
        else:
            skip("modded mob AI on a region worker",
                 "no installed modded entity survived the probe; check the server spawn flags")
        check("found modded block entities to test with", bool(blocks),
              ", ".join(blocks) if blocks else "no modded machines available")
        if not mobs:
            return 1

        print("\nbuilding two areas with modded machines")
        placed = sum(build(rcon, index, blocks) for index in range(2))
        print(f"    placed {placed} modded block(s)")

        print(f"\nspawning {args.mobs} modded mobs into each area")
        for index in range(2):
            spawn(rcon, index, mobs, args.mobs)
        time.sleep(5.0)

        alive_before = count_entities(rcon, f"@e[tag={TEST_TAG}]")
        print(f"    {alive_before} entities alive")

        print(f"\nrunning for {args.window:.0f}s under parallel region ticking")
        time.sleep(args.window)

        mode_after, after = status(rcon)
        alive_after = count_entities(rcon, f"@e[tag={TEST_TAG}]")

        print("\n" + "=" * 68)
        print(f"  execution mode:   {mode_after}")
        print(f"  entities alive:   {alive_before} -> {alive_after}")
        print(f"  callbacks:        {after[0]}/{after[1]} replayed/deferred")
        print(f"  level writes:     {after[2]}/{after[3]} replayed/deferred")

        check("still running in parallel, no degrade to serial", "worker" in mode_after,
              mode_after)
        check("the tagged modded mobs survived the run",
              alive_after > 0 and alive_after == alive_before,
              f"{alive_after} left of {alive_before}")
        dispatched = max(dispatched_regions(rcon) for _ in range(5))
        check("the modded load actually reached the region workers", dispatched > 1,
              f"{dispatched} region(s) dispatched on the sampled tick; 0 or 1 means this ran on "
              f"the main thread and tested nothing about parallelism")
        check("every deferred callback was replayed", after[0] == after[1],
              f"replayed {after[0]}, deferred {after[1]}")
        check("every deferred level write was replayed", after[2] == after[3],
              f"replayed {after[2]}, deferred {after[3]}")

        violations = rcon.command("optimal violations")
        check("zero ownership violations", "no ownership violations" in violations,
              violations.strip().splitlines()[-1] if violations.strip() else "")

        if blocks:
            cx, cz = area_center(0)
            probe = rcon.command(
                f"execute in minecraft:overworld if block {cx} {FLOOR_Y + 1} {cz} "
                f"{blocks[(0 + 0) % len(blocks)]}")
            check("the modded machines are still there", "Test passed" in probe, probe.strip())

        print("\ncleaning up")
        rcon.command("kill @e[type=!player]")
        for index in range(2):
            cx, cz = area_center(index)
            for y in (FLOOR_Y + 1, FLOOR_Y + 2):
                rcon.command(
                    f"execute in minecraft:overworld run fill "
                    f"{cx - 32} {y} {cz - 32} {cx + 32} {y} {cz + 32} minecraft:air")
        rcon.command("execute in minecraft:overworld run forceload remove all")

    print("\n" + "=" * 68)
    if FAILURES:
        print(f"{len(FAILURES)} check(s) FAILED")
        for name in FAILURES:
            print(f"  - {name}")
        return 1
    print("all modded compatibility checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
