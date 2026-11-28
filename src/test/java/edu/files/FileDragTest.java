package edu.files;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static edu.files.FileDrag.Intent.*;
import static edu.files.FileDrag.Payload.*;
import static org.junit.jupiter.api.Assertions.*;

class FileDragTest {
    private static final Path ROOT = Path.of("drag-fixture").toAbsolutePath();

    @Test
    void defaultsToCopyAndTreatsConflictingModifiersAsLink() {
        assertEquals(COPY, FileDrag.keyIntent(false, false, false));
        assertEquals(COPY, FileDrag.keyIntent(true, false, false));
        assertEquals(MOVE, FileDrag.keyIntent(false, true, false));
        assertEquals(LINK, FileDrag.keyIntent(true, true, false));
        assertEquals(LINK, FileDrag.keyIntent(false, false, true));
    }

    @Test
    void internalSessionRequiresBothGestureAndTokenAndCannotFallBackToExternal() throws IOException {
        var origin = new Object();
        var session = FileDrag.Source.create(origin, List.of(entry(ROOT.resolve("a.txt"), false)), Map.of());
        assertEquals(INTERNAL, FileDrag.classify(session, origin, true, session.token(), true));
        assertEquals(INVALID, FileDrag.classify(session, new Object(), true, session.token(), true));
        assertEquals(INVALID, FileDrag.classify(session, origin, true, "replaced token", true));
        assertEquals(INVALID, FileDrag.classify(null, null, true, session.token(), true));
        assertEquals(INVALID, FileDrag.classify(session, origin, false, null, true));
        assertEquals(EXTERNAL, FileDrag.classify(session, null, false, null, true));
        assertEquals(OTHER, FileDrag.classify(session, null, false, null, false));
    }

    @Test
    void sourceRejectsSameParentDescendantAndUnverifiedMove() throws IOException {
        Path folder = ROOT.resolve("folder");
        var source = FileDrag.Source.create(new Object(), List.of(entry(folder, true)), Map.of());
        assertNotNull(source.restriction(ROOT, null, COPY));
        assertNotNull(source.restriction(folder, null, COPY));
        assertNotNull(source.restriction(folder.resolve("child"), null, COPY));
        assertNull(source.restriction(ROOT.resolve("destination"), null, COPY));
        assertNotNull(source.restriction(ROOT.resolve("destination"), null, MOVE));
    }

    @Test
    void dropPathLimitsApplyBeforeDeduplicationAndRejectRelativeOrNestedPaths() throws IOException {
        String file = ROOT.resolve("中文 文件.txt").toString();
        assertEquals(List.of(Path.of(file)), ClipboardFiles.dragPaths(List.of(file, file)));
        assertThrows(IOException.class, () -> ClipboardFiles.dragPaths(Collections.nCopies(ClipboardFiles.MAX_ITEMS + 1, file)));
        assertThrows(IOException.class, () -> ClipboardFiles.dragPaths(List.of("relative.txt")));
        assertThrows(IOException.class, () -> ClipboardFiles.dragPaths(List.of(ROOT.toString(), file)));
        assertThrows(IOException.class, () -> ClipboardFiles.dragPaths(List.of(ROOT.resolve("bad.txt") + "\n")));
    }

    private static BrowserItem entry(Path path, boolean directory) {
        var date = FileTime.fromMillis(1);
        return BrowserItem.file(new LocalFiles.Entry(path, directory, false, 0, date, date, null, false));
    }
}
