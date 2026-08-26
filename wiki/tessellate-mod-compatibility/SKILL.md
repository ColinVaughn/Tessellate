---
name: tessellate-mod-compatibility
description: Diagnose and fix Minecraft mod incompatibilities exposed by Tessellate region-thread execution. Use when a copied Tessellate report, serial fallback, ownership failure, or affected entity or block-entity ID points to mod code; preserve server-only operation and prefer root-cause concurrency fixes over broad main-thread or serial fallbacks.
---

# Make a mod compatible with Tessellate

Produce a source-level fix that keeps independent regions parallel. Treat Tessellate's fallback as
evidence and a safety net, not the compatibility solution.

## Start from the report

Collect the copied compatibility report, the target mod source and exact version, loader, Minecraft
version, and a reliable reproduction. Use these report fields to narrow the investigation:

- `Suspected mod` and `Suspected frame` are heuristic leads, not proof. Follow the full cause chain
  and inspect every caller of the failing method before assigning ownership.
- `Affected entity type` or `Affected block entity type` identifies the narrowest candidate tick.
- `Component` distinguishes region ticking from natural spawning.
- `Reason code` commonly identifies `scheduled_tick_cross_region`, `worker_exception`, or
  `unknown_safety_check`; the human-readable reason and exception still decide the fix.
- Minecraft, loader, Tessellate, and mod versions define the compatibility claim. Do not generalize
  a result to untested versions.

If the report has no useful frame or type ID, reproduce with diagnostics enabled and capture the
first failure. Do not guess from the installed-mod list.

## Find the violated ownership rule

Trace the failing state from the reported frame to its writers and readers. Pay particular attention
to mutable static fields, singleton managers, per-level data stored globally, caches with mutable
values, iteration concurrent with mutation, read/modify/write sequences, callbacks into world state,
and access to a position owned by another region.

Classify the smallest unsafe operation, then use the first matching fix:

1. **Mod-owned shared state:** give it clear ownership or protect the complete invariant with an
   atomic operation, a short lock, immutable snapshots, or a per-level/per-region data structure.
2. **World state at a known position:** dispatch the operation to its owner with
   `TessellateApi.executeOnRegion(level, pos, work)`.
3. **A genuinely main-thread-only API:** queue only that operation with
   `TessellateApi.executeOnMainThread(work)`.
4. **Main-thread work that touches a known position:** use
   `TessellateApi.executeOnMainThread(level, pos, work)` so Tessellate leases the owner first.
5. **An entire tick that cannot be isolated safely:** register only that entity or block-entity type
   during mod initialization with `registerMainThreadEntity(type)` or
   `registerMainThreadBlockEntity(type)`.

Import these hooks from `org.texboobcat.tessellate.api.TessellateApi`. Confirm the signatures in the
Tessellate version being targeted before editing the mod.

## Concurrency rules that change the fix

- `ConcurrentHashMap` makes individual map operations safe; it does not make mutable values,
  iteration plus mutation, multi-map changes, or a separate get/modify/put sequence atomic. Prefer
  `compute`, `merge`, `putIfAbsent`, or `remove(key, value)` only when the full invariant fits that
  single operation. Otherwise protect the whole invariant or redesign its ownership.
- Do not hold a mod lock while calling world code, another region, or arbitrary callbacks. Keep the
  protected state transition small.
- `executeOnRegion` runs inline only when the current region owns the position. Otherwise it queues
  the work and returns, so callers cannot synchronously consume its result.
- `executeOnMainThread` always queues and returns before the work runs. Never block a region worker
  on that work with `join`, `get`, a latch, or a spin loop.
- Whole-type main-thread registration is process-wide and idempotent, but replaying a whole tick
  waits for active regions because the tick's interaction range is unknown. It is a last resort.
- Do not hide a fixable bug with `compatibility.forceSerialMods`, a global spawning override, or a
  whole-session serial-region rule. Those are temporary maintainer safety controls.

Keep Tessellate optional unless the mod intentionally requires it. Isolate Tessellate imports in a
loader-gated compatibility class, use the project's compile-only equivalent where available, and
verify that the mod still launches without Tessellate. Compatibility code belongs on the logical
server; do not introduce a required client mod or client-only class reference.

## Implement and prove the fix

Add one deterministic concurrency test near the changed state. Use barriers such as
`CountDownLatch` to force overlapping operations; do not rely on sleeps or a test that can pass
without overlap.

Then validate in proportion to the affected subsystem:

- Run the mod's unit tests and loader-specific build.
- Reproduce with Tessellate's parallel region ticking and at least two affected instances in
  independent regions. Confirm the original behavior still works and no serial fallback or ownership
  violation appears.
- Repeat with serial region ticking as the behavioral baseline.
- Exercise save, unload/reload, and restart when the changed state persists or is level-scoped.
- Exercise natural spawning when the report component is `natural-spawning`.
- Test every loader the mod claims to support.
- Launch a dedicated server without the client mod. If the integration is optional, also launch
  without Tessellate.

Do not claim official compatibility from compilation alone. If a full reproduction cannot run,
state exactly what was verified and leave the result as a proposed fix.

## Handoff

Report:

- the root cause and violated ownership invariant;
- the reported frame and affected type ID;
- the smallest synchronization or Tessellate boundary chosen, and why broader fallbacks were not
  needed;
- the tests and runtime matrix completed;
- any remaining temporary fallback and the condition for removing it.

If the evidence points to Tessellate rather than the target mod, reduce it to a minimal reproduction
and report it at https://github.com/ColinVaughn/Tessellate or discuss it at
https://discord.gg/dPY6zmHtr5. Do not open an issue, push a branch, or publish a compatibility claim
unless the user asks.
