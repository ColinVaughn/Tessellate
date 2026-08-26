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
- Treat frames in delegating transformers, mixin adapters, event buses, and compatibility wrappers
  with extra caution: they may only be forwarding a failure from the target mod. If the report omits
  the nested cause, keep attribution provisional until a reproduction or full log identifies it.
- `Affected entity type` or `Affected block entity type` identifies the narrowest candidate tick.
- `Component` distinguishes region ticking from natural spawning.
- `Reason code` commonly identifies `scheduled_tick_cross_region`, `worker_exception`, or
  `unknown_safety_check`; the human-readable reason and exception still decide the fix.
- Minecraft, loader, Tessellate, and mod versions define the compatibility claim. Do not generalize
  a result to untested versions.

If the report has no useful frame or type ID, reproduce with diagnostics enabled and capture the
first failure. Do not guess from the installed-mod list.

Inspect Tessellate's source when the report, ownership rule, fallback behavior, or API contract is
unclear. Reuse an existing local checkout first. Otherwise, when network access is authorized, clone
https://github.com/ColinVaughn/Tessellate and check out the tag or commit matching the reported
version. Do not reason from the latest branch when the server runs an older release. Trace both the
code that detects the failure and the scheduling or ownership path around it before deciding whether
the bug belongs to Tessellate, the target mod, or their interaction.

## Attribute failures across mod boundaries

Separate the **trigger owner** from the **unsafe-state owner**. A block entity registered by one mod
may call a recipe, capability, library, or compatibility API implemented by another mod. The
affected type identifies what exposed the race; it does not necessarily identify where to patch it.

- Follow the stack through every mod boundary until reaching the mutable state or world access that
  violates the invariant. Patch that owner when possible, not each caller that happens to trigger it.
- Inspect the exact installed jars when source tags, published sources, and binaries may differ. Use
  `jar tf` to locate classes and `javap -c -p -classpath <jars> <class>` to confirm static fields,
  initializers, and call sites before designing a bytecode patch.
- Reproduce with the complete dependency combination. Testing the registered block's mod without
  the companion mod that supplies its recipe or API does not exercise the reported path.
- Scope the compatibility claim to the tested Minecraft, loader, trigger-mod, and dependency
  versions. A bridge for one abandoned binary is not general support for every release of that mod.

A common cross-boundary failure is a registered type in one mod calling process-wide mutable state
owned by a dependency. The root fix belongs at that state owner: replace the unsafe implementation,
confine it to an owner or instance, or protect its complete invariant. Do not serialize every caller
or duplicate the same guard across each triggering type.

## Find the violated ownership rule

Trace the failing state from the reported frame to its writers and readers. Pay particular attention
to mutable static fields, singleton managers, per-level data stored globally, caches with mutable
values, iteration concurrent with mutation, read/modify/write sequences, callbacks into world state,
and access to a position owned by another region.

Classify the smallest unsafe operation and its phase in any framework-owned transaction. Thread
ownership and transaction ordering are separate invariants; moving work to the correct thread does
not repair an operation that runs before its reservation, validation, or commit phase. Then use the
first matching fix:

1. **Framework-owned pipeline state:** preserve the pipeline's candidate, reservation, commit, and
   callback ordering. Return or substitute data before the pipeline reserves it instead of creating
   a side object, committing it directly, and cancelling the original operation.
2. **Mod-owned shared state:** give it clear ownership or protect the complete invariant with an
   atomic operation, a short lock, immutable snapshots, thread-confined context, or a
   per-level/per-region data structure.
3. **World state at a known position:** dispatch the operation to its owner with
   `TessellateApi.executeOnRegion(level, pos, work)`.
4. **A genuinely main-thread-only API:** queue only that operation with
   `TessellateApi.executeOnMainThread(work)`.
5. **Main-thread work that touches a known position:** use
   `TessellateApi.executeOnMainThread(level, pos, work)` so Tessellate leases the owner first.
6. **An entire tick that cannot be isolated safely:** register only that entity or block-entity type
   during mod initialization with `registerMainThreadEntity(type)` or
   `registerMainThreadBlockEntity(type)`.

Import these hooks from `org.texboobcat.tessellate.api.TessellateApi`. Confirm the signatures in the
Tessellate version being targeted before editing the mod.

## Bridge an optional or unmaintained dependency safely

Prefer fixing and releasing the mod that owns the unsafe state. When that version is abandoned and
the maintained distribution is a controlled fork of a dependency, a narrow optional compatibility
bridge can be reasonable:

- Target the exact third-party owner and intercept the smallest state-construction, mutation, or
  access point that repairs the invariant. Do not wrap unrelated operations or whole ticks.
- When using a Mixin, keep the target optional with the framework's pseudo/soft-target mechanism and
  use the correct remapping settings for third-party names. Verify startup both with and without the
  target mod; optionality inferred from annotations alone is not a runtime test.
- Require the expected injection when the target class exists. If a later addon version changes the
  bytecode, failing at startup is safer than silently claiming compatibility while leaving the race
  active. Version-gate the bridge when multiple incompatible layouts are in circulation.
- Confirm from the runtime Mixin audit or debug log that the compatibility Mixin applied to the
  intended class. A successful compile only proves that the patch class itself compiled.
- Keep the bridge in a dedicated compatibility package and document the external class and versions
  it repairs. Remove it when the owning addon ships an equivalent fix.

Do not copy an abandoned addon's implementation into the maintained mod, add a hard dependency just
for the patch, or claim the addon is compatible merely because the server starts.

