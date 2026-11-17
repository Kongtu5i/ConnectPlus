package dev.connectplus.resources;

import java.util.concurrent.*;

/** Blocking work is bounded and rejected explicitly, never run on a network caller. */
public final class ResourceExecutors {
    private ResourceExecutors() { }
    public static ExecutorService single(String name) { return single(name, 256); }
    public static ExecutorService single(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), runnable -> {
            Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
