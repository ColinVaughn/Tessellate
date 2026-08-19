#!/usr/bin/env python3
"""Check region-scoped block and fluid ticks in four regions.

Repeater and water banks cover scheduled block and fluid ticks under concurrent load.

Run the server, then:
    python tools/verify_scheduledticks.py
"""

import sys
import time
import re
import argparse

from rcon import Rcon
from regions import overworld

AREAS = (
    ("A", (512, 512, 639, 639), (575, 575)),
    ("B", (1536, 512, 1663, 639), (1599, 575)),
    ("C", (2560, 512, 2687, 639), (2623, 575)),
    ("D", (3584, 512, 3711, 639), (3647, 575)),
)
BANK_ROWS = 16
FLOOR_Y = 3
Y = 4

FAILURES = []


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {label}" + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def cmd(rcon, text):
    return rcon.command(f"execute in minecraft:overworld run {text}")


def build_repeater_bank(rcon, spot):
    """Redstone -> repeater -> lamp banks. Lamps light only after scheduled ticks run."""
    x, z = spot
    last = z + BANK_ROWS - 1
    cmd(rcon, f"fill {x} {Y} {z} {x} {Y} {last} minecraft:redstone_block")
    cmd(rcon, f"fill {x + 1} {Y} {z} {x + 1} {Y} {last} "
              "minecraft:repeater[facing=west,delay=4]")
    cmd(rcon, f"fill {x + 2} {Y} {z} {x + 2} {Y} {last} minecraft:redstone_lamp")


def lamp_lit(rcon, spot):
    x, z = spot
    return all("Test passed" in cmd(
        rcon, f"execute if block {x + 2} {Y} {zz} minecraft:redstone_lamp[lit=true]")
        for zz in (z, z + BANK_ROWS - 1))


def reset_repeater(rcon, spot):
    x, z = spot
    cmd(rcon, f"fill {x} {Y} {z} {x + 2} {Y} {z + BANK_ROWS - 1} air")


def build_water(rcon, spot):
    """Flowing water spreads only because each step schedules the next fluid tick."""
    x, z = spot
    zz = z + 24
    cmd(rcon, f"fill {x} {Y} {zz} {x + 8} {Y} {zz + BANK_ROWS - 1} air")
    cmd(rcon, f"fill {x} {Y} {zz} {x} {Y} {zz + BANK_ROWS - 1} minecraft:water")


def water_spread(rcon, spot, distance):
    x, z = spot
    zz = z + 24
    return all("Test passed" in cmd(
        rcon, f"execute if block {x + distance} {Y} {row} minecraft:water")
        for row in (zz, zz + BANK_ROWS - 1))


def phase_peak(summary, label):
    match = re.search(rf"^{re.escape(label)}:.*\bpeak (\d+)", summary, re.MULTILINE)
    return int(match.group(1)) if match else -1


