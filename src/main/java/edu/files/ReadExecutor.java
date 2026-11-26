package edu.files;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ReadExecutor implements AutoCloseable {
    private final ThreadPoolExecutor listings = pool("file-manager-list", 2);
    private final ThreadPoolExecutor details = pool("file-manager-detail", 24);

    private static ThreadPoolExecutor pool(String name, int capacity) {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), work -> {
            var thread = new Thread(work, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    boolean listing(FutureTask<?> task) { return submit(listings, task); }
    boolean detail(FutureTask<?> task) { return submit(details, task); }

    private static boolean submit(ThreadPoolExecutor executor, FutureTask<?> task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    void cancel(FutureTask<?> task) {
        listings.remove(task);
        details.remove(task);
        task.cancel(true);
    }

    @Override
    public void close() {
        listings.shutdownNow().forEach(work -> ((FutureTask<?>) work).cancel(false));
        details.shutdownNow().forEach(work -> ((FutureTask<?>) work).cancel(false));
    }
}
