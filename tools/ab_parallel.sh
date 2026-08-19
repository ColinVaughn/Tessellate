#!/usr/bin/env bash
# Counterbalanced serial vs parallel region ticking with several loaded regions.
#
# The isolation benchmark cannot show a parallel win: it loads one region, so there is nothing for
# a second core to do. This loads N regions, where serial execution pays their sum and parallel
# execution pays the slowest of them.
#
# parallelTicking is read when the workers start, so each arm needs its own server.
#
# Runs serial, parallel, parallel, serial with a fresh server for every arm.
# Usage: bash tools/ab_parallel.sh [areas] [mobs-per-area]

set -u
cd "$(dirname "$0")/.."

AREAS="${1:-4}"
MOBS="${2:-1200}"
CONFIG="run/config/optimal-common.toml"
RESULTS="/tmp/optimal-parallel-ab.json"
LOG="/tmp/optimal-parallel-arm.log"

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot}"

rm -f "$RESULTS"

ORIGINAL_PARALLEL="$(sed -nE 's/^\s*parallelTicking = (true|false)/\1/p' "$CONFIG")"
ORIGINAL_ASYNC="$(sed -nE 's/^\s*asyncRegionLoops = (true|false)/\1/p' "$CONFIG")"
[[ "$ORIGINAL_PARALLEL" =~ ^(true|false)$ && "$ORIGINAL_ASYNC" =~ ^(true|false)$ ]] || {
    echo "ab_parallel: could not read the original config" >&2
    exit 1
}

set_option() {
    if grep -qE "^\s*$1 = " "$CONFIG"; then
        sed -i -E "s|^(\s*)$1 = .*|\1$1 = $2|" "$CONFIG"
    else
        echo "ab_parallel: option $1 not present in $CONFIG" >&2
        exit 1
    fi
}

cleanup() {
    bash tools/kill_server.sh >/dev/null 2>&1
    set_option parallelTicking "$ORIGINAL_PARALLEL"
    set_option asyncRegionLoops "$ORIGINAL_ASYNC"
}
trap cleanup EXIT

run_arm() {  # label parallel_value
    local label="$1" parallel="$2"
    local worker_check=()
    echo
    echo "================ arm: $label (parallelTicking=$parallel) ================"
    bash tools/kill_server.sh >/dev/null 2>&1
    set_option parallelTicking "$parallel"
    set_option asyncRegionLoops false
    set_option shardEntityStorage true
    set_option adaptiveThrottling true

    ./gradlew runServer > "$LOG" 2>&1 &
    local pid=$!
    for _ in $(seq 1 150); do
        grep -qE 'Done \([0-9.]+s\)' "$LOG" 2>/dev/null && break
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "server exited before becoming ready; see $LOG" >&2
            return 1
        fi
        sleep 2
    done
    grep -qE 'Done \([0-9.]+s\)' "$LOG" || { echo "server never became ready" >&2; return 1; }

    [[ "$parallel" == true ]] && worker_check+=(--expect-workers)
    python tools/bench_parallel.py --areas "$AREAS" --mobs "$MOBS" \
        --window 8 --warmup 2 --samples 5 --require-budget "${worker_check[@]}" \
        --label "$label" --save "$RESULTS" || return 1

    # A silent fall back to serial would make the parallel arm a second serial arm.
    echo "--- final execution mode ---"
    python tools/rcon.py "optimal regions" 2>/dev/null | sed -n '2,3p'
    grep -cE "ConcurrentModification|DEGRADED" "$LOG" | sed 's/^/crash-or-degrade lines: /'
}

run_arm serial-a   false || exit 1
run_arm parallel-a true  || exit 1
run_arm parallel-b true  || exit 1
run_arm serial-b   false || exit 1

echo
echo "================ summary ================"
python tools/bench_parallel.py --report "$RESULTS"
