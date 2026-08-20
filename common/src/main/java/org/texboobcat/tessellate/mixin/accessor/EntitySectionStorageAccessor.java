package org.texboobcat.tessellate.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// Exposes EntitySectionStorage.sections for reading and replacement.
//
// The sharded storage needs both directions. It reads each shard's map to chain a lazy view over
// all of them, and it replaces its own inherited map with that view, because third-party code
// reads the field directly rather than calling a method. Lithium's spawning optimization shadows
// it to count mobs against the spawn cap.
@Mixin(EntitySectionStorage.class)
public interface EntitySectionStorageAccessor {

    @Accessor("sections")
    Long2ObjectMap<? extends EntitySection<?>> tessellate$getSections();

    @Accessor("sections")
    @Mutable
    void tessellate$setSections(Long2ObjectMap<? extends EntitySection<?>> sections);
}
