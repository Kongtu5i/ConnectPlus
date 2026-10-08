package dev.connectplus.access;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccessConnectionRegistryTest {

    @Test
    void simultaneousAllowAndDenyAlwaysEndsDenied() throws Exception {
        final var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5000; round++) {
                final UUID id = UUID.randomUUID();
                final EmbeddedChannel channel = new EmbeddedChannel();
                this.registry.register(id, channel);
                final var start = new java.util.concurrent.CyclicBarrier(2);
                final var deny = workers.submit(() -> { start.await();
                    this.registry.mark(id, AccessConnectionRegistry.Status.DENIED); return null; });
                final var allow = workers.submit(() -> { start.await();
                    this.registry.mark(id, AccessConnectionRegistry.Status.ALLOWED); return null; });
                deny.get(3, java.util.concurrent.TimeUnit.SECONDS);
                allow.get(3, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    assertEquals(AccessConnectionRegistry.Status.DENIED, this.registry.byChannel(channel).status());
                } finally {
                    this.registry.remove(id, channel);
                    channel.finishAndReleaseAll();
                }
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private final AccessConnectionRegistry registry = new AccessConnectionRegistry();

    private static AccessSubject javaSubject(final UUID uuid) {
        return new AccessSubject(AccessKey.ClientType.JAVA, "Java", uuid, null);
    }

    @Test
    void registeredConnectionStartsPendingWithNoSubject() {
        final UUID id = UUID.randomUUID();
        final EmbeddedChannel channel = new EmbeddedChannel();

        this.registry.register(id, channel);

        assertEquals(1, this.registry.snapshot().size());
        final AccessConnectionRegistry.Connection connection = this.registry.snapshot().get(0);
        assertEquals(id, connection.connectionId());
        assertSame(channel, connection.c2p());
        assertNull(connection.subject());
        assertEquals(AccessConnectionRegistry.Status.PENDING, connection.status());
    }

    @Test
    void registrySurvivesBackendSwitchAndRemovesOnlyExactChannel() {
        // Two distinct connections; "backend switches" are invisible to the registry —
        // only an explicit removal with the ORIGINAL channel can drop an entry.
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final EmbeddedChannel firstChannel = new EmbeddedChannel();
        final EmbeddedChannel secondChannel = new EmbeddedChannel();
        this.registry.register(first, firstChannel);
        this.registry.register(second, secondChannel);
        this.registry.update(first, javaSubject(UUID.randomUUID()));

        // A wrong channel must not remove the entry (same id, different channel object).
        this.registry.remove(first, secondChannel);
        assertEquals(2, this.registry.snapshot().size());

        // A wrong id must not remove the entry either.
        this.registry.remove(second, firstChannel);
        assertEquals(2, this.registry.snapshot().size());

        // The subject recorded before a (simulated) switch is still there afterwards.
        assertNotNull(this.registry.snapshot().stream()
                .filter(c -> c.connectionId().equals(first)).findFirst().orElseThrow().subject());

        // Only the exact (id, channel) pair removes.
        this.registry.remove(first, firstChannel);
        assertEquals(1, this.registry.snapshot().size());
        assertEquals(second, this.registry.snapshot().get(0).connectionId());
    }

    @Test
    void updateReplacesSubjectAndBumpsGeneration() {
        final UUID id = UUID.randomUUID();
        final EmbeddedChannel channel = new EmbeddedChannel();
        this.registry.register(id, channel);
        final long before = this.registry.snapshot().get(0).generation();

        final AccessSubject pending = javaSubject(UUID.randomUUID());
        this.registry.update(id, pending);
        final AccessConnectionRegistry.Connection afterFirst = this.registry.snapshot().get(0);
        assertSame(pending, afterFirst.subject());
        assertEquals(AccessConnectionRegistry.Status.PENDING, afterFirst.status());
        assertTrue(afterFirst.generation() > before);

        final AccessSubject resolved = javaSubject(UUID.randomUUID());
        this.registry.update(id, resolved);
        assertSame(resolved, this.registry.snapshot().get(0).subject());
    }

    @Test
    void deniedIsTerminalAndLateUpdatesCannotReallow() {
        final UUID id = UUID.randomUUID();
        final EmbeddedChannel channel = new EmbeddedChannel();
        this.registry.register(id, channel);
        this.registry.update(id, javaSubject(UUID.randomUUID()));
        this.registry.mark(id, AccessConnectionRegistry.Status.DENIED);

        // A late identity result (or a later allow marker) must not resurrect it.
        this.registry.update(id, javaSubject(UUID.randomUUID()));
        this.registry.mark(id, AccessConnectionRegistry.Status.ALLOWED);
        assertEquals(AccessConnectionRegistry.Status.DENIED, this.registry.snapshot().get(0).status());
    }

    @Test
    void allowedStatusIsRecorded() {
        final UUID id = UUID.randomUUID();
        final EmbeddedChannel channel = new EmbeddedChannel();
        this.registry.register(id, channel);
        this.registry.update(id, javaSubject(UUID.randomUUID()));
        this.registry.mark(id, AccessConnectionRegistry.Status.ALLOWED);
        assertEquals(AccessConnectionRegistry.Status.ALLOWED, this.registry.snapshot().get(0).status());
    }

    @Test
    void snapshotIsAnIndependentCopy() {
        final UUID id = UUID.randomUUID();
        final EmbeddedChannel channel = new EmbeddedChannel();
        this.registry.register(id, channel);

        final var snapshot = this.registry.snapshot();
        this.registry.register(UUID.randomUUID(), new EmbeddedChannel());
        assertEquals(1, snapshot.size(), "the snapshot never changes under the reader");
        assertEquals(2, this.registry.snapshot().size());
    }

    @Test
    void findByChannelResolvesTheEntry() {
        final UUID id = UUID.randomUUID();
        final EmbeddedChannel channel = new EmbeddedChannel();
        this.registry.register(id, channel);
        assertEquals(id, this.registry.byChannel(channel).connectionId());
        assertNull(this.registry.byChannel(new EmbeddedChannel()));
    }

    @Test
    void registeringTheSameChannelTwiceKeepsOneEntry() {
        final UUID id = UUID.randomUUID();
        final EmbeddedChannel channel = new EmbeddedChannel();
        this.registry.register(id, channel);
        this.registry.register(id, channel);
        assertEquals(1, this.registry.snapshot().size());
    }
}
