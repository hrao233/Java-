package edu.files;

import java.util.List;
import java.util.UUID;

/** Internal clipboard on platforms without the Windows adapter. */
final class MemoryClipboard implements FileClipboard {
    private final boolean systemText;
    private State state = new State(0, Kind.EMPTY);
    private Offer current;

    MemoryClipboard(boolean systemText) { this.systemText = systemText; }

    @Override public synchronized State probe() { return state; }

    @Override
    public synchronized Snapshot read() {
        return current == null ? new Snapshot(state, null, List.of(), false)
                : new Snapshot(state, current.token(), current.cut() ? List.of() : current.paths(), false);
    }

    @Override
    public synchronized State publish(Offer offer) {
        current = offer;
        return advance(offer.cut() ? Kind.CUT : Kind.FILES);
    }

    @Override
    public synchronized Replacement replace(State expected, UUID token, Offer replacement) {
        if (!state.equals(expected) || current == null || !current.token().equals(token)) {
            return new Replacement(false, state);
        }
        State next = replacement == null ? clear() : publish(replacement);
        return new Replacement(true, next);
    }

    @Override
    public synchronized State text(String value) {
        // Text remains a system operation on platforms that use the internal file clipboard.
        if (systemText) {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(value), null);
        }
        return clear();
    }

    private State clear() {
        current = null;
        return advance(Kind.EMPTY);
    }

    private State advance(Kind kind) {
        state = new State(state.version() + 1, kind);
        return state;
    }
}
