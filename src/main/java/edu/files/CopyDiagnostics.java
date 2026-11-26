package edu.files;

import java.util.Locale;

final class CopyDiagnostics implements AutoCloseable {
    enum Part {
        OPEN_PATH, OPEN_RELATIVE, CLOSE_HANDLE, FILE_ID, ATTRIBUTES, COPY_DATA, PROGRESS_CALLBACK,
        SECURITY_READ, SECURITY_WRITE, TREE_CHECK, CLEANUP
    }

    private static final ThreadLocal<CopyDiagnostics> ACTIVE = new ThreadLocal<>();
    private final long[] calls = new long[Part.values().length];
    private final long[] elapsed = new long[calls.length];

    private CopyDiagnostics() { }

    static CopyDiagnostics capture() {
        var diagnostics = new CopyDiagnostics();
        ACTIVE.set(diagnostics);
        return diagnostics;
    }

    static long begin() { return ACTIVE.get() == null ? 0 : System.nanoTime(); }

    static void end(Part part, long start) {
        if (start == 0) { return; }
        var diagnostics = ACTIVE.get();
        diagnostics.calls[part.ordinal()]++;
        diagnostics.elapsed[part.ordinal()] += System.nanoTime() - start;
    }

    static void count(Part part) {
        var diagnostics = ACTIVE.get();
        if (diagnostics != null) { diagnostics.calls[part.ordinal()]++; }
    }

    String report() {
        var lines = new StringBuilder();
        for (Part part : Part.values()) {
            lines.append(String.format(Locale.ROOT, "%s calls=%d time_ms=%.2f%n", part,
                    calls[part.ordinal()], elapsed[part.ordinal()] / 1e6));
        }
        return lines.toString();
    }

    @Override
    public void close() { ACTIVE.remove(); }
}
