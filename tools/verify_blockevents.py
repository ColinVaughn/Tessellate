#!/usr/bin/env python3
"""Exercise region-owned piston and note-block events in four live regions."""

import argparse
import re
import sys
import time

from rcon import Rcon
from regions import overworld


AREAS = (
    ("A", (512, 512, 639, 639), (575, 575)),
    ("B", (1536, 512, 1663, 639), (1599, 575)),
    ("C", (2560, 512, 2687, 639), (2623, 575)),
    ("D", (3584, 512, 3711, 639), (3647, 575)),
)
ROWS = 64
Y = 4
FAILURES = []


def check(label, condition, detail=""):
    print(f"  [{'PASS' if condition else 'FAIL'}] {label}"
          + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def cmd(rcon, text):
    return rcon.command(f"execute in minecraft:overworld run {text}")


def phase(summary, label):
    match = re.search(
        rf"^{re.escape(label)}: worker (\d+)/.*main (\d+)/.*peak (\d+)",
        summary, re.MULTILINE)
    return tuple(map(int, match.groups())) if match else (-1, -1, -1)


def build_bank(rcon, spot):
    x, z = spot
    last = z + ROWS - 1
    cmd(rcon, f"fill {x} {Y} {z} {x + 8} {Y} {last} air")
    cmd(rcon, f"fill {x + 1} {Y} {z} {x + 1} {Y} {last} "
              "minecraft:piston[facing=east,extended=false]")
    cmd(rcon, f"fill {x + 2} {Y} {z} {x + 2} {Y} {last} minecraft:stone")
    cmd(rcon, f"fill {x + 6} {Y} {z} {x + 6} {Y} {last} minecraft:note_block")


def power(rcon, spot, enabled):
    x, z = spot
    block = "minecraft:redstone_block" if enabled else "air"
    cmd(rcon, f"fill {x} {Y} {z} {x} {Y} {z + ROWS - 1} {block}")
    cmd(rcon, f"fill {x + 5} {Y} {z} {x + 5} {Y} {z + ROWS - 1} {block}")


def piston_state(rcon, spot, extended):
    x, z = spot
    expected = str(extended).lower()
    return all("Test passed" in cmd(
        rcon, f"execute if block {x + 1} {Y} {row} "
              f"minecraft:piston[extended={expected}]")
        for row in (z, z + ROWS - 1))


def wait_regions(rcon, predicate, timeout=180.0):
    deadline = time.monotonic() + timeout
    state = overworld(rcon)
    while time.monotonic() < deadline and not predicate(state):
        time.sleep(0.5)
        state = overworld(rcon)
    return state


def setup(rcon):
    print("preparing the world")
    for command in (
        "gamerule doMobSpawning false",
        "gamerule doDaylightCycle false",
        "gamerule randomTickSpeed 0",
        "execute in minecraft:overworld run forceload remove all",
    ):
        rcon.command(command)
    for _, box, _ in AREAS:
        rcon.command(
            f"execute in minecraft:overworld run forceload add "
            f"{box[0]} {box[1]} {box[2]} {box[3]}")
    time.sleep(4.0)
    for _, _, spot in AREAS:
        build_bank(rcon, spot)
    time.sleep(1.0)


def topology_churn(rcon):
    print("\nstep 3: merge/split while block events remain active")
    bridge = (640, 560, 1535, 575)
    before = overworld(rcon)
    for _ in range(3):
        for _, _, spot in AREAS:
            power(rcon, spot, True)
        time.sleep(0.25)
        for _, _, spot in AREAS:
            power(rcon, spot, False)
        time.sleep(0.25)
    rcon.command(
        f"execute in minecraft:overworld run forceload add "
        f"{bridge[0]} {bridge[1]} {bridge[2]} {bridge[3]}")
    merged = wait_regions(
        rcon, lambda state: state.region_count == before.region_count - 1
        and state.merges > before.merges)
    check("bridge merged areas A and B",
          merged.region_count == before.region_count - 1 and merged.merges > before.merges,
          f"regions={before.region_count}->{merged.region_count}, "
          f"merges={before.merges}->{merged.merges}")
    rcon.command(
        f"execute in minecraft:overworld run forceload remove "
        f"{bridge[0]} {bridge[1]} {bridge[2]} {bridge[3]}")
    split = wait_regions(
        rcon, lambda state: state.region_count == before.region_count
        and state.splits > before.splits)
    check("removing bridge split areas A and B",
          split.region_count == before.region_count and split.splits > before.splits,
          f"regions={merged.region_count}->{split.region_count}, "
          f"splits={before.splits}->{split.splits}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-concurrency", action="store_true",
                        help="behavior-only serial rollback arm")
    parser.add_argument("--soak-seconds", type=int, default=0)
    args = parser.parse_args()

    with Rcon(timeout=120.0) as rcon:
        print("tessellate region-owned block-event verification\n")
        setup(rcon)
        world = overworld(rcon)
        ids = {name: world.region_near(*spot)[0] for name, _, spot in AREAS}
        check("all areas have distinct regions",
              None not in ids.values() and len(set(ids.values())) == len(AREAS),
              ", ".join(f"{name}={value}" for name, value in ids.items()))

        print("\nstep 1: piston and note-block callbacks execute")
        for _, _, spot in AREAS:
            power(rcon, spot, True)
        time.sleep(2.0)
        for name, _, spot in AREAS:
            check(f"area {name} pistons extended", piston_state(rcon, spot, True))
        for _, _, spot in AREAS:
            power(rcon, spot, False)
        time.sleep(2.0)
        for name, _, spot in AREAS:
            check(f"area {name} pistons retracted", piston_state(rcon, spot, False))

        print("\nstep 2: repeated event queues stay ordered and drain")
        deadline = time.monotonic() + max(4, args.soak_seconds)
        cycles = 0
        while time.monotonic() + 1.0 <= deadline:
            for _, _, spot in AREAS:
                build_bank(rcon, spot)
            for _, _, spot in AREAS:
                power(rcon, spot, True)
            time.sleep(0.45)
            extended = all(piston_state(rcon, spot, True) for _, _, spot in AREAS)
            for _, _, spot in AREAS:
                power(rcon, spot, False)
            time.sleep(0.45)
            retracted = all(piston_state(rcon, spot, False) for _, _, spot in AREAS)
            cycles += 1
            if not (extended and retracted):
                check("event soak preserved piston behavior", False, f"cycle={cycles}")
                break
        check("event workload completed", cycles > 0, f"cycles={cycles}")

        topology_churn(rcon)

        print("\nstep 4: phase and ownership diagnostics")
        time.sleep(2.0)
        phases = rcon.command("tessellate phases")
        event_worker, event_main, event_peak = phase(phases, "block-events")
        packet_worker, packet_main, _ = phase(phases, "block-event-packets")
        if not args.skip_concurrency:
            check("block-event callbacks reached two workers", event_worker > 0 and event_peak >= 2,
                  f"worker={event_worker}, peak={event_peak}")
        else:
            check("serial fallback executed block events on main",
                  event_worker == 0 and event_main > 0,
                  f"worker={event_worker}, main={event_main}")
        check("block-event packets stayed on main", packet_worker == 0 and packet_main > 0,
              f"worker={packet_worker}, main={packet_main}")
        check("block-event queues drained", "block-event queues: 0 pending" in phases)
        check("deferred queues drained", "deferred queues: 0 pending" in phases)
        check("parallel worker system stayed healthy",
              "parallel=true" in phases and "unavailable chunks=0" in phases
              and not re.search(r"failures [1-9]\d*", phases))
        violations = rcon.command("tessellate violations")
        check("zero ownership violations", "no ownership violations" in violations,
              violations.strip().splitlines()[0] if violations.strip() else "empty")

        print("\ncleaning up")
        for _, _, spot in AREAS:
            x, z = spot
            cmd(rcon, f"fill {x} {Y} {z} {x + 8} {Y} {z + ROWS - 1} air")
        rcon.command("execute in minecraft:overworld run forceload remove all")

    print("\n" + "=" * 60)
    if FAILURES:
        print(f"FAILED: {len(FAILURES)} check(s)")
        for failure in FAILURES:
            print(f"  - {failure}")
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
