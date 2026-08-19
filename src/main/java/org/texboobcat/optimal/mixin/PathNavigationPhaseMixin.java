package org.texboobcat.optimal.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.texboobcat.optimal.region.PhaseStats;

import java.util.Set;

// Measures the one method through which every PathNavigation path search is executed.
@Mixin(PathNavigation.class)
public abstract class PathNavigationPhaseMixin {

    @WrapMethod(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;")
    private Path optimal$measurePathSearch(Set<BlockPos> targets, int regionOffset,
                                           boolean offsetUpward, int accuracy, float followRange,
                                           Operation<Path> original) {
        long start = PhaseStats.begin(PhaseStats.Phase.PATHFINDING);
        try {
            return original.call(targets, regionOffset, offsetUpward, accuracy, followRange);
        } catch (Throwable failure) {
            PhaseStats.failed(PhaseStats.Phase.PATHFINDING);
            throw failure;
        } finally {
            PhaseStats.end(PhaseStats.Phase.PATHFINDING, start);
        }
    }
}
