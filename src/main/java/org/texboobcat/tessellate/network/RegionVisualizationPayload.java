package org.texboobcat.tessellate.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.texboobcat.tessellate.Tessellate;

import java.util.ArrayList;
import java.util.List;

// Compact server snapshot consumed by the client-side GPU region overlay.
public record RegionVisualizationPayload(boolean enabled, int sectionShift, int currentRegionId,
                                         int parallelRegions, long merges, long splits,
                                         List<Cell> cells) implements CustomPacketPayload {

    public static final int MAX_CELLS = 1024;
    public static final Type<RegionVisualizationPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(Tessellate.MODID, "region_visualization"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RegionVisualizationPayload> STREAM_CODEC =
        StreamCodec.ofMember(RegionVisualizationPayload::write, RegionVisualizationPayload::read);

    public RegionVisualizationPayload {
        cells = List.copyOf(cells);
        if (cells.size() > MAX_CELLS) {
            throw new IllegalArgumentException("too many visualization cells: " + cells.size());
        }
    }

    public static RegionVisualizationPayload disabled() {
        return new RegionVisualizationPayload(false, 0, -1, 0, 0, 0, List.of());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(this.enabled);
        buffer.writeVarInt(this.sectionShift);
        buffer.writeVarInt(this.currentRegionId + 1);
        buffer.writeVarInt(this.parallelRegions);
        buffer.writeVarLong(this.merges);
        buffer.writeVarLong(this.splits);
        buffer.writeVarInt(this.cells.size());
        for (Cell cell : this.cells) {
            buffer.writeLong(cell.section());
            buffer.writeVarInt(cell.regionId());
            buffer.writeVarInt(cell.workerIndex() + 1);
            buffer.writeVarInt(cell.tickDivisor());
            buffer.writeFloat(cell.tickMillis());
        }
    }

    private static RegionVisualizationPayload read(RegistryFriendlyByteBuf buffer) {
        boolean enabled = buffer.readBoolean();
        int sectionShift = buffer.readVarInt();
        int currentRegionId = buffer.readVarInt() - 1;
        int parallelRegions = buffer.readVarInt();
        long merges = buffer.readVarLong();
        long splits = buffer.readVarLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_CELLS) {
            throw new IllegalArgumentException("invalid visualization cell count: " + count);
        }
        List<Cell> cells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cells.add(new Cell(buffer.readLong(), buffer.readVarInt(), buffer.readVarInt() - 1,
                buffer.readVarInt(), buffer.readFloat()));
        }
        return new RegionVisualizationPayload(enabled, sectionShift, currentRegionId,
            parallelRegions, merges, splits, cells);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Cell(long section, int regionId, int workerIndex, int tickDivisor,
                       float tickMillis) {
    }
}
