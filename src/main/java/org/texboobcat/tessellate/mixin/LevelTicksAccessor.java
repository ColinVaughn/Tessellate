package org.texboobcat.tessellate.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Queue;

@Mixin(LevelTicks.class)
public interface LevelTicksAccessor<T> {

    @Accessor("allContainers")
    Long2ObjectMap<LevelChunkTicks<T>> tessellate$allContainers();

    @Accessor("toRunThisTick")
    Queue<ScheduledTick<T>> tessellate$toRunThisTick();

    @Accessor("alreadyRunThisTick")
    List<ScheduledTick<T>> tessellate$alreadyRunThisTick();
}
