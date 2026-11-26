package edu.files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Shared test clipboard. The UI can face another owner without reading the user's desktop. */
final class TestClipboard implements FileClipboard {
    private Snapshot content = new Snapshot(new State(0, Kind.EMPTY), null, List.of(), false);
    private String text;
    private int replaceAttempts;
    final AtomicReference<Callable<Snapshot>> nextRead = new AtomicReference<>();

    @Override public synchronized State probe() { return content.state(); }

    @Override public Snapshot read() throws IOException {
        var injected = nextRead.getAndSet(null);
        if (injected == null) { return snapshot(); }
        try {
            return injected.call();
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException(failure);
        }
    }

    synchronized Snapshot snapshot() { return content; }
    synchronized String textValue() { return text; }
    synchronized int replaceAttempts() { return replaceAttempts; }

    @Override
    public synchronized State publish(Offer offer) {
        text = null;
        return advance(offer.cut() ? Kind.CUT : Kind.FILES, offer.token(), offer.cut() ? List.of() : offer.paths(), false);
    }

    @Override
    public synchronized Replacement replace(State expected, UUID token, Offer replacement) {
        replaceAttempts++;
        if (!expected.equals(content.state()) || !token.equals(content.token())) {
            return new Replacement(false, content.state());
        }
        return new Replacement(true, replacement == null ? advance(Kind.EMPTY, null, List.of(), false) : publish(replacement));
    }

    @Override
    public synchronized State text(String value) {
        text = value;
        return advance(Kind.EMPTY, null, List.of(), false);
    }

    synchronized State external(Kind kind, List<Path> paths, boolean cut) {
        text = null;
        return advance(kind, null, paths, cut);
    }

    private State advance(Kind kind, UUID token, List<Path> paths, boolean cut) {
        var state = new State(content.state().version() + 1, kind);
        content = new Snapshot(state, token, paths, cut);
        return state;
    }
}
