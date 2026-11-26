package edu.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryEventsTest {
    @TempDir Path root;

    @Test
    void burstCoalescesByPathAndRetainsTheLastObservation() {
        var events = new DirectoryEvents();
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        events.record(a, ms(0));
        events.record(a, ms(40));
        events.record(b, ms(100));
        assertNull(events.take(ms(259)));
        var batch = events.take(ms(260));
        assertEquals(Set.of(a, b), batch.entries());
        assertFalse(batch.full());
        assertFalse(batch.coveredBy(ms(99)));
        assertTrue(batch.coveredBy(ms(100)));
        assertTrue(batch.impact(root).affectsDirectory(root, false));
        assertTrue(batch.impact(root).affectsEntry(a));
        assertFalse(batch.impact(root).affectsEntry(root.resolve("c")));
        assertNull(events.take(ms(700)));
    }

    @Test
    void continuousChangesReachADeadlineEvenWithoutAQuietPeriod() {
        var events = new DirectoryEvents();
        for (int millis = 0; millis < 500; millis += 50) {
            events.record(root.resolve("stable-name"), ms(millis));
            assertNull(events.take(ms(millis)));
        }
        assertNull(events.take(ms(499)));
        assertNotNull(events.take(ms(500)));
        events.record(null, ms(501));
        assertTrue(events.take(ms(661)).full());
    }

    @Test
    void overflowAndPathLimitBoundBothTheWorkerAndPendingRefresh() {
        var events = new DirectoryEvents();
        for (int i = 0; i < 128; i++) { events.record(root.resolve("entry-" + i), ms(0)); }
        var maximum = events.take(ms(160));
        assertFalse(maximum.full());
        Path extra = root.resolve("extra");
        events.record(extra, ms(170));
        var merged = maximum.merge(events.take(ms(330)));
        assertTrue(merged.full());
        assertTrue(merged.entries().isEmpty());
        assertEquals(ms(170), merged.observedAt());

        maximum.entries().forEach(entry -> events.record(entry, ms(400)));
        events.record(extra, ms(400));
        assertTrue(events.take(ms(560)).full());
        events.record(extra, ms(600));
        events.record(null, ms(601));
        events.record(extra, ms(602));
        assertTrue(events.take(ms(762)).entries().isEmpty());
    }

    private static long ms(long millis) { return Duration.ofMillis(millis).toNanos(); }
}
