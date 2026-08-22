package org.texboobcat.tessellate.api;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.texboobcat.tessellate.region.DeferredMainThreadWork;
import org.texboobcat.tessellate.region.Region;
import org.texboobcat.tessellate.guard.RegionThreadContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TessellateApiTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void registersMainThreadEntityTypes() {
        assertFalse(TessellateApi.requiresMainThreadEntityTick(EntityType.ALLAY));

        TessellateApi.registerMainThreadEntity(EntityType.ALLAY);

        assertTrue(TessellateApi.requiresMainThreadEntityTick(EntityType.ALLAY));
        assertThrows(NullPointerException.class,
            () -> TessellateApi.registerMainThreadEntity(null));
    }

    @Test
    void registersMainThreadBlockEntityTypes() {
        assertFalse(TessellateApi.requiresMainThreadBlockEntityTick(BlockEntityType.CHEST));

        TessellateApi.registerMainThreadBlockEntity(BlockEntityType.CHEST);

        assertTrue(TessellateApi.requiresMainThreadBlockEntityTick(BlockEntityType.CHEST));
        assertThrows(NullPointerException.class,
            () -> TessellateApi.registerMainThreadBlockEntity(null));
    }

    @Test
    void resolvesConfiguredEntityIds() {
        TessellateApi.configureMainThreadEntities(List.of("minecraft:armadillo"));

        assertTrue(TessellateApi.requiresMainThreadEntityTick(EntityType.ARMADILLO));
        assertFalse(TessellateApi.requiresMainThreadEntityTick(EntityType.BAT));

        TessellateApi.configureMainThreadEntities(List.of());
        assertFalse(TessellateApi.requiresMainThreadEntityTick(EntityType.ARMADILLO));
    }

    @Test
    void queuesMainThreadWork() {
        AtomicBoolean ran = new AtomicBoolean();
        TessellateApi.executeOnMainThread(() -> ran.set(true));
        assertFalse(ran.get());
        try {
            DeferredMainThreadWork.drain();
            assertTrue(ran.get());
        } finally {
            DeferredMainThreadWork.reset();
        }
    }

    @Test
    void reportsOnlyBoundRegionScopes() {
        assertFalse(TessellateApi.isRegionThread());
        RegionThreadContext.enter(new Region(7, 2), "minecraft:overworld");
        try {
            assertTrue(TessellateApi.isRegionThread());
        } finally {
            RegionThreadContext.exit();
        }
        assertFalse(TessellateApi.isRegionThread());
    }
}
