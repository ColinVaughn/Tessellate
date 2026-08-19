#!/usr/bin/env bash
# The numbers that go on a public page: with the mod off, at its shipping defaults, and with
# parallel ticking opted into.
#
# Three arms rather than two, because the shipping default is not the parallel configuration and
# quoting parallel figures as if users get them out of the box would be inaccurate.
#
#   off       regions.enabled = false                     -> what the server does without this mod
#   default   throttling on, parallel off, sharding off   -> what users actually get
#   parallel  parallelTicking on, asyncRegionLoops off    -> opt-in, experimental
#
# Two scenarios, because they answer different questions:
#
#   isolation   one lag machine, one distant observer  -> does one player's mess reach everyone?
#   throughput  four separately loaded regions         -> how much total load before anyone slows?
#
# Usage: bash tools/ab_headline.sh [lag-machine-mobs] [mobs-per-region]

set -u
cd "$(dirname "$0")/.."

LAG_MOBS="${1:-3000}"
REGION_MOBS="${2:-1200}"
CONFIG="run/config/optimal-common.toml"
LOG="/tmp/optimal-headline-arm.log"
OUT="/tmp/optimal-headline.txt"

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot}"
: > "$OUT"

set_option() {
    if grep -qE "^\s*$1 = " "$CONFIG"; then
        sed -i -E "s|^(\s*)$1 = .*|\1$1 = $2|" "$CONFIG"
    else
        echo "ab_headline: option $1 not present in $CONFIG" >&2
        exit 1
    fi
}

cleanup() { bash tools/kill_server.sh >/dev/null 2>&1; }
trap cleanup EXIT

start_server() {
    bash tools/kill_server.sh >/dev/null 2>&1
    ./gradlew runServer > "$LOG" 2>&1 &
    local pid=$!
    for _ in $(seq 1 150); do
        grep -qE 'Done \([0-9.]+s\)' "$LOG" 2>/dev/null && return 0
        kill -0 "$pid" 2>/dev/null || { echo "server exited; see $LOG" >&2; return 1; }
        sleep 2
    done
    echo "server never became ready" >&2
    return 1
}

run_arm() {  # label enabled parallel shard
    local label="$1"
    echo
    echo "================ arm: $label ================"
    set_option enabled "$2"
    set_option parallelTicking "$3"
    set_option shardEntityStorage "$4"
    set_option asyncRegionLoops false
    set_option adaptiveThrottling true
    set_option assertShardOwnership false
    start_server || return 1

    echo "--- isolation: $LAG_MOBS mobs in one region, observer 1024 blocks away ---"
    local iso
    iso=$(python tools/bench_isolation.py --mobs "$LAG_MOBS" --window 12 2>&1 \
        | grep -E "area A \(observer\)|area C \(lag machine\)|global MSPT:")
    echo "$iso"
    echo "[$label] ISOLATION" >> "$OUT"; echo "$iso" >> "$OUT"

    echo "--- throughput: 4 regions x $REGION_MOBS mobs ---"
    local thr
    thr=$(python tools/bench_parallel.py --areas 4 --mobs "$REGION_MOBS" --window 12 2>&1 \
        | grep -E "areas still at 20 TPS|slowest area|global MSPT:")
    echo "$thr"
    echo "[$label] THROUGHPUT" >> "$OUT"; echo "$thr" >> "$OUT"
}

run_arm off      false false false || exit 1
run_arm default  true  false false || exit 1
run_arm parallel true  true  true  || exit 1

echo
echo "================ collected ================"
cat "$OUT"
