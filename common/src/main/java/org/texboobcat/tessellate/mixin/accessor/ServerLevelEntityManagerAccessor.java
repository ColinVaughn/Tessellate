package org.texboobcat.tessellate.mixin.accessor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reaches a level's entity manager.
//
// The sharded storage is built inside PersistentEntitySectionManager's constructor,
// which knows nothing about the level it belongs to. The in-flight detector has to answer "is the
// region owning this cell, in this level, running right now", so the storage is bound to its level
// once at level load rather than threaded through construction.
@Mixin(ServerLevel.class)
public interface ServerLevelEntityManagerAccessor {

    @Accessor("entityManager")
    PersistentEntitySectionManager<?> tessellate$getEntityManager();
}
