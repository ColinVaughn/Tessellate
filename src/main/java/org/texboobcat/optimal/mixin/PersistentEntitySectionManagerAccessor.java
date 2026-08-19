package org.texboobcat.optimal.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reaches the section storage the manager was built with. See the level accessor for why.
@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerAccessor {

    @Accessor("sectionStorage")
    EntitySectionStorage<?> optimal$getSectionStorage();

    // Vanilla's confirmed per-chunk entity visibility, updated by the full-status callback.
    @Accessor("chunkVisibility")
    Long2ObjectMap<Visibility> optimal$getChunkVisibility();

    // The level's own EntityCallbacks, so deferred callbacks can be replayed through it.
    @Accessor("callbacks")
    net.minecraft.world.level.entity.LevelCallback<?> optimal$getCallbacks();
}
