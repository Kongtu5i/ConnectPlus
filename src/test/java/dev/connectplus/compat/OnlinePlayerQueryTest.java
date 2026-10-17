package dev.connectplus.compat;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.access.AccessConnectionRegistry;
import dev.connectplus.access.AccessKey;
import dev.connectplus.access.AccessSubject;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.testutil.OnlineConnection;
import net.raphimc.netminecraft.constants.ConnectionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OnlinePlayerQueryTest {
    private String oldLanguage;

    @org.junit.jupiter.api.BeforeEach
    void useChineseMessages() {
        this.oldLanguage = dev.connectplus.config.CPConfig.language;
        dev.connectplus.config.CPConfig.language = "zh";
    }

    @org.junit.jupiter.api.AfterEach
    void restoreLanguage() {
        dev.connectplus.config.CPConfig.language = this.oldLanguage;
    }

    @Test
    void originalBedrockVersionWinsOverTheTranslatedJavaProtocol() {
        final AccessConnectionRegistry registry = new AccessConnectionRegistry();
        final UUID wire = UUID.randomUUID();
        final String xuid = "2535429616021489";
        try (final OnlineConnection pc = new OnlineConnection(registry,
                new AccessSubject(AccessKey.ClientType.BEDROCK, "Bedrock", wire, xuid),
                ClientIdentity.verifiedBedrock(wire, "Bedrock", xuid, UUID.randomUUID(), "s"), ProtocolVersion.v1_20_2)) {
            final OnlinePlayerQuery query = new OnlinePlayerQuery(() -> registry, () -> null,
                    (uuid, id) -> wire.equals(uuid) && xuid.equals(id) ? "1.21.50" : null);
            assertEquals("基岩 1.21.50", query.snapshot().players().get(0).clientVersion());
            final OnlinePlayerQuery missing = new OnlinePlayerQuery(() -> registry, () -> null, (uuid, id) -> null);
            assertEquals("基岩（版本未知）", missing.snapshot().players().get(0).clientVersion());
            final OnlinePlayerQuery broken = new OnlinePlayerQuery(() -> registry, () -> null,
                    (uuid, id) -> { throw new IllegalStateException(); });
            assertEquals("基岩（版本未知）", broken.snapshot().players().get(0).clientVersion());
            assertTrue(broken.snapshot().available(), "Missing display metadata does not make a live connection disappear");
        }
    }

    @Test
    void unknownPlatformIsNotMislabelledAsJava() {
        final AccessConnectionRegistry registry = new AccessConnectionRegistry();
        final UUID uuid = UUID.randomUUID();
        try (final OnlineConnection pc = new OnlineConnection(registry, new AccessSubject(null, "Unknown", uuid, null),
                ClientIdentity.unverified(uuid, "Unknown"), ProtocolVersion.v1_20_2)) {
            final var snapshot = new OnlinePlayerQuery(() -> registry, () -> null).snapshot();
            assertEquals("未知平台（版本未知）", snapshot.players().get(0).clientVersion());
            dev.connectplus.config.CPConfig.language = "en";
            assertEquals("Unknown platform (version unknown)",
                    new OnlinePlayerQuery(() -> registry, () -> null).snapshot().players().get(0).clientVersion());
        }
    }

    @Test
    void englishBedrockAndUnavailableMetadataUseTheConfiguredLanguage() {
        dev.connectplus.config.CPConfig.language = "en";
        final AccessConnectionRegistry registry = new AccessConnectionRegistry();
        final UUID wire = UUID.randomUUID();
        final String xuid = "2535429616021489";
        try (final OnlineConnection pc = new OnlineConnection(registry,
                new AccessSubject(AccessKey.ClientType.BEDROCK, "BedrockPlayer", wire, xuid),
                ClientIdentity.verifiedBedrock(wire, "BedrockPlayer", xuid, UUID.randomUUID(), "s"), ProtocolVersion.v1_20_2)) {
            pc.address = null;
            final var known = new OnlinePlayerQuery(() -> registry, () -> null, (uuid, id) -> "1.21.50").snapshot().players().get(0);
            assertEquals("Bedrock 1.21.50", known.clientVersion());
            assertEquals("Other server", known.location().label());
            assertEquals("Unknown", known.serverVersion());
            assertEquals("Unavailable", known.currentServer());
            final var unknown = new OnlinePlayerQuery(() -> registry, () -> null, (uuid, id) -> null).snapshot().players().get(0);
            assertEquals("Bedrock (version unknown)", unknown.clientVersion());
        }
    }

    @Test
    void sameNameConnectionsStayDistinctAndPendingDeniedOrClosedConnectionsAreExcluded() {
        final AccessConnectionRegistry registry = new AccessConnectionRegistry();
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        try (final OnlineConnection a = new OnlineConnection(registry, new AccessSubject(AccessKey.ClientType.JAVA, "Same", first, null),
                ClientIdentity.verifiedJava(first, "Same", first), ProtocolVersion.v1_20);
             final OnlineConnection b = new OnlineConnection(registry, new AccessSubject(AccessKey.ClientType.JAVA, "Same", second, null),
                     ClientIdentity.verifiedJava(second, "Same", second), ProtocolVersion.v1_20_2)) {
            final OnlinePlayerQuery query = new OnlinePlayerQuery(() -> registry, () -> null);
            assertEquals(2, query.snapshot().players().size());
            registry.mark(a.connectionId, AccessConnectionRegistry.Status.PENDING);
            assertEquals(List.of(b.connectionId), query.snapshot().players().stream().map(OnlinePlayerQuery.Player::connectionId).toList());
            registry.mark(a.connectionId, AccessConnectionRegistry.Status.DENIED);
            b.client.close();
            assertTrue(query.snapshot().players().isEmpty());
        }
    }

    @Test
    void unfinishedBackendLoginRemainsOnlineButIsNotShownAsAConnectedServer() {
        final AccessConnectionRegistry registry = new AccessConnectionRegistry();
        final UUID uuid = UUID.randomUUID();
        try (final OnlineConnection pc = new OnlineConnection(registry, new AccessSubject(AccessKey.ClientType.JAVA, "Joining", uuid, null),
                ClientIdentity.verifiedJava(uuid, "Joining", uuid), ProtocolVersion.v1_20_2)) {
            pc.setP2sConnectionState(ConnectionState.LOGIN);
            assertEquals(OnlinePlayerQuery.Location.SWITCHING,
                    new OnlinePlayerQuery(() -> registry, () -> null).snapshot().players().get(0).location());
        }
    }

    @Test
    void missingHostConnectionMarksTheSnapshotIncompleteRatherThanOffline() {
        final AccessConnectionRegistry registry = new AccessConnectionRegistry();
        final UUID uuid = UUID.randomUUID();
        try (final OnlineConnection pc = new OnlineConnection(registry, new AccessSubject(AccessKey.ClientType.JAVA, "Missing", uuid, null),
                ClientIdentity.verifiedJava(uuid, "Missing", uuid), ProtocolVersion.v1_20_2)) {
            pc.client.attr(net.raphimc.viaproxy.proxy.session.ProxyConnection.PROXY_CONNECTION_ATTRIBUTE_KEY).set(null);
            assertFalse(new OnlinePlayerQuery(() -> registry, () -> null).snapshot().available());
        }
    }
}
