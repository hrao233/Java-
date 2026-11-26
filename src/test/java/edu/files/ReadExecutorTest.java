package edu.files;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReadExecutorTest {
    @Test
    void removesSupersededQueuedRequestsAndKeepsTheQueueBounded() throws Exception {
        var running = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var readers = new ReadExecutor()) {
            for (int n = 0; n < 2; n++) { assertTrue(readers.listing(blocked(running, release))); }
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var superseded = new ArrayList<FutureTask<Integer>>();
            for (int n = 0; n < 250; n++) {
                var obsolete = new FutureTask<Integer>(() -> fail("An obsolete queued listing ran"));
                assertTrue(readers.listing(obsolete));
                readers.cancel(obsolete);
                superseded.add(obsolete);
            }
            var latest = new FutureTask<>(() -> 42);
            assertTrue(readers.listing(latest));
            assertTrue(readers.listing(new FutureTask<>(() -> 43)));
            assertFalse(readers.listing(new FutureTask<>(() -> 44)));
            release.countDown();
            assertEquals(42, latest.get(5, TimeUnit.SECONDS));
            assertTrue(superseded.stream().allMatch(FutureTask::isCancelled));
        } finally {
            release.countDown();
        }
    }

    @Test
    void foregroundListingsCanRunWhileDetailWorkersAreOccupied() throws Exception {
        var running = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var readers = new ReadExecutor()) {
            assertTrue(readers.detail(blocked(running, release)));
            assertTrue(readers.detail(blocked(running, release)));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var listing = new FutureTask<>(() -> "directory ready");
            assertTrue(readers.listing(listing));
            assertEquals("directory ready", listing.get(5, TimeUnit.SECONDS));
            assertEquals(1, release.getCount());
        } finally {
            release.countDown();
        }
    }

    private static FutureTask<Void> blocked(CountDownLatch running, CountDownLatch release) {
        return new FutureTask<>(() -> {
            running.countDown();
            release.await();
            return null;
        });
    }
}
