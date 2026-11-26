package edu.files;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static java.nio.file.StandardWatchEventKinds.*;

final class DirectoryWatcher implements AutoCloseable {
    enum Status { ACTIVE, WAITING, UNAVAILABLE }
    record Target(Path directory) { }
    record Notice(Target target, Status status, DirectoryEvents.Batch changes) { }

    @FunctionalInterface
    interface IdentityReader { DirectoryIdentity read(Path path) throws IOException; }

    private static final long POLL = Duration.ofMillis(125).toNanos();
    private static final long RETRY = Duration.ofSeconds(1).toNanos();
    private static final long CHECK = Duration.ofSeconds(2).toNanos();

    private final Consumer<Notice> receiver;
    private final Predicate<Path> exclude;
    private final IdentityReader identities;
    private volatile Target desired;
    private volatile boolean closed;
    private volatile int registrations;
    private Thread worker;

    DirectoryWatcher(IdentityReader identities, Predicate<Path> exclude, Consumer<Notice> receiver) {
        this.identities = identities;
        this.exclude = exclude;
        this.receiver = receiver;
    }

    synchronized Target watch(Path directory) {
        if (closed) { throw new IllegalStateException("Watcher is closed"); }
        var target = new Target(directory.toAbsolutePath().normalize());
        desired = target;
        if (worker == null) {
            worker = new Thread(this::run, "file-manager-watch");
            worker.setDaemon(true);
            worker.start();
        }
        notifyAll();
        return target;
    }

    synchronized void pause() { desired = null; }

    int registrationCount() { return registrations; }

    @Override
    public synchronized void close() {
        closed = true;
        desired = null;
        if (worker != null) { worker.interrupt(); }
        notifyAll();
    }

    boolean awaitClosed(Duration timeout) throws InterruptedException {
        Thread running;
        synchronized (this) { running = worker; }
        return running == null || running.join(timeout);
    }

    private synchronized Target awaitTarget(Target previous) throws InterruptedException {
        while (!closed && (desired == null || desired == previous)) { wait(); }
        return desired;
    }

    private void run() {
        Target previous = null;
        try {
            while (!closed) {
                Target target = awaitTarget(previous);
                if (target == null) { break; }
                try (WatchService service = target.directory().getFileSystem().newWatchService()) {
                    new Registration(target, service).monitor();
                } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                    publish(target, Status.UNAVAILABLE, null);
                } finally {
                    registrations = 0;
                }
                previous = target;
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean current(Target target) { return !closed && desired == target; }

    private void publish(Target target, Status status, DirectoryEvents.Batch changes) {
        if (current(target)) { receiver.accept(new Notice(target, status, changes)); }
    }

    private final class Registration {
        private final Target target;
        private final WatchService service;
        private final DirectoryEvents events = new DirectoryEvents();
        private WatchKey directory;
        private WatchKey parent;
        private DirectoryIdentity identity;
        private Status status;
        private long nextCheck;

        Registration(Target target, WatchService service) {
            this.target = target;
            this.service = service;
        }

        void monitor() throws InterruptedException {
            bind();
            while (current(target)) {
                long now = System.nanoTime();
                if (now - nextCheck >= 0) { check(); }
                var batch = events.take(System.nanoTime());
                if (batch != null) { publish(target, status, batch); }
                long wait = events.waitNanos(System.nanoTime(), POLL);
                var key = service.poll(wait, TimeUnit.NANOSECONDS);
                if (key != null && current(target)) { receive(key); }
            }
            clearDirectory();
            clearParent();
        }

        private void check() {
            if (status == Status.ACTIVE) {
                try {
                    if (identity.equals(identities.read(target.directory()))) {
                        nextCheck = System.nanoTime() + CHECK;
                        return;
                    }
                } catch (IOException | SecurityException inaccessible) {
                    // Re-register against the path to distinguish a replacement from loss of access.
                }
                clearDirectory();
            }
            bind();
        }

        private void bind() {
            Path path = target.directory();
            clearParent();
            try {
                if (path.getParent() != null) {
                    parent = path.getParent().register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                }
                var before = identities.read(path);
                if (!before.known()) { throw new IOException("Directory identity is unavailable"); }
                directory = path.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                var after = identities.read(path);
                if (!before.equals(after)) { throw new IOException("Directory changed during registration"); }
                identity = after;
                nextCheck = System.nanoTime() + CHECK;
                setStatus(Status.ACTIVE, true);
            } catch (NoSuchFileException | NotDirectoryException missing) {
                clearDirectory();
                setStatus(Status.WAITING, false);
                nextCheck = System.nanoTime() + RETRY;
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                clearDirectory();
                setStatus(Status.UNAVAILABLE, false);
                nextCheck = System.nanoTime() + RETRY;
            }
        }

        private void receive(WatchKey key) {
            boolean relevantKey = key == directory || key == parent;
            boolean rebind = false;
            for (var event : key.pollEvents()) {
                if (!relevantKey) { continue; }
                if (event.kind() == OVERFLOW) {
                    events.record(null, System.nanoTime());
                    if (key == parent) { rebind = true; }
                } else if (event.context() instanceof Path child) {
                    if (key == directory) {
                        var entry = target.directory().resolve(child);
                        if (!exclude.test(entry)) { events.record(entry, System.nanoTime()); }
                    } else if (child.equals(target.directory().getFileName()) && event.kind() != ENTRY_MODIFY) {
                        events.record(null, System.nanoTime());
                        rebind = true;
                    }
                }
            }
            if (!key.reset() && relevantKey) {
                if (key == parent) { parent = null; }
                rebind = true;
            }
            if (rebind) {
                clearDirectory();
                bind();
            }
        }

        private void clearDirectory() {
            if (directory != null) { directory.cancel(); }
            directory = null;
            identity = null;
        }

        private void clearParent() {
            if (parent != null) { parent.cancel(); }
            parent = null;
        }

        private void setStatus(Status value, boolean catchUp) {
            registrations = (directory == null ? 0 : 1) + (parent == null || !parent.isValid() ? 0 : 1);
            if (status == value && !catchUp) { return; }
            status = value;
            // The next full read also covers events already recorded before rebinding.
            events.clear();
            var batch = new DirectoryEvents.Batch(Set.of(), true, System.nanoTime());
            publish(target, value, batch);
        }
    }
}
