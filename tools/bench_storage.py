#!/usr/bin/env python3
"""Measure the MSPT cost of sharded entity storage under a fixed load.

Disable adaptive throttling so both modes perform the same work. Each mode needs a server restart;
save the samples to one JSON file and run the modes in alternating order to reduce warmup bias.

Usage:
    python tools/bench_storage.py --label sharded --save /tmp/storage-ab.json
    python tools/bench_storage.py --report /tmp/storage-ab.json
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

AREA_MIN, AREA_MAX = (512, 512), (639, 639)
FLOOR_Y = 3
SPAWN_Y = 4
PEN_HALF = 16

WARMUP_SAMPLES = 3


def center(lo, hi):
    return ((lo[0] + hi[0]) // 2, (lo[1] + hi[1]) // 2)


def setup(rcon):
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
    rcon.command(
        f"execute in minecraft:overworld run forceload add "
        f"{AREA_MIN[0]} {AREA_MIN[1]} {AREA_MAX[0]} {AREA_MAX[1]}")
    time.sleep(2.0)
    rcon.command(
        f"execute in minecraft:overworld run fill "
        f"{AREA_MIN[0]} {FLOOR_Y} {AREA_MIN[1]} {AREA_MAX[0]} {FLOOR_Y} {AREA_MAX[1]} "
        f"minecraft:stone")


def spawn(rcon, count):
    cx, cz = center(AREA_MIN, AREA_MAX)
    print(f"spawning {count} zombies at [{cx}, {cz}]")
    rng = random.Random(20260818)
    for i in range(count):
        x = rng.randint(cx - PEN_HALF, cx + PEN_HALF)
        z = rng.randint(cz - PEN_HALF, cz + PEN_HALF)
        rcon.command(
            f"execute in minecraft:overworld run summon minecraft:zombie {x} {SPAWN_Y} {z} "
            '{PersistenceRequired:1b,IsBaby:0b}')
        if (i + 1) % 500 == 0:
            print(f"    {i + 1}/{count}")


def tick_stats(rcon):
    out = rcon.command("tick query")
    mean = re.search(r"Average time per tick: ([\d.]+)ms", out)
    p95 = re.search(r"P95: ([\d.]+)ms", out)
    return (float(mean.group(1)) if mean else -1.0,
            float(p95.group(1)) if p95 else -1.0)


def server_state(rcon):
    out = rcon.command("tessellate regions")
    sharded = "shardEntityStorage=true" in out.replace(" ", "")
    shards = re.search(r"(\d+) shard\(s\)", out)
    throttled = re.search(r"1/([2-9]|\d\d+) rate", out) is not None
    return sharded, int(shards.group(1)) if shards else 0, throttled


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", help="name for this arm, e.g. sharded or vanilla")
    parser.add_argument("--mobs", type=int, default=2000)
    parser.add_argument("--window", type=float, default=5.0)
    parser.add_argument("--samples", type=int, default=10, help="after warmup")
    parser.add_argument("--save", metavar="FILE", help="append this arm's samples")
    parser.add_argument("--report", metavar="FILE", help="summarise a file and exit")
    args = parser.parse_args()

    if args.report:
        with open(args.report, encoding="utf-8") as handle:
            runs = json.load(handle)
        print(f"\n{'arm':24s} {'runs':>4s} {'mean MSPT':>10s} {'p95':>8s} {'samples':>8s}")
        by_label = {}
        for run in runs:
            by_label.setdefault(run["label"], []).extend(run["means"])
        for label, means in sorted(by_label.items()):
            count = sum(1 for r in runs if r["label"] == label)
            print(f"{label:24s} {count:4d} {statistics.median(means):10.2f} "
                  f"{max(means):8.2f} {len(means):8d}")
        labels = sorted(by_label)
        if len(labels) == 2:
            a, b = (statistics.median(by_label[l]) for l in labels)
            delta = b - a
            print(f"\n{labels[1]} - {labels[0]} = {delta:+.2f} ms "
                  f"({delta / a * 100:+.1f}%)")
            print("A difference smaller than the spread within either arm is noise, not a result.")
            for label in labels:
                values = by_label[label]
                print(f"  {label:22s} spread {min(values):.2f} .. {max(values):.2f} ms")
        return 0

    if not args.label:
        parser.error("--label is required unless --report is given")

    with Rcon(timeout=300.0) as rcon:
        sharded, shards, throttled = server_state(rcon)
        print(f"arm '{args.label}': shardEntityStorage={sharded}, {shards} shard(s)")

        setup(rcon)
        spawn(rcon, args.mobs)
        time.sleep(3.0)

        sharded, shards, throttled = server_state(rcon)
        if throttled:
            print("\nERROR: a region is throttled, so the arms would not do equal work.\n"
                  "       Set regions.adaptiveThrottling = false and restart before measuring.")
            return 2
        print(f"under load: {shards} shard(s), no region throttled")

        print(f"\n{WARMUP_SAMPLES} warmup window(s), then {args.samples} measured")
        means = []
        for i in range(WARMUP_SAMPLES + args.samples):
            time.sleep(args.window)
            mean, p95 = tick_stats(rcon)
            warmup = i < WARMUP_SAMPLES
            print(f"    {'warmup' if warmup else 'sample'} {mean:7.2f} ms mean, {p95:7.2f} ms p95")
            if not warmup:
                means.append(mean)

        print(f"\n  median {statistics.median(means):.2f} ms over {len(means)} samples "
              f"(spread {min(means):.2f} .. {max(means):.2f})")

        rcon.command("kill @e[type=zombie]")
        rcon.command("execute in minecraft:overworld run forceload remove all")

    if args.save:
        runs = []
        if os.path.exists(args.save):
            with open(args.save, encoding="utf-8") as handle:
                runs = json.load(handle)
        runs.append({"label": args.label, "sharded": sharded, "shards": shards,
                     "mobs": args.mobs, "means": means})
        with open(args.save, "w", encoding="utf-8") as handle:
            json.dump(runs, handle, indent=2)
        print(f"appended to {args.save} ({len(runs)} run(s) recorded)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
