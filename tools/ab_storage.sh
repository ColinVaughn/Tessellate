#!/usr/bin/env bash
# Counterbalanced A/B of sharded vs vanilla entity storage.
#
# The option is read when the level is built, so each arm needs its own server. That makes arm
# order a confound: a straight sharded-then-vanilla comparison measures JIT warmup as much as it
# measures the change, which on this project once turned pure noise into an apparent 2.5 ms
# regression. The arms therefore run in the order sharded, vanilla, vanilla, sharded.
#
# Adaptive throttling is disabled for the duration so both arms do identical work. It is restored
# afterwards.
#
# Usage: bash tools/ab_storage.sh [mobs] [samples]

set -u
cd "$(dirname "$0")/.."

MOBS="${1:-1500}"
SAMPLES="${2:-8}"
CONFIG="run/config/tessellate-common.toml"
RESULTS="/tmp/tessellate-storage-ab.json"
LOG="/tmp/tessellate-ab-arm.log"

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot}"

rm -f "$RESULTS"

set_option() {  # name value
    if grep -qE "^\s*$1 = " "$CONFIG"; then
        sed -i -E "s|^(\s*)$1 = .*|\1$1 = $2|" "$CONFIG"
    else
        echo "ab_storage: option $1 not present in $CONFIG" >&2
        exit 1
    fi
}

restore_throttling() {
    set_option adaptiveThrottling true
    bash tools/kill_server.sh >/dev/null 2>&1
}
trap restore_throttling EXIT

run_arm() {  # label shard_value
    local label="$1" shard="$2"
    echo
    echo "================ arm: $label (shardEntityStorage=$shard) ================"
    bash tools/kill_server.sh >/dev/null 2>&1
    set_option shardEntityStorage "$shard"
    set_option adaptiveThrottling false

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

    python tools/bench_storage.py --label "$label" --mobs "$MOBS" \
        --samples "$SAMPLES" --save "$RESULTS" || return 1
}

# Alternating order, so warmup drift cancels instead of loading onto one arm.
run_arm sharded true   || exit 1
run_arm vanilla false  || exit 1
run_arm vanilla false  || exit 1
run_arm sharded true   || exit 1

echo
echo "================ summary ================"
python tools/bench_storage.py --report "$RESULTS"
