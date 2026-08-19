#!/usr/bin/env python3
"""Check per-region entity tracking across spawns, moves, and verification passes.

The test also checks orphan counts, region cost attribution, and entity tick rate.

Run the server, then:
    python tools/verify_entities.py
"""

import re
import sys
import time

from rcon import Rcon, RconError
from regions import overworld

AREA_A = (512, 512, 639, 639)
AREA_C = (1536, 512, 1663, 639)
A_CENTER = (575, 575)
C_CENTER = (1599, 575)
FLOOR_Y = 3
SPAWN_Y = 5

FAILURES = []


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {label}" + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def state(rcon):
    return overworld(rcon)


def setup(rcon):
    print("preparing the world")
    for cmd in [
        "gamerule doMobSpawning false",
        "gamerule doDaylightCycle false",
        "gamerule doWeatherCycle false",
        "gamerule randomTickSpeed 0",
        "time set midnight",
        "execute in minecraft:overworld run forceload remove all",
        "kill @e[type=zombie]",
        "kill @e[type=item]",
        "kill @e[type=armor_stand]",
    ]:
        rcon.command(cmd)
    time.sleep(1.5)
    for box in (AREA_A, AREA_C):
        rcon.command(
            f"execute in minecraft:overworld run forceload add {box[0]} {box[1]} {box[2]} {box[3]}")
    time.sleep(4.0)
    # Clear persistent entities that load with these chunks.
    rcon.command("execute in minecraft:overworld run kill @e[type=!minecraft:player]")
    time.sleep(1.0)
    for box in (AREA_A, AREA_C):
        rcon.command(
            f"execute in minecraft:overworld run fill "
            f"{box[0]} {FLOOR_Y} {box[1]} {box[2]} {FLOOR_Y} {box[3]} minecraft:stone")
    time.sleep(2.0)


def summon(rcon, count, x, z, spread=8):
    for i in range(count):
        rcon.command(
            f"execute in minecraft:overworld run summon minecraft:zombie "
            f"{x + (i % spread)} {SPAWN_Y} {z + (i // spread)} "
            "{PersistenceRequired:1b,NoAI:1b}")


def main():
    with Rcon(timeout=120.0) as rcon:
        print("tessellate per-region entity verification\n")
        setup(rcon)

        print("step 0: regions established, no entities")
        before = state(rcon)
        a_id, _ = before.region_near(*A_CENTER)
        c_id, _ = before.region_near(*C_CENTER)
        check("both test areas have a region", a_id is not None and c_id is not None,
              f"A={a_id}, C={c_id}")
        check("no entities tracked yet", before.total_entities() == 0,
              f"{before.total_entities()}")
        base_misplaced = before.entity_misplaced

        print("\nstep 1: spawn into an established region")
        summon(rcon, 40, A_CENTER[0], A_CENTER[1])
        summon(rcon, 10, C_CENTER[0], C_CENTER[1])
        time.sleep(2.0)
        after = state(rcon)
        _, a_info = after.region_near(*A_CENTER)
        _, c_info = after.region_near(*C_CENTER)
        check("area A region holds exactly the 40 spawned there",
              a_info is not None and a_info["entities"] == 40,
              f"{a_info['entities'] if a_info else '?'}")
        check("area C region holds exactly the 10 spawned there",
              c_info is not None and c_info["entities"] == 10,
              f"{c_info['entities'] if c_info else '?'}")
        check("orphan bucket is empty", after.entity_orphans == 0, f"{after.entity_orphans}")
        check("nothing needed repairing", after.entity_misplaced == base_misplaced,
              f"misplaced {base_misplaced} -> {after.entity_misplaced}")

        print("\nstep 2: force a verify pass and confirm it finds nothing")
        # The default verification interval is 200 ticks (10 seconds).
        time.sleep(12.0)
        verified = state(rcon)
        check("verify pass found no misplaced entities",
              verified.entity_misplaced == base_misplaced,
              f"misplaced {base_misplaced} -> {verified.entity_misplaced}")
        check("counts unchanged after verify",
              verified.total_entities() == after.total_entities(),
              f"{after.total_entities()} -> {verified.total_entities()}")

        print("\nstep 3: teleport entities across the region boundary")
        rcon.command(
            f"execute in minecraft:overworld run teleport @e[type=zombie,limit=15,"
            f"x={A_CENTER[0]},y={SPAWN_Y},z={A_CENTER[1]},sort=nearest] "
            f"{C_CENTER[0]} {SPAWN_Y} {C_CENTER[1] + 20}")
        time.sleep(2.0)
        moved = state(rcon)
        _, a_after = moved.region_near(*A_CENTER)
        _, c_after = moved.region_near(*C_CENTER)
        check("area A lost the 15 that left",
              a_after is not None and a_after["entities"] == 25,
              f"{a_after['entities'] if a_after else '?'}")
        check("area C gained them",
              c_after is not None and c_after["entities"] == 25,
              f"{c_after['entities'] if c_after else '?'}")
        check("total is conserved, nothing duplicated or dropped",
              moved.total_entities() == 50, f"{moved.total_entities()}")
        check("orphan bucket still empty", moved.entity_orphans == 0, f"{moved.entity_orphans}")
        check("relocation needed no repair", moved.entity_misplaced == base_misplaced,
              f"misplaced {base_misplaced} -> {moved.entity_misplaced}")

        print("\nstep 4: per-region cost attribution")
        for rid, info in sorted(moved.regions.items()):
            print(f"    region#{rid}: {info['entities']:3d} entity(s), "
                  f"{info['entity_ms']:.3f} ms last, near [{info['x']}, {info['z']}]")
        check("the loaded regions report nonzero cost",
              any(i["entity_ms"] > 0 for i in moved.regions.values() if i["entities"] > 0),
              "all zero")

        print("\nstep 5: entities are still actually ticking")
        rcon.command(
            f"execute in minecraft:overworld run summon minecraft:item "
            f"{A_CENTER[0]} {SPAWN_Y} {A_CENTER[1]} "
            '{Item:{id:"minecraft:diamond",Count:1b},Age:-30000s,PickupDelay:32767s}')
        time.sleep(1.0)

        def age():
            out = rcon.command(
                f"execute in minecraft:overworld run data get entity "
                f"@e[type=item,limit=1,sort=nearest,x={A_CENTER[0]},y={SPAWN_Y},z={A_CENTER[1]}] Age")
            m = re.search(r"following entity data: (-?\d+)", out)
            if not m:
                raise RconError(f"could not read Age: {out}")
            return int(m.group(1))

        a0 = age()
        t0 = time.time()
        time.sleep(6.0)
        gained = age() - a0
        elapsed = time.time() - t0
        tps = gained / elapsed
        print(f"    {gained} ticks in {elapsed:.1f}s = {tps:.1f} TPS")
        check("entities tick at ~20 TPS through the region path", tps > 19.0,
              f"{tps:.1f} TPS")

        print("\nstep 6: ownership guard")
        violations = rcon.command("tessellate violations")
        check("zero ownership violations", "no ownership violations" in violations,
              violations.strip().splitlines()[0] if violations.strip() else "empty")

        print("\ncleaning up")
        rcon.command("kill @e[type=zombie]")
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
