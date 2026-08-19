#!/usr/bin/env python3
"""Compare region-scoped entity ticking with the vanilla path.

The test alternates both modes against the same loaded world to limit JIT and machine-state drift.
It confirms each config reload through `/tessellate regions` before collecting a sample.

Usage:
    python tools/bench_ab.py [--mobs 2500] [--window 10] [--reps 3]
"""

import argparse
import random
import re
import statistics
import sys
import time
from pathlib import Path

from rcon import Rcon, RconError
from regions import overworld

CONFIG = Path(__file__).resolve().parent.parent / "run" / "config" / "tessellate-common.toml"

AREA_A = (512, 512, 639, 639)
AREA_C = (1536, 512, 1663, 639)
A_CENTER = (575, 575)
C_CENTER = (1599, 575)
PEN_HALF = 16
FLOOR_Y = 3
SPAWN_Y = 5

FAILURES = []


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {label}" + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def set_scoped(rcon, enabled):
    """Set regions.scopedEntityTicking and wait for NeoForge to reload it."""
    text = CONFIG.read_text(encoding="utf-8")
    new = re.sub(r"scopedEntityTicking = (true|false)",
                 f"scopedEntityTicking = {str(enabled).lower()}", text)
    if new == text and f"scopedEntityTicking = {str(enabled).lower()}" not in text:
        raise RconError("could not find scopedEntityTicking in the config file")
    CONFIG.write_text(new, encoding="utf-8")

    # Confirm the live value instead of assuming the file watcher has run.
    deadline = time.time() + 45.0
    while time.time() < deadline:
        time.sleep(2.0)
        if read_scoped(rcon) is enabled:
            return True
    return False


def read_scoped(rcon):
    """Return the live regions.scopedEntityTicking value."""
    return overworld(rcon).entity_scoped


def tick_mean(rcon):
    out = rcon.command("tick query")
    m = re.search(r"Average time per tick: ([\d.]+)ms", out)
    if not m:
        raise RconError(f"could not parse tick query: {out}")
    return float(m.group(1))


def setup(rcon, mobs):
    print("preparing the world")
    for cmd in [
        "gamerule doMobSpawning false",
        "gamerule doDaylightCycle false",
        "gamerule doWeatherCycle false",
        "gamerule doFireTick false",
        "gamerule mobGriefing false",
        "gamerule randomTickSpeed 0",
        "time set midnight",
        "execute in minecraft:overworld run forceload remove all",
        "kill @e[type=zombie]",
        "kill @e[type=item]",
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

    print(f"spawning {mobs} zombies into a pen in area C")
    rng = random.Random(20260818)
    for i in range(mobs):
        x = rng.randint(C_CENTER[0] - PEN_HALF, C_CENTER[0] + PEN_HALF)
        z = rng.randint(C_CENTER[1] - PEN_HALF, C_CENTER[1] + PEN_HALF)
        rcon.command(
            f"execute in minecraft:overworld run summon minecraft:zombie {x} {SPAWN_Y} {z} "
            "{PersistenceRequired:1b}")
        if (i + 1) % 500 == 0:
            print(f"    {i + 1}/{mobs}")
    time.sleep(3.0)

    rcon.command("scoreboard objectives add tessellatecount dummy")
    rcon.command("execute in minecraft:overworld store result score #count tessellatecount "
                 "if entity @e[type=minecraft:zombie]")
    out = rcon.command("scoreboard players get #count tessellatecount")
    m = re.search(r"has (-?\d+) ", out)
    found = int(m.group(1)) if m else -1
    check("the load is actually populated", found == mobs, f"expected {mobs}, found {found}")


def measure(rcon, window):
    time.sleep(2.0)
    t0 = time.time()
    time.sleep(window)
    return tick_mean(rcon), time.time() - t0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mobs", type=int, default=2500)
    parser.add_argument("--window", type=float, default=10.0)
    parser.add_argument("--reps", type=int, default=4)
    parser.add_argument("--warmup", type=int, default=3,
                        help="measurement windows to discard before the real reps")
    parser.add_argument("--skip-setup", action="store_true")
    args = parser.parse_args()

    with Rcon(timeout=180.0) as rcon:
        print("tessellate A/B: region-scoped vs vanilla entity ticking\n")
        if not args.skip_setup:
            setup(rcon, args.mobs)

        # Discard warmup windows while the JIT settles.
        print(f"\nwarmup ({args.warmup} discarded window(s))")
        set_scoped(rcon, True)
        for _ in range(args.warmup):
            mspt, _ = measure(rcon, args.window)
            print(f"    discard : {mspt:6.2f} ms")

        # Alternate the first arm so warmup drift does not favor one mode.
        scoped, vanilla = [], []
        for rep in range(args.reps):
            scoped_first = rep % 2 == 0
            order = ["scoped", "vanilla"] if scoped_first else ["vanilla", "scoped"]
            print(f"\nrep {rep + 1}/{args.reps} (order: {order[0]} then {order[1]})")

            for arm in order:
                enabled = arm == "scoped"
                ok = set_scoped(rcon, enabled)
                check(f"rep {rep + 1}: {arm} arm really is {arm}", ok,
                      "config flip not observed")
                mspt, _ = measure(rcon, args.window)
                (scoped if enabled else vanilla).append(mspt)
                print(f"    {arm:8s}: {mspt:6.2f} ms")

        set_scoped(rcon, True)

        print("\n" + "=" * 68)
        print("MSPT: region-scoped vs vanilla entity ticking")
        print(f"  scoped   {[f'{v:.2f}' for v in scoped]}  mean {statistics.mean(scoped):.2f} ms")
        print(f"  vanilla  {[f'{v:.2f}' for v in vanilla]}  mean {statistics.mean(vanilla):.2f} ms")

        s_mean, v_mean = statistics.mean(scoped), statistics.mean(vanilla)
        delta = s_mean - v_mean
        pct = 100.0 * delta / v_mean if v_mean else 0.0
        print(f"  delta    {delta:+.2f} ms ({pct:+.1f}%)")

        pairs = [s - v for s, v in zip(scoped, vanilla)]
        print(f"  paired   {[f'{d:+.2f}' for d in pairs]}")
        consistent = all(d > 0 for d in pairs) or all(d < 0 for d in pairs)

        overlap = min(scoped) <= max(vanilla) and min(vanilla) <= max(scoped)
        print(f"  per-rep ranges overlap: {'yes' if overlap else 'no'}")
        print(f"  paired deltas share a sign: {'yes' if consistent else 'no'}")

        if not consistent:
            print("  -> the difference does not survive counterbalancing; no measurable cost,")
            print("     expected while execution is sequential")
        elif delta > 0:
            print(f"  -> a consistent +{delta:.2f} ms cost. Small and sequential-only, but real;")
            print("     record it rather than calling it noise")

        # Use within-arm spread as a simple noise floor, not a significance test.
        spread = max(max(scoped) - min(scoped), max(vanilla) - min(vanilla))
        print(f"  worst within-arm spread: {spread:.2f} ms "
              f"-- no effect smaller than this is detectable here")

        check("region-scoped ticking costs no more than ~1 ms at this load",
              not consistent or delta <= 1.0,
              f"{delta:+.2f} ms, "
              + ("consistently signed" if consistent else "mixed signs across pairs"))

        print("\ncleaning up")
        rcon.command("kill @e[type=zombie]")
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
