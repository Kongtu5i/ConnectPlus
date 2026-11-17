package dev.connectplus.resources;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Canceled but uninterruptible authentication still occupies a slot until it really exits. */
public final class AccountTasks {
    private final int limit;
    private final Set<Task> active = new HashSet<>();
    private boolean stopped;
    public AccountTasks(int limit) { this.limit = limit; }
    public synchronized void enable() { stopped = false; }
    public synchronized int activeCount() { return active.size(); }
    public synchronized void shutdown() { stopped = true; List.copyOf(active).forEach(Task::cancel); }
    public synchronized boolean start(Channel owner, String name, Runnable operation) {
        if (stopped || !owner.isActive() || active.size() >= limit) return false;
        Task task = new Task(owner, name, operation); active.add(task);
        try {
            task.timeout = owner.eventLoop().schedule(task::cancel, 15, TimeUnit.MINUTES);
            owner.closeFuture().addListener(task);
            task.thread.start();
            return true;
        } catch (RuntimeException | Error error) { task.detach(); active.remove(task); throw error; }
    }
    private final class Task implements ChannelFutureListener {
        final Channel owner;
        final Thread thread;
        ScheduledFuture<?> timeout;
        volatile boolean canceled;
        Task(Channel owner, String name, Runnable operation) {
            this.owner = owner;
            thread = new Thread(() -> {
                try { if (!canceled) operation.run(); }
                finally { synchronized (AccountTasks.this) { detach(); active.remove(this); } }
            }, name);
            thread.setDaemon(true);
        }
        @Override public void operationComplete(ChannelFuture ignored) { cancel(); }
        void cancel() { synchronized (AccountTasks.this) { canceled = true; thread.interrupt(); detach(); } }
        void detach() {
            owner.closeFuture().removeListener(this);
            if (timeout != null) { timeout.cancel(false); timeout = null; }
        }
    }
}
