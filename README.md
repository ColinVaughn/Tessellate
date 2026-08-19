# Tessellate

Tessellate is a NeoForge performance mod for Minecraft 1.21.1. It groups distant loaded areas into
independent regions and lets those regions run on different CPU cores. If one region gets too
expensive, Tessellate can slow it down without dragging the rest of the server with it.

| Situation | What Tessellate does |
| --- | --- |
| Several distant bases are busy | Runs their regions concurrently |
| One region exceeds the server budget | Reduces only that region's simulation rate |
| Two regions become close enough to interact | Merges them before they tick |
| A worker reaches unsafe shared state | Falls back to serial ticking for the session |

This works best when players or forced-loaded areas are spread out. One dense base, mob farm, or
machine cluster is still one region, so Tessellate cannot split that work across several cores. It also
doesn't replace single-thread optimizers such as [Lithium](https://modrinth.com/mod/lithium); the two
solve different problems.

## Current benchmark

For the current benchmark, we ran an ABBA sequence and restarted the server before every arm. The
test used four forced-loaded regions 2,048 blocks apart, each with exactly 1,200 persistent zombies
(4,800 total). Both modes used adaptive throttling and the same nine-mod setup. The only setting we
changed was `parallelTicking`.

```mermaid
xychart-beta
    title "Median global tick time"
    x-axis ["Serial", "Parallel"]
    y-axis "MSPT - lower is better" 0 --> 45
    bar [40.55, 15.75]
```

**Parallel ticking cut median MSPT by 61.2% and p95 MSPT by 62.5%.** All four regions held 20 TPS in
both parallel runs. In the serial runs, the adaptive budget had to slow some regions because their
work could only run one after another.

| Mode, median of two arms | Median MSPT | Median p95 | Regions at 20 TPS | Slowest region |
| --- | ---: | ---: | ---: | ---: |
| Serial regional ticking | 40.55 ms | 50.80 ms | 0/4 and 1/4 | 10.0-10.6 TPS |
| **Parallel regional ticking** | **15.75 ms** | **19.05 ms** | **4/4 in both arms** | **20.0 TPS** |

<details>
<summary>Raw arms and benchmark controls</summary>

| Arm | Median MSPT | Median p95 | Regions at 20 TPS | Slowest region |
| --- | ---: | ---: | ---: | ---: |
| Serial A | 40.1 ms | 50.8 ms | 0/4 | 10.0 TPS |
| Parallel A | 15.1 ms | 18.8 ms | 4/4 | 20.0 TPS |
| Parallel B | 16.4 ms | 19.3 ms | 4/4 | 20.0 TPS |
| Serial B | 41.0 ms | 50.8 ms | 1/4 | 10.6 TPS |

- Hardware: Intel Core Ultra 7 255H, 16 cores, Java 21.
- Runtime: Minecraft 1.21.1, NeoForge 21.1.248, Lithium 0.15.4, C2ME
  0.4.0-alpha.0.120, Mekanism and Mekanism Generators 10.7.19.85, Naturalist 2.0.3,
  Citadel 2.7.1, Friends & Foes 4.0.27, Resourceful Lib 3.0.12, and ScalableLux
  0.3.0-alpha.0.8.
- Each arm discarded two 8-second warmup windows, then used the median of five 8-second samples.
- `asyncRegionLoops=false` in both arms so `tick query` included the worker critical path. Shipping
  async mode does not join workers every tick, so its main-thread MSPT is not directly comparable.
- `ItemEntity.Age`, a vanilla field outside Tessellate, measured each region's effective TPS.
- Every arm verified exact entity count, separate regions, correct execution mode, drained boundary
  queues, and zero boundary failures. Parallel arms also required worker overlap of at least two.

Reproduce the same serial/parallel sequence with:

```bash
bash tools/ab_parallel.sh 4 1200
```

</details>

This benchmark is deliberately built around several separate regions. It is not a promise of the
same speedup on every server. One hot region still runs on one core, and different mixes of mobs,
machines, and mods will behave differently.

---

## How it works

```mermaid
flowchart LR
    L[Loaded chunks] --> M[Region map]
    M --> A[Region A worker]
    M --> B[Region B worker]
    M --> C[Region C worker]
    A --> Q[Main-thread global commits]
    B --> Q
    C --> Q
    Q --> N[Packets, lifecycle, saves]
```

Each worker handles the local simulation for its region. Anything that touches shared Minecraft or
mod state is queued and applied on the main thread once the region is safe.

### Regions

Tessellate divides the loaded world into **sections** of 4x4 chunks. Nearby entity-ticking sections are
joined into a **region**, and that whole region ticks as one unit.

With the default settings, regions merge when they come within 2 sections of each other. Two
separate regions are therefore at least 3 sections (192 blocks) apart, far enough that one cannot
reach the other during a single tick.

The map updates as chunks load and unload. Run `/tessellate regions` to see it.

### Regional TPS: slicing, not skipping

Tessellate measures the cost of each region every tick. If the server is about to miss its tick-time
target, the most expensive region gets a **tick divisor**. A divisor of 4 runs that region at 5 TPS
while unaffected regions can stay at 20.

