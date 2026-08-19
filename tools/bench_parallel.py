#!/usr/bin/env python3
"""Measure throughput with several loaded regions.

Each area is far enough from the others to remain a separate region. `ItemEntity.Age` measures
regional TPS, while the report summarizes how many areas stay near 20 TPS and the global MSPT.

Usage:
    python tools/bench_parallel.py --areas 4 --mobs 900 --save /tmp/par.json --label parallel
    python tools/bench_parallel.py --report /tmp/par.json
"""

import argparse
import json
import os
import random
import re
import statistics
import sys
import time

from rcon import Rcon

AREA_SPACING = 2048
AREA_ORIGIN = (512, 512)
AREA_SIZE = 128
FLOOR_Y = 3
SPAWN_Y = 4
PEN_HALF = 14

FAILURES = []


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {label}" + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def area_bounds(index):
    x0 = AREA_ORIGIN[0] + index * AREA_SPACING
    z0 = AREA_ORIGIN[1]
    return (x0, z0), (x0 + AREA_SIZE - 1, z0 + AREA_SIZE - 1)


def center(lo, hi):
    return ((lo[0] + hi[0]) // 2, (lo[1] + hi[1]) // 2)


def setup(rcon, areas):
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
    rcon.command("time set midnight")
    rcon.command("execute in minecraft:overworld run forceload remove all")
    rcon.command("kill @e[type=zombie]")
    rcon.command("kill @e[type=item]")

    for index in range(areas):
        lo, hi = area_bounds(index)
        rcon.command(
            f"execute in minecraft:overworld run forceload add {lo[0]} {lo[1]} {hi[0]} {hi[1]}")
    time.sleep(2.5)

    for index in range(areas):
        lo, hi = area_bounds(index)
        rcon.command(
            f"execute in minecraft:overworld run fill "
            f"{lo[0]} {FLOOR_Y} {lo[1]} {hi[0]} {FLOOR_Y} {hi[1]} minecraft:stone")
        cx, cz = center(lo, hi)
        rcon.command(
            f"execute in minecraft:overworld run fill "
            f"{cx - PEN_HALF - 2} {SPAWN_Y} {cz - PEN_HALF - 2} "
            f"{cx + PEN_HALF + 2} {SPAWN_Y + 5} {cz + PEN_HALF + 2} minecraft:air")
        probe = rcon.command(
            f"execute in minecraft:overworld if block {cx} {FLOOR_Y} {cz} minecraft:stone")
        check(f"area {index} has a floor", "Test passed" in probe, probe.strip())


def spawn_observer(rcon, index):
    lo, hi = area_bounds(index)
    cx, cz = center(lo, hi)
    rcon.command(
        f"execute in minecraft:overworld run summon minecraft:item {cx} {SPAWN_Y} {cz} "
        '{Item:{id:"minecraft:diamond",Count:1b},Age:-30000s,PickupDelay:32767s,'
        'CustomName:\'{"text":"observer-' + str(index) + '"}\'}')
    return cx, cz


def read_age(rcon, x, z):
    out = rcon.command(
        f"execute in minecraft:overworld run data get entity "
        f"@e[type=item,limit=1,sort=nearest,x={x},y={SPAWN_Y},z={z}] Age")
    match = re.search(r"following entity data: (-?\d+)", out)
    if not match:
        raise RuntimeError(f"could not read Age at [{x}, {z}]: {out}")
    return int(match.group(1))


def spawn_load(rcon, index, count):
    lo, hi = area_bounds(index)
    cx, cz = center(lo, hi)
    rng = random.Random(20260818 + index)
    for i in range(count):
        x = rng.randint(cx - PEN_HALF, cx + PEN_HALF)
        z = rng.randint(cz - PEN_HALF, cz + PEN_HALF)
        rcon.command(
            f"execute in minecraft:overworld run summon minecraft:zombie {x} {SPAWN_Y} {z} "
            '{PersistenceRequired:1b,Invulnerable:1b,IsBaby:0b}')


def count_zombies(rcon):
    rcon.command("scoreboard objectives add optimalcount dummy")
    rcon.command("execute in minecraft:overworld store result score #count optimalcount "
                 "if entity @e[type=minecraft:zombie]")
    out = rcon.command("scoreboard players get #count optimalcount")
    match = re.search(r"has (-?\d+) ", out)
    return int(match.group(1)) if match else -1


def tick_stats(rcon):
    out = rcon.command("tick query")
    mean = re.search(r"Average time per tick: ([\d.]+)ms", out)
    p95 = re.search(r"P95: ([\d.]+)ms", out)
    return (float(mean.group(1)) if mean else -1.0,
            float(p95.group(1)) if p95 else -1.0)


def execution_mode(rcon):
    out = rcon.command("optimal regions")
    match = re.search(r"execution: (.+)", out)
    return match.group(1).strip() if match else "unknown"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--areas", type=int, default=4)
    parser.add_argument("--mobs", type=int, default=900, help="zombies per area")
    parser.add_argument("--window", type=float, default=12.0)
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=2)
    parser.add_argument("--expect-workers", action="store_true")
    parser.add_argument("--require-budget", action="store_true")
    parser.add_argument("--label", help="name for this arm, e.g. serial or parallel")
    parser.add_argument("--save", metavar="FILE")
    parser.add_argument("--report", metavar="FILE")
    args = parser.parse_args()

    if args.report:
        with open(args.report, encoding="utf-8") as handle:
            runs = json.load(handle)
        print(f"\n{'arm':28s} {'areas at 20 TPS':>16s} {'slowest':>9s} {'MSPT':>8s}")
        for run in runs:
            at_full = sum(1 for tps in run["tps"] if tps >= 19.0)
            print(f"{run['label']:28s} {at_full:>10d} / {len(run['tps']):<3d} "
                  f"{min(run['tps']):8.1f} {run['mspt']:8.1f}")
        if len(runs) == 2:
            before, after = runs
            before_full = sum(1 for t in before["tps"] if t >= 19.0)
            after_full = sum(1 for t in after["tps"] if t >= 19.0)
            print(f"\n{after['label']}: {after_full}/{len(after['tps'])} at full speed; "
                  f"{before['label']}: {before_full}/{len(before['tps'])} "
                  f"(delta {after_full - before_full:+d})")
        return 0

    with Rcon(timeout=600.0) as rcon:
        mode = execution_mode(rcon)
        print(f"optimal parallel throughput benchmark\nexecution: {mode}\n")

        setup(rcon, args.areas)
        observers = [spawn_observer(rcon, i) for i in range(args.areas)]
        time.sleep(1.5)

        print(f"\nspawning {args.mobs} zombies into each of {args.areas} area(s)")
        for index in range(args.areas):
            spawn_load(rcon, index, args.mobs)
            print(f"    area {index} loaded")
        time.sleep(4.0)
        expected_zombies = args.areas * args.mobs
        found_zombies = count_zombies(rcon)
        check("the requested load is actually populated",
              found_zombies == expected_zombies,
              f"expected {expected_zombies}, found {found_zombies}")

        print(f"\n{args.warmup} warmup window(s), then {args.samples} measured")
        sample_tps, sample_mspt, sample_p95 = [], [], []
        for sample in range(args.warmup + args.samples):
            before = []
            for pos in observers:
                before.append((read_age(rcon, *pos), time.monotonic()))
            time.sleep(args.window)
            after = []
            for pos in observers:
                after.append((read_age(rcon, *pos), time.monotonic()))
            rates = [(end_age - start_age) / (end_time - start_time)
                     for (start_age, start_time), (end_age, end_time)
                     in zip(before, after)]
            mean_mspt, p95_mspt = tick_stats(rcon)
            warmup = sample < args.warmup
            print(f"    {'warmup' if warmup else 'sample'}: "
                  f"{min(rates):5.1f}..{max(rates):5.1f} TPS, "
                  f"{mean_mspt:6.1f} ms mean, {p95_mspt:6.1f} ms p95")
            if not warmup:
                sample_tps.append(rates)
                sample_mspt.append(mean_mspt)
                sample_p95.append(p95_mspt)

        tps = [statistics.median(rates) for rates in zip(*sample_tps)]
        mean_mspt = statistics.median(sample_mspt)
        p95_mspt = statistics.median(sample_p95)

        print("\n  medians")
        for index, rate in enumerate(tps):
            print(f"  area {index}       {rate:5.1f} TPS")
        print(f"  global MSPT  {mean_mspt:.1f} ms mean, {p95_mspt:.1f} ms p95")

        print("\nregion map:")
        for line in rcon.command("optimal regions").splitlines():
            if "region#" in line or "overworld" in line:
                print(f"    {line.strip()}")

        at_full = sum(1 for rate in tps if rate >= 19.0)
        print("\n" + "=" * 68)
        print(f"THROUGHPUT RESULT ({mode})")
        print(f"  areas still at 20 TPS:  {at_full} of {args.areas}")
        print(f"  slowest area:           {min(tps):.1f} TPS")
        print(f"  global MSPT:            {mean_mspt:.1f} ms")

        check("every area is a separate region",
              len(re.findall(r"region#", rcon.command("optimal regions"))) >= args.areas,
              "areas merged into fewer regions than expected")
        if args.require_budget:
            check("global MSPT stays inside the 50 ms tick budget", mean_mspt < 50.0,
                  f"{mean_mspt:.1f} ms")
        final_zombies = count_zombies(rcon)
        check("the entity load remained constant", final_zombies == expected_zombies,
              f"expected {expected_zombies}, found {final_zombies}")

        phases = rcon.command("optimal phases")
        entities = re.search(
            r"entities: worker (\d+)/.*main (\d+)/.*peak (\d+)", phases)
        worker_calls = int(entities.group(1)) if entities else -1
        worker_peak = int(entities.group(3)) if entities else -1
        if args.expect_workers:
            check("entity work really overlapped on workers",
                  worker_calls > 0 and worker_peak >= 2,
                  f"worker calls={worker_calls}, peak={worker_peak}")
        else:
            check("serial arm did not execute entity work on workers", worker_calls == 0,
                  f"worker calls={worker_calls}")
        boundary_rows = re.findall(
            r"boundary .*?pending (\d+).*?failures (\d+)/(\d+), balanced=(true|false)",
            phases)
        check("all measured boundaries drained and balanced",
              bool(boundary_rows) and all(row == ("0", "0", "0", "true")
                                          for row in boundary_rows),
              f"{len(boundary_rows)} active boundary categories")

        print("\ncleaning up")
        rcon.command("kill @e[type=zombie]")
        rcon.command("kill @e[type=item]")
        rcon.command("execute in minecraft:overworld run forceload remove all")

    if args.save and args.label and not FAILURES:
        runs = []
        if os.path.exists(args.save):
            with open(args.save, encoding="utf-8") as handle:
                runs = json.load(handle)
        runs.append({"label": args.label, "mode": mode, "areas": args.areas,
                     "mobs": args.mobs, "tps": tps, "mspt": mean_mspt,
                     "sample_tps": sample_tps, "sample_mspt": sample_mspt,
                     "sample_p95": sample_p95})
        with open(args.save, "w", encoding="utf-8") as handle:
            json.dump(runs, handle, indent=2)
        print(f"saved to {args.save}")
    elif args.save and args.label:
        print(f"not saved to {args.save}: validation failed")

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
