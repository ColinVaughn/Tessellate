package org.texboobcat.optimal.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.RegionWorkers;

import java.util.HashSet;
import java.util.Set;

// Diagnostic: names the main-thread code that mutates entity storage while a region worker is
// running.
//
// Entity ticking on a worker reliably throws ConcurrentModificationException from
// LivingEntity.pushEntities, which iterates an EntitySection's contents. The
// iteration is the victim, so its stack trace does not name the writer. This does.
//
// It fires only while a region task is in flight and only off the worker threads, so it is
// silent in serial mode and costs an AtomicInteger read otherwise. Each distinct call site
// is reported once.
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> REPORTED = new HashSet<>();

    @Inject(method = "add", at = @At("HEAD"))
    private void optimal$reportConcurrentAdd(EntityAccess entity, CallbackInfo ci) {
        optimal$report("add");
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void optimal$reportConcurrentRemove(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        optimal$report("remove");
    }

    private static void optimal$report(String operation) {
        if (!Config.diagnoseEntitySectionRaces
            || !RegionWorkers.anyTaskInFlight()
            || RegionWorkers.isWorkerThread()) {
            return;
        }

        String site = StackWalker.getInstance()
            .walk(frames -> frames
                .filter(f -> !f.getClassName().startsWith("org.texboobcat.optimal"))
                .filter(f -> !f.getClassName().equals(EntitySection.class.getName()))
                .limit(8)
                .map(f -> "    " + f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                .reduce("", (a, b) -> a + "\n" + b));

        synchronized (REPORTED) {
            if (!REPORTED.add(operation + site)) {
                return;
            }
        }
        LOGGER.error("optimal DIAGNOSTIC: thread '{}' called EntitySection.{} while {} region "
                + "task(s) were in flight. This is the race that blocks async region ticking:{}",
            Thread.currentThread().getName(), operation, RegionWorkers.activeTasks(), site);
    }
}