Running the whole region once every fourth tick would produce the right average rate, but it feels
awful in play:

| | gated (all work every 4th tick) | sliced (1/4 of the work every tick) |
| --- | --- | --- |
| bystander's TPS | 19.6 | **20.0** |
| MSPT, mean | 20.5 ms | 23.7 ms |
| MSPT, **95th percentile** | **210.9 ms** | **32.9 ms** |

That gated version caused a 200 ms hitch every fourth tick, which made everyone stutter. Tessellate
instead runs a quarter of the region's entities on every tick. Each entity still averages 5 TPS,
but the work is spread out.

Entity IDs and block positions determine slice membership, so the same mob or hopper stays in the
same slice each cycle.

The throttle looks at the whole tick before deciding whether to step in. Chunk work, networking,
and block entities outside regions all consume time that regions cannot control, so the available
region budget is whatever remains under `targetTickMillis`. If the server is comfortably holding
20 TPS, nothing is throttled.

### Parallel region ticking

Separate regions cannot interact during a tick, which makes it possible to run them on different
threads. The harder part is keeping every shared boundary safe:

- **Entity storage is sharded by grid cell.** Vanilla keeps every entity section in one map plus
  one balanced tree. Sharding by *cell* rather than by region matters: a section key's coordinates
  are chunk coordinates, so the shard is a pure function of the key, and regions merging or
  splitting migrates nothing.
- **Entity lifecycle callbacks run on the main thread.** One entity crossing a chunk section
  updates six level-global containers: entity tracking, the player list, mob navigation, dragon
  parts, game-event listeners, and the mod event bus. Workers queue those and the main thread
  replays them, which replaces six concurrency problems with one rule.
- **Scheduled ticks are region-owned.** Each region has an ordinary vanilla `LevelTicks` child;
  chunk tick containers move between children at quiescent topology changes. Owner-local writes
  stay on the worker and cross-owner writes are handed off.
- **Block events are region-owned.** Each region has an ordered, deduplicating queue. Its block
  callbacks run on the owner worker; only successful event packets and positional sound/network
  effects are committed on the main thread.

Most simulation stays on the region worker. Shared work crosses one of these measured boundaries:

| boundary | region-worker work | main-thread commit or global barrier |
| --- | --- | --- |
| scheduled block/fluid ticks | drain the region-owned vanilla `LevelTicks` child | cross-owner scheduling is handed to the destination owner |
| chunk/random ticks | tick with worker-local vanilla random state | chunk/player broadcasts and packet delivery |
| entity ticks | core entity simulation | add/remove lifecycle, persistent callbacks, and entity insertion |
| teleports/dimension changes | request the transition | replay the transition after the source owner is idle |
| block-entity ticks | core block-entity simulation | ticker and fresh block-entity registration |
| block events | ordered owner callback queue | successful packets and positional sound/network effects |
| natural spawning | independent searches with one snapshot and atomic cap reservations | entity insertion and vanilla spawn-state replay |
| custom spawners/mod callbacks | none | arbitrary global mod code remains main-threaded |
| commands | none | quiesce all owners, drain handoffs, then execute the whole command |
| autosave/chunk unload | none while the owner is active | busy autosaves retry and direct saves lease the owner; optional C2ME can serialize unload snapshots on its workers |
| explicit saves and shutdown | none | quiesce and drain before taking the global snapshot or closing storage |
| topology merge/split | none while owners are active | wait affected owners, drain against old ownership, then replace the map |

`/tessellate phases` reports queued, replayed, direct, pending, elapsed main-thread time, failures,
balance, and the last source region for each boundary. The counters are bounded by boundary type;
they do not retain an ever-growing region history.

With `asyncRegionLoops=true`, a busy region keeps its ownership claim and only misses its own next
slot. Other regions and the main server tick continue instead of waiting at a global barrier.
Targeted leases protect packets, commands, chunk topology, entity loading and unloading, and saves.

Tessellate runs this ownership path without C2ME and has no build, metadata, or class dependency on it.
Vanilla prepares a safe unload snapshot on the main thread and writes it asynchronously. If C2ME is
installed, it can also run `ChunkSerializer.write` for that snapshot on its workers. Loaded
autosaves are still serialized during an idle main-thread slice; they do not wait on a busy region.

### Validation coverage

| Check | Result |
| --- | --- |
| JVM regression suite | 81 tests passed |
| Tessellate-only NeoForge GameTests | All 5 required tests passed without external mods |
| Scheduled ticks | 30 minutes, 422 rebuild cycles, worker peak 5, no failure or queued work left |
| Block events | 30 minutes, 1,001,984 callbacks, worker peak 4, packets stayed on main |
| Natural spawning | Three parallel runs, worker overlap confirmed, global and local caps held |
| Deferred writes | 6,947 level writes and 2,592 entity callbacks replayed exactly |
| Full-mod save/unload | 1,200 entities survived a 49-chunk unload/reload exactly |
| Main-thread boundaries | 8,519 deferred operations balanced with zero pending work |

