package dev.connectplus.access;

import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.ClientPlatformInspector;
import dev.connectplus.identity.ClientIdentity;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AccessIdentitySourceTest {

    private static final UUID WIRE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String XUID = "2530000000000001";

    private final AccessIdentitySource source = new AccessIdentitySource(new ClientPlatformInspector());

    private static ProxyConnection proxyOf(final EmbeddedChannel client) {
        return new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
    }

    @Test
    void verifiedJavaResolvesImmediatelyWithEntryUuid() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY)
                .set(ClientIdentity.verifiedJava(WIRE, "Java", WIRE));

        final AccessSubject subject = this.source.resolve(proxyOf(channel)).toCompletableFuture().join();
        assertEquals(AccessKey.ClientType.JAVA, subject.clientType());
        assertEquals(WIRE, subject.entryUuid());
        assertEquals("Java", subject.name());
        assertNull(subject.xuid());
        assertEquals(AccessKey.javaUuid(WIRE), subject.key());
    }

    @Test
    void verifiedBedrockCarriesXuid() throws Exception {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(
                ClientIdentity.verifiedBedrock(WIRE, "Bedrock", XUID, UUID.randomUUID(), UUID.randomUUID().toString()));

        final AccessSubject subject = this.source.resolve(proxyOf(channel)).toCompletableFuture().join();
        assertEquals(AccessKey.ClientType.BEDROCK, subject.clientType());
        assertEquals(XUID, subject.xuid());
        assertEquals(AccessKey.bedrockXuid(XUID), subject.key());
    }

    @Test
    void offlineJavaDoesNotBecomeBedrockWhenBridgeUnavailable() {
        // UNVERIFIED, no bridge answer, no session signal: the platform is unknown and
        // unknown is never bedrock — the subject keeps the wire UUID as a java-shaped key.
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.unverified(WIRE, "Java"));

        final AccessSubject subject = new AccessIdentitySource(new ClientPlatformInspector(() -> true))
                .resolve(proxyOf(channel)).toCompletableFuture().join();
        assertEquals(AccessKey.ClientType.JAVA, subject.clientType());
        assertEquals(WIRE, subject.entryUuid());
        assertNull(subject.xuid());
    }

    @Test
    void knownBedrockMissingXuidKeepsTheConfirmedPlatform() {
        // The bridge matched a bedrock session on the raw address pair but its identity
        // was not trustworthy: platform BEDROCK, XUID unavailable — the wire UUID is
        // display-only and never becomes the matching key.
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.unverified(WIRE, "Bedrock"));
        channel.attr(CPAttributeKeys.PLATFORM_CLASSIFICATION)
                .set(ClientPlatformInspector.Classification.BEDROCK);

        final AccessSubject subject = this.source.resolve(proxyOf(channel)).toCompletableFuture().join();
        assertEquals(AccessKey.ClientType.BEDROCK, subject.clientType());
        assertNull(subject.xuid());
        assertNull(subject.key(), "no xuid means no primary key");
        assertEquals(WIRE, subject.entryUuid(), "the wire uuid stays for display, not matching");
    }

    @Test
    void unresolvedPlatformCannotMatchAJavaWhitelistKey() throws Exception {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.unverified(WIRE, "Unknown"));
        // The default inspector has no evidence from a running host in this fixture.
        final AccessSubject subject = this.source.resolve(proxyOf(channel)).toCompletableFuture().join();
        final var entries = java.util.Map.of(AccessKey.javaUuid(WIRE),
                new AccessEntry(AccessKey.ClientType.JAVA, "Java", WIRE, null));
        final var list = new AccessListStore.Snapshot(1, true, entries);
        final var empty = new AccessListStore.Snapshot(1, true, java.util.Map.of());
        assertNull(subject.key(), "unknown platform must not manufacture a Java identifier");
        assertEquals(AccessPolicy.Reason.IDENTIFIER_UNAVAILABLE, AccessPolicy.evaluate(
                new AccessPolicy.State(true, false, list, empty, java.util.Set.of(), 1), subject).reason());
        assertEquals(AccessPolicy.Reason.IDENTIFIER_UNAVAILABLE, AccessPolicy.evaluate(
                new AccessPolicy.State(false, true, list, empty, java.util.Set.of(), 1), subject).reason());
        assertTrue(AccessPolicy.evaluate(new AccessPolicy.State(false, false, list, empty,
                java.util.Set.of(), 1), subject).allowed());
    }

    @Test
    void pendingResolutionAwaitsTheControlledFuture() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.unverified(WIRE, "Bedrock"));
        final CompletableFuture<ClientIdentity> controlled = new CompletableFuture<>();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY_RESOLUTION).set(controlled);

        final CompletableFuture<AccessSubject> resolution =
                this.source.resolve(proxyOf(channel)).toCompletableFuture();
        assertFalse(resolution.isDone(), "an unresolved identity keeps the subject pending");

        controlled.complete(ClientIdentity.verifiedBedrock(WIRE, "Bedrock", XUID,
                UUID.randomUUID(), UUID.randomUUID().toString()));
        final AccessSubject subject = resolution.join();
        assertEquals(AccessKey.ClientType.BEDROCK, subject.clientType());
        assertEquals(XUID, subject.xuid());
    }

    @Test
    void pendingResolutionWithoutIdentityPreservesUnknownPlatform() {
        // The capture's resolution future completed with null (e.g. a closed channel
        // before any identity was established): the subject still exists, java-shaped.
        final EmbeddedChannel channel = new EmbeddedChannel();
        final CompletableFuture<ClientIdentity> controlled = new CompletableFuture<>();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY_RESOLUTION).set(controlled);
        final ProxyConnection connection = proxyOf(channel);
        connection.setGameProfile(new com.mojang.authlib.GameProfile(WIRE, "WireName"));

        final CompletableFuture<AccessSubject> resolution = this.source.resolve(connection).toCompletableFuture();
        controlled.complete(null);
        final AccessSubject subject = resolution.join();
        assertNull(subject.clientType());
        assertNull(subject.key());
        assertEquals(WIRE, subject.entryUuid(), "the wire uuid from the profile is the fallback identity");
        assertEquals("WireName", subject.name());
    }
}
