#!/usr/bin/env python3
"""Measure whether load in one region affects a distant region.

`ItemEntity.Age` is used as an external tick counter. At 20 TPS it advances about 200 times per
10 seconds. The benchmark also records global MSPT and verifies the spawned entity count.

Usage:
    python tools/bench_isolation.py [--mobs 3000] [--window 10]
"""

import argparse
import random
import re
import sys
import time

from rcon import Rcon

# Area A (observer): chunks (32..39, 32..39)
A_MIN, A_MAX = (512, 512), (639, 639)
# Area C (lag machine): chunks (96..103, 32..39); 15 sections clear of A at shift 2
C_MIN, C_MAX = (1536, 512), (1663, 639)

FLOOR_Y = 3
SPAWN_Y = 4

FAILURES = []


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {label}" + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def center(lo, hi):
    return ((lo[0] + hi[0]) // 2, (lo[1] + hi[1]) // 2)


def setup_world(rcon):
    print("preparing the world")
    for rule, value in [
        ("doMobSpawning", "false"),
        ("doDaylightCycle", "false"),
        ("doWeatherCycle", "false"),
        ("doFireTick", "false"),
        ("mobGriefing", "false"),
        ("randomTickSpeed", "0"),
        ("doMobLoot", "false"),
        ("doTileDrops", "false"),
    ]:
        rcon.command(f"gamerule {rule} {value}")
    # Keep zombies from burning during the run.
    rcon.command("time set midnight")

    rcon.command("execute in minecraft:overworld run forceload remove all")
    rcon.command("kill @e[type=item]")
    rcon.command("kill @e[type=zombie]")

    for name, lo, hi in (("A", A_MIN, A_MAX), ("C", C_MIN, C_MAX)):
        rcon.command(
            f"execute in minecraft:overworld run forceload add {lo[0]} {lo[1]} {hi[0]} {hi[1]}")
    time.sleep(2.0)

    # The superflat benchmark world has no floor.
    for name, lo, hi in (("A", A_MIN, A_MAX), ("C", C_MIN, C_MAX)):
        rcon.command(
            f"execute in minecraft:overworld run fill "
            f"{lo[0]} {FLOOR_Y} {lo[1]} {hi[0]} {FLOOR_Y} {hi[1]} minecraft:stone")
        cx, cz = center(lo, hi)
        probe = rcon.command(
            f"execute in minecraft:overworld if block {cx} {FLOOR_Y} {cz} minecraft:stone")
        check(f"area {name} has a floor", "Test passed" in probe, probe.strip())


def spawn_observer(rcon, lo, hi, label):
    """Spawn an item whose Age tracks ticks in this area."""
    cx, cz = center(lo, hi)
    rcon.command(
        f"execute in minecraft:overworld run summon minecraft:item {cx} {SPAWN_Y} {cz} "
        '{Item:{id:"minecraft:diamond",Count:1b},Age:-30000s,PickupDelay:32767s,'
        'CustomName:\'{"text":"' + label + '"}\'}')
    return cx, cz


def read_age(rcon, x, z):
    out = rcon.command(
        f"execute in minecraft:overworld run data get entity "
        f"@e[type=item,limit=1,sort=nearest,x={x},y={SPAWN_Y},z={z}] Age")
    match = re.search(r"following entity data: (-?\d+)", out)
    if not match:
        raise RuntimeError(f"could not read Age at [{x}, {z}]: {out}")
    return int(match.group(1))


# Keep mobs dense enough to exercise collision-heavy mob-farm behavior.
PEN_HALF = 16


def spawn_lag_machine(rcon, count, lo, hi):
    cx, cz = center(lo, hi)
    print(f"spawning {count} zombies into a {2 * PEN_HALF}x{2 * PEN_HALF} pen at "
          f"[{cx}, {cz}] (this takes a moment)")
    rng = random.Random(20260818)
    for i in range(count):
        x = rng.randint(cx - PEN_HALF, cx + PEN_HALF)
        z = rng.randint(cz - PEN_HALF, cz + PEN_HALF)
        rcon.command(
            f"execute in minecraft:overworld run summon minecraft:zombie {x} {SPAWN_Y} {z} "
            '{PersistenceRequired:1b,IsBaby:0b}')
        if (i + 1) % 500 == 0:
            print(f"    {i + 1}/{count}")


def count_entities(rcon, entity_type):
    """Count matching entities without removing them."""
    rcon.command("scoreboard objectives add optimalcount dummy")
    rcon.command(
        f"execute in minecraft:overworld store result score #count optimalcount "
        f"if entity @e[type={entity_type}]")
    out = rcon.command("scoreboard players get #count optimalcount")
    match = re.search(r"has (-?\d+) ", out)
    return int(match.group(1)) if match else -1


def tick_stats(rcon):
    out = rcon.command("tick query")
    mean = re.search(r"Average time per tick: ([\d.]+)ms", out)
    p95 = re.search(r"P95: ([\d.]+)ms", out)
    return (float(mean.group(1)) if mean else -1.0,
            float(p95.group(1)) if p95 else -1.0)


def measure(rcon, a_pos, c_pos, window):
    print(f"\nmeasuring over a {window:.0f}s wall-clock window")
    a0, c0 = read_age(rcon, *a_pos), read_age(rcon, *c_pos)
    t0 = time.time()
    time.sleep(window)
    a1, c1 = read_age(rcon, *a_pos), read_age(rcon, *c_pos)
    elapsed = time.time() - t0

    a_ticks, c_ticks = a1 - a0, c1 - c0
    mean_mspt, p95_mspt = tick_stats(rcon)

    print(f"\n  elapsed        {elapsed:.2f} s")
    print(f"  area A ticks   {a_ticks}   ({a_ticks / elapsed:.1f} TPS)")
    print(f"  area C ticks   {c_ticks}   ({c_ticks / elapsed:.1f} TPS)")
    print(f"  global MSPT    {mean_mspt:.1f} ms mean, {p95_mspt:.1f} ms p95")
    return a_ticks, c_ticks, elapsed, mean_mspt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mobs", type=int, default=3000, help="zombies in the lag machine")
    parser.add_argument("--window", type=float, default=10.0, help="measurement window, seconds")
    parser.add_argument("--skip-setup", action="store_true", help="reuse the existing world state")
    args = parser.parse_args()

    with Rcon(timeout=180.0) as rcon:
        print("optimal regional isolation benchmark\n")

        if not args.skip_setup:
            setup_world(rcon)

        a_pos = spawn_observer(rcon, A_MIN, A_MAX, "observer-A")
        c_pos = spawn_observer(rcon, C_MIN, C_MAX, "observer-C")
        time.sleep(1.0)

        print("\nunloaded baseline (no lag machine)")
        base_a, base_c, base_elapsed, base_mspt = measure(rcon, a_pos, c_pos, args.window)
        expected = 20.0 * base_elapsed
        check("both areas run at ~20 TPS with no load",
              base_a > expected * 0.9 and base_c > expected * 0.9,
              f"A={base_a}, C={base_c}, expected ~{expected:.0f}")

        if args.mobs > 0:
            spawn_lag_machine(rcon, args.mobs, C_MIN, C_MAX)
            time.sleep(3.0)

            print(f"\nloaded ({args.mobs} zombies in area C)")
            load_a, load_c, load_elapsed, load_mspt = measure(rcon, a_pos, c_pos, args.window)

            print("\nregion map during load:")
            for line in rcon.command("optimal regions").splitlines():
                if "overworld" in line or "region#" in line:
                    print(f"    {line.strip()}")

            zombies = count_entities(rcon, "minecraft:zombie")
            check("the lag machine is actually populated", zombies == args.mobs,
                  f"expected {args.mobs} zombies, found {zombies}")

            print("\n" + "=" * 68)
            print("ISOLATION RESULT")
            print(f"  area A (observer):    {load_a:4d} ticks / {load_elapsed:.1f}s"
                  f"  = {load_a / load_elapsed:5.1f} TPS")
            print(f"  area C (lag machine): {load_c:4d} ticks / {load_elapsed:.1f}s"
                  f"  = {load_c / load_elapsed:5.1f} TPS")
            print(f"  global MSPT:          {load_mspt:.1f} ms (unloaded {base_mspt:.1f} ms)")
            # Isolation is judged by observer TPS and global MSPT.
            expected_full = 20.0 * load_elapsed
            observer_ok = load_a >= expected_full * 0.95
            budget_ok = load_mspt < 50.0
            print(f"\n  isolation achieved:   {'YES' if observer_ok and budget_ok else 'NO'}")

            check("observer area holds ~20 TPS despite the lag machine", observer_ok,
                  f"{load_a} ticks, expected >= {expected_full * 0.95:.0f}")
            check("global MSPT stays inside the 50 ms tick budget", budget_ok,
                  f"{load_mspt:.1f} ms")
            if load_c < expected_full * 0.95:
                print(f"    area C was throttled to {load_c / load_elapsed:.1f} TPS to make room")
            else:
                print("    area C was not throttled; its load fitted inside the budget")

        print("\ncleaning up")
        rcon.command("kill @e[type=zombie]")
        rcon.command("kill @e[type=item]")
        rcon.command("execute in minecraft:overworld run forceload remove all")

    print("\n" + "=" * 68)
    if FAILURES:
        print(f"{len(FAILURES)} check(s) FAILED")
        for name in FAILURES:
            print(f"  - {name}")
        return 1
    print("all harness checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
