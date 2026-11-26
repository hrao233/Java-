package edu.files;

import edu.files.FileChanges.Outcome;
import edu.files.FileChanges.Selection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

interface FileClipboard {
    enum Kind { EMPTY, FILES, CUT, UNSUPPORTED }

    record State(long version, Kind kind) { }

    record Snapshot(State state, UUID token, List<Path> paths, boolean externalMove) {
        public Snapshot {
            paths = List.copyOf(paths);
        }
    }

    record Offer(UUID token, boolean cut, List<Path> paths) {
        public Offer {
            paths = List.copyOf(paths);
        }
    }

    record Transfer(State state, UUID token, boolean move, List<Selection> sources) {
        public Transfer {
            sources = List.copyOf(sources);
        }

        boolean matches(Snapshot snapshot) {
            return state.equals(snapshot.state()) && token.equals(snapshot.token());
        }

        Transfer after(Outcome outcome) {
            var remaining = move ? outcome.remainingCut(sources) : outcome.remainingCopy(sources);
            return new Transfer(state, token, move, remaining);
        }

        Offer offer() {
            return new Offer(token, move, sources.stream().map(Selection::path).toList());
        }
    }

    record Replacement(boolean applied, State state) { }

    /** Reads availability and version without requesting delayed payloads. */
    State probe() throws IOException;

    Snapshot read() throws IOException;

    State publish(Offer offer) throws IOException;

    /** Checks the version and the private token under the same lock as the update. */
    Replacement replace(State expected, UUID token, Offer replacement) throws IOException;

    State text(String value) throws IOException;

    static FileClipboard system() {
        return com.sun.jna.Platform.isWindows() && com.sun.jna.Native.POINTER_SIZE == 8
                ? new WindowsClipboard() : new MemoryClipboard(true);
    }
}
