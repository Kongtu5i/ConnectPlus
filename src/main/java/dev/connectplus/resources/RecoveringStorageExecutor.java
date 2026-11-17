package dev.connectplus.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** At most two IO domains, including threads which ignore interruption after a timeout. */
public final class RecoveringStorageExecutor extends AbstractExecutorService {
    private final String name;
    private final int maxDomains;
    private final List<ExecutorService> domains = new ArrayList<>();
    private ExecutorService current;
    private boolean stopped;
    public RecoveringStorageExecutor(String name, int maxDomains) { this.name = name; this.maxDomains = maxDomains; }
    private void prune() { domains.removeIf(ExecutorService::isTerminated); }
    @Override public synchronized void execute(Runnable command) {
        currentDomain().execute(command);
    }
    public synchronized ExecutorService currentDomain() {
        prune();
        if (stopped) throw new RejectedExecutionException("Storage stopped");
        if (current == null) {
            if (domains.size() >= maxDomains) throw new RejectedExecutionException("Storage is still blocked");
            current = ResourceExecutors.single(name); domains.add(current);
        }
        return current;
    }
    public void rotate() {
        ExecutorService expected;
        synchronized (this) { expected = current; }
        rotate(expected);
    }
    public void rotate(ExecutorService expected) {
        List<Runnable> dropped = List.of();
        synchronized (this) {
            if (current != expected) return; // A late timeout cannot retire the replacement.
            if (current != null) { dropped = current.shutdownNow(); current = null; }
            prune();
        }
        discard(dropped); // Completing futures may enter the coordinator; do not hold this monitor.
    }
    public synchronized int workerDomainCount() { prune(); return domains.size(); }
    @Override public synchronized void shutdown() { stopped = true; domains.forEach(ExecutorService::shutdown); }
    @Override public List<Runnable> shutdownNow() {
        List<Runnable> dropped = new ArrayList<>();
        synchronized (this) {
            stopped = true;
            domains.forEach(domain -> dropped.addAll(domain.shutdownNow()));
        }
        discard(dropped); return dropped;
    }
    private static void discard(List<Runnable> tasks) {
        tasks.forEach(task -> { if (task instanceof DiscardableTask pending) pending.discard(); });
    }
    @Override public synchronized boolean isShutdown() { return stopped; }
    @Override public synchronized boolean isTerminated() { prune(); return stopped && domains.isEmpty(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<ExecutorService> snapshot;
        synchronized (this) { snapshot = List.copyOf(domains); }
        for (ExecutorService domain : snapshot) {
            if (!domain.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) return false;
        }
        return isTerminated();
    }
}
