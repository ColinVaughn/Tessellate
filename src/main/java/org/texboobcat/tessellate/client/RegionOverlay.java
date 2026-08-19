package org.texboobcat.tessellate.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.texboobcat.tessellate.Tessellate;
import org.texboobcat.tessellate.network.RegionVisualizationPayload;
import org.texboobcat.tessellate.region.RegionSectionPos;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Shader-backed world boundaries and a live map for server region snapshots.
@EventBusSubscriber(modid = Tessellate.MODID, value = Dist.CLIENT)
public final class RegionOverlay {

    private static final int[] COLORS = {
        0xFF4B4B, 0x4BFF74, 0x4B78FF, 0xFFD447,
        0xF24BFF, 0x35E8F2, 0xFF8A35, 0xA5E83A,
        0x915CFF, 0xFF62A9, 0x32C99A, 0x43B9FF,
        0xC9A33B, 0x70E898, 0xC84BE0, 0xFF705C
    };
    private static final int IDLE_COLOR = 0x737A86;
    private static final double WORLD_RADIUS = 512.0;
    private static final int PANEL_WIDTH = 184;
    private static final int PANEL_HEIGHT = 124;
    private static final long STALE_NANOS = 2_000_000_000L;

    private static State state = State.disabled();

    private RegionOverlay() {
    }

    public static void accept(RegionVisualizationPayload payload) {
        Map<Long, RegionVisualizationPayload.Cell> bySection = new LinkedHashMap<>();
        for (RegionVisualizationPayload.Cell cell : payload.cells()) {
            bySection.put(cell.section(), cell);
        }
        state = new State(payload, Map.copyOf(bySection), System.nanoTime());
    }

