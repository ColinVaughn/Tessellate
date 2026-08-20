package org.texboobcat.tessellate.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.texboobcat.tessellate.gametest.RegionalChunkGameTestCases;

import java.util.UUID;

@GameTestHolder("minecraft")
@PrefixGameTestTemplate(false)
public final class RegionalChunkGameTests {

    private RegionalChunkGameTests() {
    }

    @GameTest(template = "bastion/blocks/air", timeoutTicks = 100)
    public static void mainThreadBoundaryHandoffs(GameTestHelper helper) {
        RegionalChunkGameTestCases.mainThreadBoundaryHandoffs(helper);
    }

    @GameTest(template = "bastion/blocks/air", timeoutTicks = 4800, skyAccess = true)
    public static void regionalChunkTicksAndNaturalSpawning(GameTestHelper helper) {
        RegionalChunkGameTestCases.regionalChunkTicksAndNaturalSpawning(
            helper, RegionalChunkGameTests::makeNegotiatedMockPlayer);
    }

    @GameTest(template = "bastion/blocks/air", timeoutTicks = 200)
    public static void pathfindingIsConcurrentOnRegionWorkers(GameTestHelper helper) {
        RegionalChunkGameTestCases.pathfindingIsConcurrentOnRegionWorkers(helper);
    }

    @GameTest(template = "bastion/blocks/air", timeoutTicks = 100)
    public static void scheduledTickRouterPreservesVanillaContainerSemantics(
            GameTestHelper helper) {
        RegionalChunkGameTestCases.scheduledTickRouterPreservesVanillaContainerSemantics(helper);
    }

    @GameTest(template = "bastion/blocks/air", timeoutTicks = 100)
    public static void blockEventRouterPreservesOrderDedupAndMainThreadPackets(
            GameTestHelper helper) {
        RegionalChunkGameTestCases.blockEventRouterPreservesOrderDedupAndMainThreadPackets(helper);
    }

    private static ServerPlayer makeNegotiatedMockPlayer(ServerLevel level, String name) {
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
        NetworkRegistry.configureMockConnection(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
}
