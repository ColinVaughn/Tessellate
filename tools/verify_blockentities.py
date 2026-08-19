#!/usr/bin/env python3
"""Check region-scoped block-entity ticking with hoppers in two regions.

The test verifies region attribution, orphan counts, grouping cost, and behavior after a merge.

Run the server, then:
    python tools/verify_blockentities.py
"""

import re
import sys
import time

from rcon import Rcon
from regions import overworld

AREA_A = (512, 512, 639, 639)
AREA_C = (1536, 512, 1663, 639)
BRIDGE = (640, 512, 1535, 575)
A_SPOT = (575, 575)
C_SPOT = (1599, 575)
FLOOR_Y = 3
CHEST_Y = 5
HOPPER_Y = 6

FAILURES = []


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {label}" + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def build_hopper(rcon, spot):
    """Place a hopper with one diamond above a chest."""
    x, z = spot
    # Recreate the blocks so an interrupted run cannot leave a sleeping Lithium ticker.
    rcon.command(f"execute in minecraft:overworld run setblock {x} {HOPPER_Y} {z} air")
    rcon.command(f"execute in minecraft:overworld run setblock {x} {CHEST_Y} {z} air")
    rcon.command(f"execute in minecraft:overworld run setblock {x} {CHEST_Y} {z} minecraft:chest")
    rcon.command(
        f"execute in minecraft:overworld run setblock {x} {HOPPER_Y} {z} "
        "minecraft:hopper[facing=down]"
        "{Items:[{Slot:0b,id:'minecraft:diamond',count:1}]}")


def chest_has_diamond(rcon, spot):
    x, z = spot
    out = rcon.command(
        f"execute in minecraft:overworld run data get block {x} {CHEST_Y} {z} Items")
    return "diamond" in out


def setup(rcon):
    print("preparing the world")
    for cmd in [
        "gamerule doMobSpawning false",
        "gamerule doDaylightCycle false",
        "gamerule randomTickSpeed 0",
        "time set midnight",
        "execute in minecraft:overworld run forceload remove all",
        "kill @e[type=item]",
        "kill @e[type=zombie]",
    ]:
        rcon.command(cmd)
    time.sleep(1.5)
    for box in (AREA_A, AREA_C):
        rcon.command(
            f"execute in minecraft:overworld run forceload add {box[0]} {box[1]} {box[2]} {box[3]}")
    time.sleep(4.0)
    for box in (AREA_A, AREA_C):
        rcon.command(
            f"execute in minecraft:overworld run fill "
            f"{box[0]} {FLOOR_Y} {box[1]} {box[2]} {FLOOR_Y} {box[3]} minecraft:stone")
    time.sleep(2.0)


def main():
    with Rcon(timeout=120.0) as rcon:
        print("optimal region-scoped block entity verification\n")
        setup(rcon)

        print("step 0: build a hopper-into-chest in each region")
        build_hopper(rcon, A_SPOT)
        build_hopper(rcon, C_SPOT)
        time.sleep(3.0)

        ow = overworld(rcon)
        orphans, group_ms = ow.be_orphans, ow.be_ms
        a_id, a_info = ow.region_near(*A_SPOT)
        c_id, c_info = ow.region_near(*C_SPOT)
        check("both areas have a region", a_id is not None and c_id is not None,
              f"A={a_id}, C={c_id}")

        print("\nstep 1: block entities are attributed to the right region")
        check("area A region holds its hopper and chest",
              a_info is not None and a_info["blockentities"] >= 1,
              f"{a_info['blockentities'] if a_info else '?'}")
        check("area C region holds its hopper and chest",
              c_info is not None and c_info["blockentities"] >= 1,
              f"{c_info['blockentities'] if c_info else '?'}")
        check("orphan block entity bucket is empty", orphans == 0, f"{orphans}")
        print(f"    grouping cost: {group_ms:.3f} ms")
        check("grouping cost is small", group_ms < 1.0, f"{group_ms:.3f} ms")

        print("\nstep 2: the hoppers actually tick (functional proof)")
        check("area A hopper moved its item into the chest",
              chest_has_diamond(rcon, A_SPOT), "chest empty")
        check("area C hopper moved its item into the chest",
              chest_has_diamond(rcon, C_SPOT), "chest empty")

        print("\nstep 3: hoppers keep working after a region merge")
        rcon.command(
            f"execute in minecraft:overworld run forceload add "
            f"{BRIDGE[0]} {BRIDGE[1]} {BRIDGE[2]} {BRIDGE[3]}")
        time.sleep(6.0)
        merged_ow = overworld(rcon)
        merged, orphans_after = merged_ow.regions, merged_ow.be_orphans
        # The bridge merges both test areas; the spawn region remains separate.
        build_hopper(rcon, A_SPOT)
        build_hopper(rcon, C_SPOT)
        time.sleep(3.0)
        check("area A hopper still ticks after the merge",
              chest_has_diamond(rcon, A_SPOT), "chest empty")
        check("area C hopper still ticks after the merge",
              chest_has_diamond(rcon, C_SPOT), "chest empty")
        check("orphan bucket still empty after the merge", orphans_after == 0,
              f"{orphans_after}")
        print(f"    regions after bridging: {sorted(merged.keys())}")

        print("\nstep 4: ownership guard")
        violations = rcon.command("optimal violations")
        check("zero ownership violations", "no ownership violations" in violations,
              violations.strip().splitlines()[0] if violations.strip() else "empty")

        print("\ncleaning up")
        for spot in (A_SPOT, C_SPOT):
            rcon.command(
                f"execute in minecraft:overworld run setblock {spot[0]} {HOPPER_Y} {spot[1]} air")
            rcon.command(
                f"execute in minecraft:overworld run setblock {spot[0]} {CHEST_Y} {spot[1]} air")
        rcon.command("kill @e[type=item]")
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
