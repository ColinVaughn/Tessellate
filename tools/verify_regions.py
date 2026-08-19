#!/usr/bin/env python3
"""Exercise live region creation, merge, and split behavior.

The test uses block-coordinate `/forceload` boxes, waits for chunk counts to settle, and treats
spawn tickets as part of the baseline.

Run the server (`./gradlew runServer`), then:
    python tools/verify_regions.py
"""

import sys
import time

from rcon import Rcon
from regions import parse

# Block-coordinate boxes, all placed well clear of world spawn.
# Area A: chunks (32..39, 32..39)  -> sections (8..9, 8..9) at shift 2
AREA_A = (512, 512, 639, 639)
AREA_A_CHUNKS = 64
# Area C: chunks (96..103, 32..39) -> sections (24..25, 8..9); 15 sections clear of A
AREA_C = (1536, 512, 1663, 639)
AREA_C_CHUNKS = 64
# Bridge: chunks (40..95, 32..35)  -> sections (10..23, 8); 224 chunks, inside the 256 limit
BRIDGE = (640, 512, 1535, 575)
BRIDGE_CHUNKS = 224

FAILURES = []


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {label}" + (f"  ({detail})" if detail else ""))
    if not condition:
        FAILURES.append(label)
    return bool(condition)


def overworld_from(text):
    return parse(text)["minecraft:overworld"]


def wait_stable(rcon, timeout=180.0, settle_reads=3):
    """Poll until the overworld ticking-chunk count stops changing."""
    deadline = time.time() + timeout
    last = None
    stable = 0
    out = ""
    while time.time() < deadline:
        out = rcon.command("optimal regions")
        state = overworld_from(out)
        if state.chunks == last:
            stable += 1
            if stable >= settle_reads:
                return out
        else:
            stable = 0
            last = state.chunks
        time.sleep(0.5)
    print(f"    WARNING: chunk count never settled within {timeout:.0f}s")
    return out


def forceload(rcon, box, add=True):
    verb = "add" if add else "remove"
    x1, z1, x2, z2 = box
    return rcon.command(f"execute in minecraft:overworld run forceload {verb} {x1} {z1} {x2} {z2}")


def show(out):
    state = overworld_from(out)
    print(f"    {state.line}")
    for line in out.splitlines():
        if "region#" in line:
            print(f"    {line.strip()}")


def main():
    with Rcon(timeout=60.0) as rcon:
        print("optimal region verification\n")

        print("step 0: baseline, nothing forceloaded")
        rcon.command("execute in minecraft:overworld run forceload remove all")
        rcon.command("optimal violations clear")
        out = wait_stable(rcon)
        base = overworld_from(out)
        print(f"    spawn-chunk baseline: {base.chunks} ticking chunk(s), "
              f"{base.region_count} region(s)")
        check("baseline is stable and small", base.chunks < 64,
              f"{base.chunks} chunk(s)")
        base_merges, base_splits = base.merges, base.splits

        print("\nstep 1: two separated areas")
        forceload(rcon, AREA_A)
        forceload(rcon, AREA_C)
        out = wait_stable(rcon)
        state = overworld_from(out)
        expected = base.chunks + AREA_A_CHUNKS + AREA_C_CHUNKS
        check("forceload produced the intended chunk count", state.chunks == expected,
              f"expected {expected}, got {state.chunks}")
        check("two separated areas make two distinct regions",
              state.region_count == base.region_count + 2,
              f"expected {base.region_count + 2}, got {state.region_count}")
        show(out)

        print("\nstep 2: bridge the gap")
        forceload(rcon, BRIDGE)
        out = wait_stable(rcon)
        state = overworld_from(out)
        expected = base.chunks + AREA_A_CHUNKS + AREA_C_CHUNKS + BRIDGE_CHUNKS
        check("bridge produced the intended chunk count", state.chunks == expected,
              f"expected {expected}, got {state.chunks}")
        check("a continuous bridge merges them into one region",
              state.region_count == base.region_count + 1,
              f"expected {base.region_count + 1}, got {state.region_count}")
        check("the merge was recorded", state.merges > base_merges,
              f"merges {base_merges} -> {state.merges}")
        show(out)

        print("\nstep 3: remove the bridge")
        forceload(rcon, BRIDGE, add=False)
        out = wait_stable(rcon)
        state = overworld_from(out)
        check("removing the bridge splits them again",
              state.region_count == base.region_count + 2,
              f"expected {base.region_count + 2}, got {state.region_count}")
        check("the split was recorded", state.splits > base_splits,
              f"splits {base_splits} -> {state.splits}")
        show(out)

        print("\nstep 4: ownership guard")
        violations = rcon.command("optimal violations")
        check("zero ownership violations", "no ownership violations" in violations,
              violations.strip().splitlines()[0] if violations.strip() else "empty")

        print("\nstep 5: incremental tracking and overhead")
        state = overworld_from(rcon.command("optimal regions"))
        print(f"    {state.chunks} ticking chunks")
        print(f"    steady-state per-tick update: {state.update_ms:.3f} ms "
              f"({100 * state.update_ms / 50.0:.3f}% of the 50 ms budget)")
        print(f"    periodic verify rescan:      {state.verify_ms:.3f} ms "
              f"(peak {state.peak_ms:.3f} ms)")
        check("per-tick cost is negligible", state.update_ms < 0.1,
              f"{state.update_ms:.3f} ms")
        # Drift means an entity-ticking transition was missed.
        check("incremental tracking never drifted from the chunk map", state.drift == 0,
              f"drift={state.drift}")

        print("\ncleaning up")
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