These checks cover the workloads listed here, not every possible mod interaction. The serial
fallback is still necessary because no test suite can prove that arbitrary mod code is thread-safe.

---

## The trade-off

Throttling has an intentional cost: when a region exceeds its share, **everything inside it runs
slower**. Mobs move more slowly, farms produce less, and hoppers move fewer items. That is useful for
containing a lag machine, but Tessellate cannot tell a lag machine from a legitimately busy base. It
will slow either one.

If you would rather let the whole server drop below 20 TPS than slow a player's build, set
`regions.adaptiveThrottling = false`. Region tracking stays on and you keep the `/tessellate regions`
diagnostics.

---

## Configuration

`config/tessellate-common.toml`.

| option | default | what it does |
| --- | --- | --- |
| `regions.enabled` | `true` | region tracking; off makes the mod inert |
| `regions.adaptiveThrottling` | `true` | the regional TPS system |
| `regions.budgetMillis` | `25.0` | minimum budget retained for region work |
| `regions.targetTickMillis` | `45.0` | tick time to steer toward; raise to intervene later |
| `regions.maxTickDivisor` | `16` | slowest a region may be driven (1.25 TPS) |
| `regions.minThrottleMillis` | `2.0` | regions cheaper than this are never slowed |
| `regions.sectionShift` | `2` | region grid granularity, 4x4 chunks |
| `regions.parallelTicking` | `true` | tick regions on worker threads |
| `regions.directWorkerChunkReads` | `true` | resolve loaded worker chunk reads before synchronous-load instrumentation |
| `regions.parallelNaturalSpawning` | `true` | run owner-region spawn searches concurrently with level-wide cap reservations |
| `regions.shardEntityStorage` | `true` | storage isolation required by `parallelTicking` |
| `regions.asyncRegionLoops` | `true` | independent per-region loops without a per-tick barrier |
| `regions.scopedScheduledTicks` | `true` | region-own block/fluid schedulers; false uses one serial fallback child |
| `regions.scopedBlockEvents` | `true` | region-own block-event callbacks; false uses the serial fallback queue |

To return to serial regional ticking while diagnosing a mod conflict:

```toml
parallelTicking = false
asyncRegionLoops = false
```

### Commands

- `/tessellate regions`: region map, per-region cost and tick rate, execution mode
- `/tessellate phases`: worker/main calls, time, overlap, lock wait, failures and deferred queue depth
- `/tessellate violations`: thread-ownership violations recorded (should be empty)
- `/tessellate visualize`: stable region map plus GPU-rendered perimeter curtains; `T#` is the
  pooled worker that most recently ran the region

When Lithium is installed, Tessellate disables Lithium's `alloc.chunk_random` and
`entity.inactive_navigations` mixins through Lithium's supported mod override metadata. Both keep
mutable state once per level; Tessellate replaces the useful parts with worker-local random state and
an atomic path-type cache so the full chunk/path operation does not need a global monitor.

---

## Compatibility

We tested independent region loops with:

- NeoForge alone, no other mods
- Lithium 0.15.4 + Mekanism 10.7.19.85 + Mekanism Generators 10.7.19.85 + Naturalist 2.0.3 +
  Citadel 2.7.1 + Friends & Foes 4.0.27 + Resourceful Lib 3.0.12 + ScalableLux 0.3.0-alpha.0.8
- C2ME 0.4.0-alpha.0.120 with the full mod set above

Across these setups, entity queries returned byte-identical results with sharding on and off (0
differences across 25 spatial queries). Block entities and scheduled ticks behaved the same, with
no ownership violations or serial fallbacks. Vanilla and Naturalist retention probes passed, all
six tested Generators blocks created block entities, and two real clients negotiated the optional
visualizer payload while three regions ran concurrently.

Lithium needs a little special handling because its spawning optimization reads entity storage
internals directly. Tessellate's sharded storage preserves that path. Region block entities can also
register Lithium world-border listeners concurrently, so Tessellate serializes that one registration
method instead of locking whole ticks around Lithium's internal `WeakHashMap`.

---

## Limitations

Tessellate protects the shared state it knows about, but it cannot make unknown mod code thread-safe.

- **Other mods can still introduce races.** Tessellate protects four level-global containers reachable
  from entity ticks. A mod that keeps its own unsafe global state can still race.
- **C2ME 0.4.0-alpha.0.120 has one test limitation.** Full live benchmarks, save/unload, restart,
  and shutdown pass with C2ME installed. Its natural-spawning GameTest fails in both serial and
  parallel regional modes, so that specific test is not counted as passing compatibility coverage.
- Numbers above are from a synthetic benchmark on one machine. Your server is not this benchmark.

---

## Building

```
./gradlew build          # requires JDK 21
./gradlew runServer      # dev server with RCON on 25585
python tools/bench_isolation.py --mobs 6000
python tools/bench_parallel.py --areas 4 --mobs 1200
python tools/verify_blockevents.py --soak-seconds 1800
```

The benchmark scripts read the RCON password from `TESSELLATE_RCON_PASSWORD`; use the same value for
`rcon.password` in the dev server's local `server.properties`.
