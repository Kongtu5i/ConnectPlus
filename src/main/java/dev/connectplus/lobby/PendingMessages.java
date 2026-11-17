package dev.connectplus.lobby;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One-shot, bounded messages. Removing a message also removes its lifecycle callbacks. */
final class PendingMessages<T> {
    private static final int MAX_PENDING = 4096;
    private static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(2);
    private record Key(UUID player, Channel owner) {
        @Override public boolean equals(Object other) {
            return other instanceof Key key && player.equals(key.player) && owner == key.owner;
        }
        @Override public int hashCode() { return 31 * player.hashCode() + System.identityHashCode(owner); }
    }
    private final Map<Key, Entry> entries = new LinkedHashMap<>();

    private final class Entry implements ChannelFutureListener, Runnable {
        final UUID player;
        final T value;
        final Channel owner;
        final Key key;
        final long expires = System.nanoTime() + TTL_NANOS;
        ScheduledFuture<?> expiry;

        Entry(UUID player, T value, Channel owner) {
            this.player = player; this.value = value; this.owner = owner; this.key = new Key(player, owner);
        }
        @Override public void operationComplete(ChannelFuture ignored) { run(); }
        @Override public void run() {
            synchronized (PendingMessages.this) {
                entries.remove(key, this);
                detach();
            }
        }
        void detach() {
            if (owner != null) owner.closeFuture().removeListener(this);
            if (expiry != null) { expiry.cancel(false); expiry = null; }
        }
    }

    synchronized Runnable post(UUID player, T value, Channel owner) {
        prune();
        if (owner != null && !owner.isActive()) return () -> {};
        Key key = new Key(player, owner);
        Entry previous = entries.remove(key);
        if (previous != null) previous.detach();
        if (entries.size() >= MAX_PENDING) return () -> {};
        Entry entry = new Entry(player, value, owner);
        entries.put(key, entry);
        if (owner != null) {
            try {
                entry.expiry = owner.eventLoop().schedule(entry, 2, TimeUnit.MINUTES);
                owner.closeFuture().addListener(entry);
            } catch (RuntimeException error) { entry.run(); throw error; }
        }
        return entry;
    }

    /** Legacy diagnostic callers without a connection take the most recently posted matching value. */
    synchronized T consume(UUID player) {
        prune();
        Entry latest = null;
        for (Entry entry : entries.values()) if (entry.player.equals(player)) latest = entry;
        return take(latest);
    }
    synchronized T consume(UUID player, Channel owner) {
        prune();
        Entry entry = entries.get(new Key(player, owner));
        if (entry == null && owner != null) entry = entries.get(new Key(player, null));
        return take(entry);
    }
    private T take(Entry entry) {
        if (entry == null) return null;
        entries.remove(entry.key);
        entry.detach();
        return entry.owner == null || entry.owner.isActive() ? entry.value : null;
    }
    synchronized int size() { prune(); return entries.size(); }
    synchronized void clear() { entries.values().forEach(Entry::detach); entries.clear(); }
    private void prune() {
        long now = System.nanoTime();
        entries.values().removeIf(entry -> {
            if (now - entry.expires < 0 && (entry.owner == null || entry.owner.isActive())) return false;
            entry.detach(); return true;
        });
    }
}
