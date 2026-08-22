package org.texboobcat.tessellate.region;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionalTaskQueueTest {

    @Test
    void dispatchesOnlyToTheCurrentOwner() {
        RegionalTaskQueue queue = new RegionalTaskQueue();
        Region first = new Region(1, 2);
        Region second = new Region(2, 2);
        BlockPos firstPos = new BlockPos(0, 64, 0);
        BlockPos secondPos = new BlockPos(64, 64, 0);
        BlockPos unownedPos = new BlockPos(128, 64, 0);
        List<String> ran = new ArrayList<>();

        queue.add(firstPos, () -> ran.add("first"));
        queue.add(secondPos, () -> ran.add("second"));
        queue.add(unownedPos, () -> ran.add("unowned"));

        java.util.function.Function<BlockPos, Region> ownerAt = pos ->
            pos.equals(firstPos) ? first : pos.equals(secondPos) ? second : null;
        queue.drain(first, ownerAt);
        assertEquals(List.of("first"), ran);
        queue.drain(null, ownerAt);
        assertEquals(List.of("first", "unowned"), ran);
        queue.drain(second, ownerAt);
        assertEquals(List.of("first", "unowned", "second"), ran);
    }
}
