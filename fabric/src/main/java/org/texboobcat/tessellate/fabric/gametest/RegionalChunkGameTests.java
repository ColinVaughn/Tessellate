package org.texboobcat.tessellate.fabric.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.texboobcat.tessellate.gametest.RegionalChunkGameTestCases;

import java.util.UUID;

public final class RegionalChunkGameTests {

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void mainThreadBoundaryHandoffs(GameTestHelper helper) {
        RegionalChunkGameTestCases.mainThreadBoundaryHandoffs(helper);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200)
    public void compatibilityApiRunsOnLiveOwners(GameTestHelper helper) {
        RegionalChunkGameTestCases.compatibilityApiRunsOnLiveOwners(helper);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 4800, skyAccess = true)
    public void regionalChunkTicksAndNaturalSpawning(GameTestHelper helper) {
        RegionalChunkGameTestCases.regionalChunkTicksAndNaturalSpawning(
            helper, RegionalChunkGameTests::makeMockPlayer);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200)
    public void pathfindingIsConcurrentOnRegionWorkers(GameTestHelper helper) {
        RegionalChunkGameTestCases.pathfindingIsConcurrentOnRegionWorkers(helper);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void scheduledTickRouterPreservesVanillaContainerSemantics(GameTestHelper helper) {
        RegionalChunkGameTestCases.scheduledTickRouterPreservesVanillaContainerSemantics(helper);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void blockEventRouterPreservesOrderDedupAndMainThreadPackets(GameTestHelper helper) {
        RegionalChunkGameTestCases.blockEventRouterPreservesOrderDedupAndMainThreadPackets(helper);
    }

    private static ServerPlayer makeMockPlayer(ServerLevel level, String name) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile,
            ClientInformation.createDefault()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
}