## Preserve framework-owned transactions

Trace the whole vanilla and loader pipeline, including other mixins around the same invocation.
For parallel natural spawning, the relevant lifecycle is candidate creation and positioning,
validation, Tessellate cap reservation, `finalizeSpawn`, entity insertion, and
`SpawnState.afterSpawn`. The same mob instance at the same position must flow from reservation
through `afterSpawn`; adding an entity before a reservation or replacing or moving it afterward is
a transaction violation even when the current worker owns the position.

For a mod that replaces a natural-spawn candidate:

- Construct and copy the replacement after the original candidate is positioned but before
  `isValidPositionForMob` and Tessellate's reservation wrapper. On Fabric, a stable pattern is an
  injection after the version's `Mob.moveTo` or `Mob.snapTo` call that updates the method's local
  `Mob` reference, such as a MixinExtras `LocalRef<Mob>`.
- Let vanilla continue with the replacement so validation, reservation, finalization, insertion,
  mob-cap accounting, and the after-spawn callback all observe that same object. Do not call
  `addFreshEntity` from the pre-validation replacement hook and then cancel the original candidate.
- If common event code currently constructs, finalizes, and inserts the replacement, separate
  construction from commit. Let the loader hook capture and return the candidate while non-pipeline
  callers retain their existing commit behavior.
- Do not rely on mixin priority around another wrapper of `isValidPositionForMob`. An inner wrapper
  can change the target method's local while an outer wrapper still holds the old argument. Change
  the local before invocation arguments are loaded so every wrapper sees the replacement.

`executeOnRegion` does not repair this failure: an owner-local call runs inline at the same invalid
transaction phase. `executeOnMainThread` only moves the invalid direct insertion and needlessly
serializes the mod's decision. Tessellate may still defer the final level-global entity insertion to
the main thread to protect entity indexes and lifecycle callbacks; actual compatibility means the
candidate selection, preparation, validation, and regional work stay parallel while the normal
pipeline owns that narrow global commit.

## Keep lazy class loading and transformers parallel

A region worker can be the first thread to touch a class, so class definition, Mixin application,
and custom ASM transforms may run during a parallel tick. Make transformer-owned state safe for
concurrent and reentrant calls; do not redirect class loading or transformation to the server main
thread, and never block a region worker waiting for such a redirect.

- Separate immutable setup state from per-transformation state. Freeze discovered configurations
  and patch lists after initialization. Keep the target `ClassNode`, readers, writers, visitors, and
  other scratch state local to one invocation.
- Replace shared context such as `currentConfig`, `currentTarget`, or `currentClass` with a
  `ThreadLocal` or scoped-value equivalent when nested helpers need access. Transformations can
  recursively trigger more transformations on the same thread, so save the previous binding,
  restore it in `finally`, and remove the outermost binding to prevent leaks. A simple set/remove
  pair is not reentrant-safe.
- Use concurrent caches with immutable values and atomic publication. For a recursive class graph,
  cache immutable direct superclass/interface edges with `putIfAbsent` or `computeIfAbsent`, then
  derive the transitive result without publishing a partially built mutable set.
- Avoid `Class.forName`, reflective loading, or class initialization while holding transformer or
  Mixin locks; cross-thread classloader lock cycles can deadlock. Prefer the loader's bytecode
  provider when inspecting an unloaded superclass or interface. Preload a known class only during a
  loader phase where doing so cannot bypass its Mixin or ASM transformation.
- Check how transformer instances are created. `Unsafe.allocateInstance`, deserialization, and
  similar mechanisms bypass constructors and instance field initializers. State needed by those
  instances must be initialized by a guaranteed lifecycle hook or, when it is process-wide by
  design, by normal static initialization.
- Do not add a global transformer lock merely because a race exists in owned state. A narrow
  framework-required transformation lock can serialize one-time class definition without moving
  ticks to the main thread, but it is a last resort because recursive loads can deadlock. Prefer
  thread confinement and atomic caches first.

For transformer-owned state, prefer reentrant thread confinement for invocation context and
concurrent caches with immutable, atomically published values. This keeps the triggering region tick
on its worker without moving class transformation or the affected type's whole tick to the main
thread.

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

For a transformer fix, overlap transforms for different targets/configurations and force one nested
transform. Assert that each call observes its own context, that the outer context is restored after
the nested call, and that cache entries are complete. Instantiate the transformer through its real
production path so tests catch constructor-bypassing allocation.

Then validate in proportion to the affected subsystem:

- Run the mod's unit tests and loader-specific build.
- Generate or preprocess the exact Minecraft version and inspect the transformed target or mixin
  audit. Compilation alone does not prove that an injection matched the intended instruction or ran
  before competing wrappers loaded their arguments.
- First reproduce the original failure with the unpatched artifact under the same fixture. Without a
  pre-fix failure, a clean post-fix run may only prove that the test never reached the unsafe path.
- Reproduce with Tessellate's parallel region ticking and at least two affected instances in
  independent regions. Confirm the original behavior still works and no serial fallback or ownership
  violation appears.
- Drive enough real work to cross the failing call repeatedly: populate every relevant process or
  input lane, supply required energy or resources, and assert actual outputs or state changes. Also
  verify that Tessellate still reports independent region workers rather than a degraded serial mode.
- Scan the post-fix log for the original exception, suspected frame, ownership violation, worker
  failure, compatibility candidate, and serial-fallback markers. Record zero matches alongside the
  positive behavior assertion.
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
