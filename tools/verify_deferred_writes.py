#!/usr/bin/env python3
"""Exercise worker-thread deferral for scheduled ticks and block events.

Zombies walk over pressure plates, scheduling ticks and powering note blocks. Two separate areas
ensure the work is dispatched to region workers instead of staying on the main thread.

Usage:
    python tools/verify_deferred_writes.py [--mobs 400] [--window 15]
"""

import argparse
import random
import re
import sys
import time

from rcon import Rcon

AREA_SPACING = 2048
AREA_ORIGIN = (512, 512)
PLATE_HALF = 12

FLOOR_Y = 3
NOTE_Y = 4
PLATE_Y = 5
SPAWN_Y = 6

FAILURES = []


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {label}" + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def area_center(index):
    return (AREA_ORIGIN[0] + index * AREA_SPACING, AREA_ORIGIN[1])


def build_area(rcon, index):
    cx, cz = area_center(index)
    lo = (cx - 32, cz - 32)
    hi = (cx + 32, cz + 32)
    rcon.command(
        f"execute in minecraft:overworld run forceload add {lo[0]} {lo[1]} {hi[0]} {hi[1]}")
    time.sleep(1.0)

    rcon.command(
        f"execute in minecraft:overworld run fill "
        f"{lo[0]} {FLOOR_Y} {lo[1]} {hi[0]} {FLOOR_Y} {hi[1]} minecraft:stone")

    # Each plate schedules a tick and powers the note block below it.
    rcon.command(
        f"execute in minecraft:overworld run fill "
        f"{cx - PLATE_HALF} {NOTE_Y} {cz - PLATE_HALF} "
        f"{cx + PLATE_HALF} {NOTE_Y} {cz + PLATE_HALF} minecraft:note_block")
    rcon.command(
        f"execute in minecraft:overworld run fill "
        f"{cx - PLATE_HALF} {PLATE_Y} {cz - PLATE_HALF} "
        f"{cx + PLATE_HALF} {PLATE_Y} {cz + PLATE_HALF} minecraft:stone_pressure_plate")

    plate = rcon.command(
        f"execute in minecraft:overworld if block {cx} {PLATE_Y} {cz} "
        f"minecraft:stone_pressure_plate")
    note = rcon.command(
        f"execute in minecraft:overworld if block {cx} {NOTE_Y} {cz} minecraft:note_block")
    check(f"area {index} has pressure plates", "Test passed" in plate, plate.strip())
    check(f"area {index} has note blocks under them", "Test passed" in note, note.strip())


def spawn(rcon, index, count):
    cx, cz = area_center(index)
    rng = random.Random(20260818 + index)
    for _ in range(count):
        x = rng.randint(cx - PLATE_HALF, cx + PLATE_HALF)
        z = rng.randint(cz - PLATE_HALF, cz + PLATE_HALF)
        rcon.command(
            f"execute in minecraft:overworld run summon minecraft:zombie {x} {SPAWN_Y} {z} "
            '{PersistenceRequired:1b,IsBaby:0b}')


def counters(rcon):
    """Return the server's deferred and replayed counts."""
    out = rcon.command("optimal regions")
    match = re.search(
        r"deferred to main thread: (\d+)/(\d+) entity callback\(s\), "
        r"(\d+)/(\d+) level write\(s\)", out)
    if not match:
        raise RuntimeError(f"could not read deferral counters: {out}")
    return tuple(int(value) for value in match.groups())


def execution_mode(rcon):
    out = rcon.command("optimal regions")
    found = re.search(r"execution: (.+)", out)
    return found.group(1).strip() if found else "unknown"


def count_entities(rcon, entity_type):
    rcon.command("scoreboard objectives add optimaldw dummy")
    rcon.command(
        f"execute in minecraft:overworld store result score #c optimaldw "
        f"if entity @e[type={entity_type}]")
    out = rcon.command("scoreboard players get #c optimaldw")
    match = re.search(r"has (-?\d+) ", out)
    return int(match.group(1)) if match else -1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mobs", type=int, default=400, help="zombies per area")
    parser.add_argument("--window", type=float, default=15.0)
    args = parser.parse_args()

    with Rcon(timeout=600.0) as rcon:
        mode = execution_mode(rcon)
        print(f"optimal deferred-write check\nexecution: {mode}\n")

        for rule, value in [
            ("doMobSpawning", "false"),
            ("doDaylightCycle", "false"),
            ("doWeatherCycle", "false"),
            ("doFireTick", "false"),
            ("randomTickSpeed", "0"),
            ("doTileDrops", "false"),
            ("doMobLoot", "false"),
        ]:
            rcon.command(f"gamerule {rule} {value}")
        rcon.command("time set midnight")
        rcon.command("execute in minecraft:overworld run forceload remove all")
        rcon.command("kill @e[type=zombie]")

        print("building two plate fields")
        for index in range(2):
            build_area(rcon, index)

        before = counters(rcon)
        print(f"\nbefore load: {before[0]}/{before[1]} callbacks, "
              f"{before[2]}/{before[3]} level writes")

        print(f"\nspawning {args.mobs} zombies onto each field")
        for index in range(2):
            spawn(rcon, index, args.mobs)
        time.sleep(4.0)

        print(f"running for {args.window:.0f}s")
        time.sleep(args.window)

        after = counters(rcon)
        mode_after = execution_mode(rcon)
        print(f"\nafter load:  {after[0]}/{after[1]} callbacks, "
              f"{after[2]}/{after[3]} level writes")

        writes = after[3] - before[3]
        replayed = after[2] - before[2]

        print("\n" + "=" * 68)
        check("still running in parallel", "worker" in mode_after, mode_after)
        check("the mobs survived the run", count_entities(rcon, "minecraft:zombie") > 0)
        check("scheduled ticks and block events were deferred from workers", writes > 0,
              f"{writes} deferred; zero means the path still is not being exercised")
        check("every deferred level write was replayed", writes == replayed,
              f"deferred {writes}, replayed {replayed}")
        check("every deferred entity callback was replayed", after[0] == after[1],
              f"replayed {after[0]}, deferred {after[1]}")

        violations = rcon.command("optimal violations")
        check("zero ownership violations", "no ownership violations" in violations,
              violations.strip().splitlines()[-1] if violations.strip() else "")

        # Corruption can leave the field stuck without raising an exception.
        cx, cz = area_center(0)
        plate = rcon.command(
            f"execute in minecraft:overworld if block {cx} {PLATE_Y} {cz} "
            f"minecraft:stone_pressure_plate")
        check("the plate field survived intact", "Test passed" in plate, plate.strip())

        print("\ncleaning up")
        rcon.command("kill @e[type=zombie]")
        rcon.command("execute in minecraft:overworld run forceload remove all")

    print("\n" + "=" * 68)
    if FAILURES:
        print(f"{len(FAILURES)} check(s) FAILED")
        for name in FAILURES:
            print(f"  - {name}")
        return 1
    print("all deferred-write checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