    @SubscribeEvent
    public static void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        State current = state;
        Minecraft minecraft = Minecraft.getInstance();
        if (!current.active() || minecraft.player == null || minecraft.level == null) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        double minY = minecraft.level.getMinBuildHeight();
        double maxY = minecraft.level.getMaxBuildHeight();
        int cellSize = 1 << (current.payload().sectionShift() + 4);
        float pulse = 0.5F + 0.5F * (float) Math.sin(
            (minecraft.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) * 0.16);

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        for (RegionVisualizationPayload.Cell cell : current.payload().cells()) {
            renderCell(current, cell, poseStack, buffers, camera, minY, maxY, cellSize, pulse);
        }
        buffers.endBatch(RenderType.debugFilledBox());
    }

    private static void renderCell(State current, RegionVisualizationPayload.Cell cell,
                                   PoseStack poseStack, MultiBufferSource buffers, Vec3 camera,
                                   double minY, double maxY, int cellSize, float pulse) {
        int sectionX = RegionSectionPos.x(cell.section());
        int sectionZ = RegionSectionPos.z(cell.section());
        double minX = (double) sectionX * cellSize;
        double minZ = (double) sectionZ * cellSize;
        if (distanceSquaredToCell(camera.x, camera.z, minX, minZ, cellSize)
            > WORLD_RADIUS * WORLD_RADIUS) {
            return;
        }

        int color = color(cell.regionId());
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        float wallAlpha = cell.regionId() == current.payload().currentRegionId()
            ? 0.11F + pulse * 0.05F : 0.075F;

        if (!sameRegion(current, sectionX - 1, sectionZ, cell.regionId())) {
            renderBox(poseStack, buffers, camera, minX - 0.06, minY, minZ,
                minX + 0.06, maxY, minZ + cellSize, red, green, blue, wallAlpha);
        }
        if (!sameRegion(current, sectionX + 1, sectionZ, cell.regionId())) {
            renderBox(poseStack, buffers, camera, minX + cellSize - 0.06, minY, minZ,
                minX + cellSize + 0.06, maxY, minZ + cellSize, red, green, blue, wallAlpha);
        }
        if (!sameRegion(current, sectionX, sectionZ - 1, cell.regionId())) {
            renderBox(poseStack, buffers, camera, minX, minY, minZ - 0.06,
                minX + cellSize, maxY, minZ + 0.06, red, green, blue, wallAlpha);
        }
        if (!sameRegion(current, sectionX, sectionZ + 1, cell.regionId())) {
            renderBox(poseStack, buffers, camera, minX, minY, minZ + cellSize - 0.06,
                minX + cellSize, maxY, minZ + cellSize + 0.06, red, green, blue, wallAlpha);
        }
    }

    @SubscribeEvent
    public static void renderHud(RenderGuiEvent.Post event) {
        State current = state;
        Minecraft minecraft = Minecraft.getInstance();
        if (!current.active() || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        drawMap(event.getGuiGraphics(), minecraft, current.payload());
    }

    private static void drawMap(GuiGraphics graphics, Minecraft minecraft,
                                RegionVisualizationPayload payload) {
        int left = graphics.guiWidth() - PANEL_WIDTH - 8;
        int top = 8;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xC010141A);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF596273);
        graphics.drawString(minecraft.font,
            "TESSELLATE  " + uniqueRegionCount(payload.cells()) + " REGIONS  |  "
                + payload.parallelRegions() + " PARALLEL",
            left + 7, top + 6, 0xFFF1F4FA, false);

        int mapLeft = left + 7;
        int mapTop = top + 20;
        int mapWidth = PANEL_WIDTH - 14;
        int mapHeight = 72;
        graphics.fill(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight, 0xB006090D);
        if (!payload.cells().isEmpty()) {
            drawCells(graphics, payload, mapLeft, mapTop, mapWidth, mapHeight);
        }

        graphics.drawString(minecraft.font,
            "merges " + payload.merges() + "  splits " + payload.splits(),
            left + 7, top + 96, 0xFF8F99A8, false);
        List<RegionVisualizationPayload.Cell> regions = payload.cells().stream()
            .sorted(Comparator.comparing((RegionVisualizationPayload.Cell cell) ->
                cell.regionId() != payload.currentRegionId()).thenComparingInt(
                    RegionVisualizationPayload.Cell::regionId))
            .collect(LinkedHashMap<Integer, RegionVisualizationPayload.Cell>::new,
                (map, cell) -> map.putIfAbsent(cell.regionId(), cell), Map::putAll)
            .values().stream().limit(3).toList();
        int x = left + 7;
        for (RegionVisualizationPayload.Cell cell : regions) {
            int color = color(cell.regionId());
            graphics.fill(x, top + 111, x + 6, top + 117, 0xFF000000 | color);
            String label = "R" + cell.regionId() + " T"
                + (cell.workerIndex() < 0 ? "-" : cell.workerIndex())
                + " " + String.format(java.util.Locale.ROOT, "%.1fms", cell.tickMillis());
            graphics.drawString(minecraft.font, label, x + 9, top + 109, 0xFFD7DCE5, false);
            x += minecraft.font.width(label) + 18;
            if (x > left + PANEL_WIDTH - 45) {
                break;
            }
        }
    }

    private static void drawCells(GuiGraphics graphics, RegionVisualizationPayload payload,
                                  int left, int top, int width, int height) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (RegionVisualizationPayload.Cell cell : payload.cells()) {
            int x = RegionSectionPos.x(cell.section());
            int z = RegionSectionPos.z(cell.section());
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        double scale = Math.min((width - 4.0) / (maxX - minX + 1.0),
            (height - 4.0) / (maxZ - minZ + 1.0));
        scale = Math.max(1.0, Math.min(12.0, scale));
        double usedWidth = (maxX - minX + 1.0) * scale;
        double usedHeight = (maxZ - minZ + 1.0) * scale;
        double originX = left + (width - usedWidth) / 2.0;
        double originY = top + (height - usedHeight) / 2.0;
        int size = Math.max(1, (int) Math.ceil(scale));
        for (RegionVisualizationPayload.Cell cell : payload.cells()) {
            int x = (int) Math.floor(originX + (RegionSectionPos.x(cell.section()) - minX) * scale);
            int y = (int) Math.floor(originY + (RegionSectionPos.z(cell.section()) - minZ) * scale);
            graphics.fill(x, y, x + size, y + size, 0xD0000000 | color(cell.regionId()));
            if (cell.regionId() == payload.currentRegionId()) {
                graphics.renderOutline(x, y, size, size, 0xFFFFFFFF);
            }
        }
    }

    private static int uniqueRegionCount(List<RegionVisualizationPayload.Cell> cells) {
        return (int) cells.stream().map(RegionVisualizationPayload.Cell::regionId).distinct().count();
    }

    private static boolean sameRegion(State current, int sectionX, int sectionZ, int regionId) {
        RegionVisualizationPayload.Cell neighbor = current.bySection().get(
            RegionSectionPos.pack(sectionX, sectionZ));
        return neighbor != null && neighbor.regionId() == regionId;
    }

    private static double distanceSquaredToCell(double x, double z, double minX, double minZ,
                                                int size) {
        double dx = x < minX ? minX - x : Math.max(0.0, x - (minX + size));
        double dz = z < minZ ? minZ - z : Math.max(0.0, z - (minZ + size));
        return dx * dx + dz * dz;
    }

    private static void renderBox(PoseStack poseStack, MultiBufferSource buffers, Vec3 camera,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ,
                                  float red, float green, float blue, float alpha) {
        DebugRenderer.renderFilledBox(poseStack, buffers,
            new AABB(minX - camera.x, minY - camera.y, minZ - camera.z,
                maxX - camera.x, maxY - camera.y, maxZ - camera.z),
            red, green, blue, alpha);
    }

    private static int color(int regionId) {
        return regionId < 0 ? IDLE_COLOR : COLORS[Math.floorMod(regionId, COLORS.length)];
    }

    private record State(RegionVisualizationPayload payload,
                         Map<Long, RegionVisualizationPayload.Cell> bySection,
                         long receivedAt) {
        private static State disabled() {
            return new State(RegionVisualizationPayload.disabled(), Map.of(), 0L);
        }

        private boolean active() {
            return this.payload.enabled() && System.nanoTime() - this.receivedAt < STALE_NANOS;
        }
    }
}
