package org.texboobcat.tessellate.region;

import net.minecraft.core.BlockPos;
import org.texboobcat.tessellate.guard.RegionThreadContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

final class RegionalTaskQueue {

    private record Task(BlockPos pos, Runnable work) {
    }

    private final Queue<Task> pending = new ConcurrentLinkedQueue<>();

    void add(BlockPos pos, Runnable work) {
        this.pending.add(new Task(pos, work));
    }

    boolean isEmpty() {
        return this.pending.isEmpty();
    }

    void drain(@Nullable Region current, Function<BlockPos, Region> ownerAt) {
        int remaining = this.pending.size();
        while (remaining-- > 0) {
            Task task = this.pending.poll();
            if (task == null) {
                return;
            }
            Region owner = ownerAt.apply(task.pos());
            if ((current == null && owner == null)
                || (current != null && owner != null && current.id() == owner.id())) {
                task.work().run();
            } else {
                this.pending.add(task);
            }
        }
    }

    void dispatch(Collection<Region> regions, String levelKey,
                  Function<BlockPos, Region> ownerAt, boolean parallel) {
        if (this.pending.isEmpty()) {
            return;
        }
        List<Runnable> tasks = new ArrayList<>(regions.size());
        for (Region region : regions) {
            tasks.add(() -> {
                RegionThreadContext.enter(region, levelKey);
                try {
                    drain(region, ownerAt);
                } finally {
                    RegionThreadContext.exit();
                }
            });
        }
        if (parallel) {
            RegionWorkers.runAllAndWait(tasks);
        } else {
            tasks.forEach(Runnable::run);
        }
        drain(null, ownerAt);
    }
}
