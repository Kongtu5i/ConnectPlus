package dev.connectplus.access;

import io.netty.channel.Channel;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ConnectPlus' own registry of every player-to-proxy connection, keyed by the
 * per-connection id and bound to the exact c2p channel. The existing lobby
 * session tables cannot see players that left the lobby or bypassed it, so the
 * access checks run against this registry: entries survive server switches
 * (the c2p channel does) and only an exact (id, channel) match removes one.
 */
public final class AccessConnectionRegistry {

    /** The admission lifecycle of one connection. */
    public enum Status { PENDING, ALLOWED, DENIED }

    /**
     * One registered connection. The subject is null while the identity is
     * still resolving (PENDING); the generation bumps on every published
     * change so observers can detect replacements.
     */
    public record Connection(UUID connectionId, Channel c2p, AccessSubject subject, Status status, long generation) {
    }

    private static final class Entry {
        final Channel c2p;
        volatile AccessSubject subject;
        volatile Status status;
        volatile long generation;

        Entry(final Channel c2p) {
            this.c2p = c2p;
            this.status = Status.PENDING;
            this.generation = 0;
        }
    }

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();

    /** Registers a fresh connection as PENDING; registering the same pair again is a no-op. */
    public void register(final UUID connectionId, final Channel c2p) {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(c2p, "c2p");
        this.entries.computeIfAbsent(connectionId, id -> new Entry(c2p));
    }

    /** Publishes the resolved subject; a denied connection never accepts updates. */
    public void update(final UUID connectionId, final AccessSubject subject) {
        Objects.requireNonNull(subject, "subject");
        this.entries.computeIfPresent(connectionId, (id, entry) -> {
            if (entry.status != Status.DENIED) {
                entry.subject = subject;
                entry.generation = this.generations.incrementAndGet();
            }
            return entry;
        });
    }

    /** Moves the status; DENIED is terminal and later marks cannot change it. */
    public void mark(final UUID connectionId, final Status status) {
        Objects.requireNonNull(status, "status");
        this.entries.computeIfPresent(connectionId, (id, entry) -> {
            if (entry.status != Status.DENIED) {
                entry.status = status;
                entry.generation = this.generations.incrementAndGet();
            }
            return entry;
        });
    }

    /** Removes the entry only when the channel still matches the registered one. */
    public boolean remove(final UUID connectionId, final Channel c2p) {
        final Entry entry = this.entries.get(connectionId);
        if (entry == null || entry.c2p != c2p) {
            return false;
        }
        return this.entries.remove(connectionId, entry);
    }

    /** An immutable snapshot of all live connections. */
    public List<Connection> snapshot() {
        return this.entries.entrySet().stream()
                .map(e -> new Connection(e.getKey(), e.getValue().c2p, e.getValue().subject,
                        e.getValue().status, e.getValue().generation))
                .toList();
    }

    /** The entry of this exact channel, or null. */
    public Connection byChannel(final Channel c2p) {
        for (final var e : this.entries.entrySet()) {
            if (e.getValue().c2p == c2p) {
                return new Connection(e.getKey(), e.getValue().c2p, e.getValue().subject,
                        e.getValue().status, e.getValue().generation);
            }
        }
        return null;
    }
}
