package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RegionWorkersTest {

    @Test
    void awaitIdleCannotPassALiveRegionTask() throws Exception {
        RegionWorkers.TaskTracker tasks = new RegionWorkers.TaskTracker();
        tasks.started();
        CompletableFuture<Void> idle = CompletableFuture.runAsync(tasks::awaitIdle);
        assertThrows(TimeoutException.class, () -> idle.get(50, TimeUnit.MILLISECONDS));

        tasks.finished();
        idle.get(1, TimeUnit.SECONDS);
    }
}