def setup(rcon):
    print("preparing the world")
    for c in [
        "gamerule doMobSpawning false",
        "gamerule doDaylightCycle false",
        "gamerule randomTickSpeed 0",
        "time set midnight",
        "execute in minecraft:overworld run forceload remove all",
        "kill @e[type=item]",
        "kill @e[type=zombie]",
    ]:
        rcon.command(c)
    time.sleep(1.5)
    for _, box, _ in AREAS:
        rcon.command(
            f"execute in minecraft:overworld run forceload add {box[0]} {box[1]} {box[2]} {box[3]}")
    time.sleep(4.0)
    for _, box, _ in AREAS:
        cmd(rcon, f"fill {box[0]} {FLOOR_Y} {box[1]} {box[2]} {FLOOR_Y} {box[3]} minecraft:stone")
        cmd(rcon, f"fill {box[0]} {Y} {box[1]} {box[2]} {Y} {box[3]} air")
    time.sleep(2.0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-concurrency", action="store_true",
                        help="check behavior only, for the serial rollback arm")
    parser.add_argument("--soak-seconds", type=int, default=0,
                        help="rebuild and verify all banks for this many seconds")
    args = parser.parse_args()
    with Rcon(timeout=120.0) as rcon:
        print("optimal region-scoped scheduled tick verification\n")
        setup(rcon)

        ow = overworld(rcon)
        region_ids = {name: ow.region_near(*spot)[0] for name, _, spot in AREAS}
        check("all areas have distinct regions",
              None not in region_ids.values() and len(set(region_ids.values())) == len(AREAS),
              ", ".join(f"{name}={region_id}" for name, region_id in region_ids.items()))

        print("\nstep 1: redstone repeaters fire their scheduled ticks")
        for name, _, spot in AREAS:
            reset_repeater(rcon, spot)
        time.sleep(1.0)
        for name, _, spot in AREAS:
            build_repeater_bank(rcon, spot)
        # delay=4 is eight game ticks; leave room for command latency.
        time.sleep(3.0)
        for name, _, spot in AREAS:
            check(f"area {name} repeater powered its lamp", lamp_lit(rcon, spot),
                  "first and last bank rows checked")

        print("\nstep 2: fluid ticks spread water")
        for name, _, spot in AREAS:
            build_water(rcon, spot)
        time.sleep(4.0)
        for name, _, spot in AREAS:
            check(f"area {name} water flowed at least 3 blocks",
                  water_spread(rcon, spot, 3), "first and last bank rows checked")

        if args.soak_seconds > 0:
            print(f"\nsoak: rebuilding four dense banks for {args.soak_seconds} seconds")
            deadline = time.monotonic() + args.soak_seconds
            next_report = time.monotonic() + 60.0
            cycles = 0
            reports = 0
            while time.monotonic() + 4.0 <= deadline:
                for _, _, spot in AREAS:
                    reset_repeater(rcon, spot)
                    build_water(rcon, spot)
                time.sleep(0.25)
                for _, _, spot in AREAS:
                    build_repeater_bank(rcon, spot)
                time.sleep(4.0)
                cycles += 1
                healthy = all(lamp_lit(rcon, spot) and water_spread(rcon, spot, 3)
                              for _, _, spot in AREAS)
                if not healthy:
                    check("soak banks remained functional", False, f"cycle={cycles}")
                    break
                if time.monotonic() >= next_report:
                    reports += 1
                    phases = rcon.command("optimal phases")
                    healthy = ("parallel=true" in phases
                               and not re.search(r"failures [1-9]\d*", phases)
                               and "deferred queues: 0 pending" in phases)
                    check(f"soak minute {reports} stayed healthy", healthy,
                          f"cycles={cycles}")
                    next_report += 60.0
            check("scheduled-tick soak completed", time.monotonic() + 4.0 > deadline,
                  f"cycles={cycles}")

        if not args.skip_concurrency:
            print("\nstep 3: block and fluid schedulers overlap across regions")
            phases = rcon.command("optimal phases")
            block_peak = phase_peak(phases, "scheduled-blocks")
            fluid_peak = phase_peak(phases, "scheduled-fluids")
            check("scheduled block ticks reached two workers", block_peak >= 2,
                  f"peak={block_peak}")
            check("scheduled fluid ticks reached two workers", fluid_peak >= 2,
                  f"peak={fluid_peak}")

        print("\nstep 4: ownership guard")
        violations = rcon.command("optimal violations")
        check("zero ownership violations", "no ownership violations" in violations,
              violations.strip().splitlines()[0] if violations.strip() else "empty")

        print("\ncleaning up")
        for _, _, spot in AREAS:
            reset_repeater(rcon, spot)
            x, z = spot
            cmd(rcon, f"fill {x} {Y} {z + 24} {x + 8} {Y} "
                      f"{z + 24 + BANK_ROWS - 1} air")
        rcon.command("execute in minecraft:overworld run forceload remove all")

    print("\n" + "=" * 60)
    if FAILURES:
        print(f"FAILED: {len(FAILURES)} check(s)")
        for name in FAILURES:
            print(f"  - {name}")
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
