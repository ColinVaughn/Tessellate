package org.texboobcat.optimal.guard;

import org.texboobcat.optimal.region.Region;

import javax.annotation.Nullable;

// Which region, if any, the current thread is ticking.
//
// A null current region means the main thread, which is allowed to touch anything. This is the
// single source of truth used by the ownership guard during parallel execution.
public final class RegionThreadContext {

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private RegionThreadContext() {
    }

    public record Binding(Region region, String levelKey) {
    }

    public static void enter(Region region, String levelKey) {
        CURRENT.set(new Binding(region, levelKey));
    }

    public static void exit() {
        CURRENT.remove();
    }

    public static void clear() {
        CURRENT.remove();
    }

    @Nullable
    public static Binding currentBinding() {
        return CURRENT.get();
    }

    @Nullable
    public static Region current() {
        Binding binding = CURRENT.get();
        return binding == null ? null : binding.region();
    }

    public static boolean onMainThread() {
        return CURRENT.get() == null;
    }
}
