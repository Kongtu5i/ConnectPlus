package dev.connectplus.access;

import com.mojang.authlib.GameProfile;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.ClientPlatformInspector;
import dev.connectplus.identity.ClientIdentity;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The admission pipeline over the real service, registry, identity source and
 * gate: entry checks, online rule updates and the denial terminality. Fixture
 * subjects use controlled futures and explicit c2p channels; real official-host
 * results are a separate (task 8) acceptance.
 */
class AccessAdmissionIT {

    private static final UUID JAVA_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String XUID = "2530000000000001";

    @TempDir
    Path dir;

    private AccessService service;
    private AccessConnectionRegistry registry;
    private AccessIdentitySource identitySource;
    private AccessGate gate;

    @BeforeEach
    void setUp() throws Exception {
        // Both lists exist and are valid from the start; the strict startup load
        // succeeds and mutations have data to work on.
        java.nio.file.Files.writeString(this.dir.resolve("whitelist.json"),
                "{\"schemaVersion\": 1, \"players\": []}");
        java.nio.file.Files.writeString(this.dir.resolve("blacklist.json"),
                "{\"schemaVersion\": 1, \"players\": []}");
        this.service = new AccessService(
                new AccessListStore(this.dir.resolve("whitelist.json")),
                new AccessListStore(this.dir.resolve("blacklist.json")),
                new AccessService.ConfiguredFlags(true, true), // both lists on: strictest mix
                Runnable::run);
        this.service.initialize().toCompletableFuture().join();
        this.registry = new AccessConnectionRegistry();
        this.identitySource = new AccessIdentitySource(new ClientPlatformInspector());
        // Tests run rechecks inline; production schedules on the owning event loop.
        this.gate = new AccessGate(this.service, this.registry, this.identitySource,
                (channel, task) -> task.run());
        this.gate.install();
    }

    @AfterEach
    void tearDown() {
        AccessGate.uninstall();
    }

    private ProxyConnection registerProxy(final EmbeddedChannel channel) {
        final ProxyConnection pc = new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), channel);
        // What the official Client2ProxyChannelInitializer does for live connections:
        // publish the ProxyConnection on the channel so ProxyConnection.fromChannel works.
        channel.attr(ProxyConnection.PROXY_CONNECTION_ATTRIBUTE_KEY).set(pc);
        final UUID connectionId = UUID.randomUUID();
        channel.attr(CPAttributeKeys.CONNECTION_ID).set(connectionId);
        this.registry.register(connectionId, channel);
        channel.closeFuture().addListener(f -> this.registry.remove(connectionId, channel));
        return pc;
    }

    private static void setVerifiedJava(final EmbeddedChannel channel, final UUID uuid) {
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.verifiedJava(uuid, "Java", uuid));
    }

    private AccessSubject javaSubject() {
        return new AccessSubject(AccessKey.ClientType.JAVA, "Java", JAVA_UUID, null);
    }

    // ---- entry checks -------------------------------------------------------

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void allowedSubjectWithoutPrimaryKeyDoesNotAddAnExtraAccessPermissionGate(boolean confirmedBedrock) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ProxyConnection pc = this.registerProxy(channel);
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.unverified(JAVA_UUID, "Unverified"));
        if (confirmedBedrock) channel.attr(CPAttributeKeys.PLATFORM_CLASSIFICATION)
                .set(ClientPlatformInspector.Classification.BEDROCK);
        this.service.setEnabled(AccessPolicy.Kind.WHITELIST, false).toCompletableFuture().join();
        this.service.setEnabled(AccessPolicy.Kind.BLACKLIST, confirmedBedrock).toCompletableFuture().join();
        assertTrue(this.gate.admit(pc).toCompletableFuture().join().allowed());
        assertTrue(this.gate.protectedOperationsAllowed(channel),
                "resolved and allowed subjects may use ordinary switches; authentication is checked separately");
        assertEquals(ClientIdentity.Kind.UNVERIFIED, channel.attr(CPAttributeKeys.CLIENT_IDENTITY).get().kind());
        channel.finishAndReleaseAll();
    }

    @Test
    void nameMetadataFailureCannotBypassAnAdmissionRefusal() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ProxyConnection pc = this.registerProxy(channel);
        setVerifiedJava(channel, JAVA_UUID);
        this.gate.setSubjectObserver(subject -> { throw new IllegalStateException("name index write rejected"); });
        assertEquals(AccessPolicy.Reason.NOT_WHITELISTED,
                this.gate.admit(pc).toCompletableFuture().join().reason());
        assertFalse(channel.isActive());
    }

    @Test
    void allowedConfirmedBedrockWithoutXuidLogsTheSkippedCheck() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ProxyConnection pc = this.registerProxy(channel);
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.unverified(JAVA_UUID, "Bedrock"));
        channel.attr(CPAttributeKeys.PLATFORM_CLASSIFICATION).set(ClientPlatformInspector.Classification.BEDROCK);
        this.service.setEnabled(AccessPolicy.Kind.WHITELIST, false).toCompletableFuture().join();
        final var lines = new java.util.ArrayList<AccessRejectionLog.LogRecord>();
        AccessRejectionLog.putSinkForTests(lines::add);
        try {
            assertTrue(this.gate.admit(pc).toCompletableFuture().join().allowed());
            assertEquals(1, lines.size());
            assertTrue(lines.get(0).line().contains("blacklist check skipped"));
            assertFalse(lines.get(0).line().contains("rejected"));
        } finally {
            AccessRejectionLog.resetForTests();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void directAndLobbyEntriesUseTheSamePolicy() {
        // One direct-mode connection and one lobby-mode connection carry the same
        // blacklisted identity: both must be rejected by the same gate.
        final EmbeddedChannel direct = new EmbeddedChannel();
        final EmbeddedChannel lobby = new EmbeddedChannel();
        final ProxyConnection directPc = this.registerProxy(direct);
        final ProxyConnection lobbyPc = this.registerProxy(lobby);
        setVerifiedJava(direct, JAVA_UUID);
        setVerifiedJava(lobby, JAVA_UUID);
        try {
            this.service.mutateNow(AccessPolicy.Kind.BLACKLIST,
                    List.of(new AccessEntry(AccessKey.ClientType.JAVA, "Java", JAVA_UUID, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }

        final AccessPolicy.Decision directDecision = this.gate.admit(directPc).toCompletableFuture().join();
        final AccessPolicy.Decision lobbyDecision = this.gate.admit(lobbyPc).toCompletableFuture().join();
        assertEquals(AccessPolicy.Reason.BLACKLISTED, directDecision.reason());
        assertEquals(directDecision.reason(), lobbyDecision.reason());
        assertFalse(direct.isActive());
        assertFalse(lobby.isActive());
        assertFalse(this.gate.isAllowed(direct));
        assertFalse(this.gate.isAllowed(lobby));
    }

    @Test
    void whitelistedSubjectIsAdmittedAndKept() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ProxyConnection pc = this.registerProxy(channel);
        setVerifiedJava(channel, JAVA_UUID);
        try {
            this.service.mutateNow(AccessPolicy.Kind.WHITELIST,
                    List.of(new AccessEntry(AccessKey.ClientType.JAVA, "Java", JAVA_UUID, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }

        final AccessPolicy.Decision decision = this.gate.admit(pc).toCompletableFuture().join();
        assertTrue(decision.allowed());
        assertTrue(channel.isActive());
        assertTrue(this.gate.isAllowed(channel));
        assertTrue(this.gate.protectedOperationsAllowed(channel));
    }

    // ---- denial terminality --------------------------------------------------

    @Test
    void admissionCannotRecoverAfterDenial() {
        // The plan fixture: a controlled future and an explicit c2p. The admission is
        // still pending when an external denial (e.g. a rule update) lands.
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ProxyConnection pc = this.registerProxy(channel);
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.unverified(JAVA_UUID, "Bedrock"));
        final CompletableFuture<ClientIdentity> resolution = new CompletableFuture<>();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY_RESOLUTION).set(resolution);

        final CompletableFuture<AccessPolicy.Decision> pending = this.gate.admit(pc).toCompletableFuture();
        this.gate.reject(pc, new AccessPolicy.Decision(AccessPolicy.Reason.BLACKLISTED));
        assertFalse(channel.isActive(), "a rejected connection is closed");
        assertFalse(this.gate.isAllowed(channel), "a rejected connection is not allowed");

        // The late bridge answer is a perfectly acceptable bedrock identity — it must
        // not re-open the connection or mark it allowed.
        try {
            this.service.mutateNow(AccessPolicy.Kind.WHITELIST,
                    List.of(new AccessEntry(AccessKey.ClientType.JAVA, "Bedrock", JAVA_UUID, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        try {
            resolution.complete(ClientIdentity.verifiedBedrock(JAVA_UUID, "Bedrock", XUID,
                    UUID.randomUUID(), UUID.randomUUID().toString()));
        } catch (final RuntimeException ignored) {
        }
        pending.join();
        assertFalse(this.gate.isAllowed(channel), "nothing re-admitted the denied connection");
        assertFalse(this.gate.protectedOperationsAllowed(channel));
    }

    // ---- online rule updates --------------------------------------------------

    @Test
    void ruleUpdateDisconnectsBackendAndDirectPlayers() {
        // One "backend" connection and one "direct" connection, both admitted.
        final EmbeddedChannel backend = new EmbeddedChannel();
        final EmbeddedChannel direct = new EmbeddedChannel();
        final ProxyConnection backendPc = this.registerProxy(backend);
        final ProxyConnection directPc = this.registerProxy(direct);
        setVerifiedJava(backend, JAVA_UUID);
        setVerifiedJava(direct, JAVA_UUID);
        try {
            this.service.mutateNow(AccessPolicy.Kind.WHITELIST,
                    List.of(new AccessEntry(AccessKey.ClientType.JAVA, "Java", JAVA_UUID, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(this.gate.admit(backendPc).toCompletableFuture().join().allowed());
        assertTrue(this.gate.admit(directPc).toCompletableFuture().join().allowed());

        // Rule update: the account lands on the blacklist; the service publication
        // triggers the recheck (this is the CoreMain wiring under test).
        this.service.subscribe(state -> this.gate.recheck(state));
        try {
            this.service.mutateNow(AccessPolicy.Kind.BLACKLIST,
                    List.of(new AccessEntry(AccessKey.ClientType.JAVA, "Java", JAVA_UUID, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }

        assertFalse(backend.isActive(), "the backend connection is disconnected");
        assertFalse(direct.isActive(), "the direct connection is disconnected");
    }

    @Test
    void oldRuleRevisionCannotReallowConnection() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ProxyConnection pc = this.registerProxy(channel);
        setVerifiedJava(channel, JAVA_UUID);
        try {
            this.service.mutateNow(AccessPolicy.Kind.WHITELIST,
                    List.of(new AccessEntry(AccessKey.ClientType.JAVA, "Java", JAVA_UUID, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(this.gate.admit(pc).toCompletableFuture().join().allowed());

        // Deny under the current revision.
        try {
            this.service.mutateNow(AccessPolicy.Kind.BLACKLIST,
                    List.of(new AccessEntry(AccessKey.ClientType.JAVA, "Java", JAVA_UUID, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        this.gate.recheck(this.service.state());
        assertFalse(this.gate.isAllowed(channel));
        assertFalse(channel.isActive());

        // A recheck driven by a stale state (whitelist only, no blacklist entry)
        // must not re-allow: the evaluation always uses the latest state.
        this.gate.recheck(new AccessPolicy.State(true, false, this.service.state().whitelist(),
                this.service.state().blacklist(), Set.of(), this.service.state().revision() - 1));
        assertFalse(this.gate.isAllowed(channel), "a stale-state recheck cannot re-allow");
        assertFalse(channel.isActive());
    }

    // ---- pending identity -----------------------------------------------------

    @Test
    void pendingIdentityCannotClaimProfileOrRestoreAccount() {
        // A connection whose admission has not completed (no subject yet) is pending:
        // CP operations that require an access decision stay locked, even though the
        // captured identity itself would verify.
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ProxyConnection pc = this.registerProxy(channel);
        setVerifiedJava(channel, JAVA_UUID);

        assertFalse(this.gate.isAllowed(channel), "pending is not allowed");
        assertFalse(this.gate.protectedOperationsAllowed(channel), "pending blocks protected CP operations");

        // Admission completes: the same connection unlocks.
        try {
            this.service.mutateNow(AccessPolicy.Kind.WHITELIST,
                    List.of(new AccessEntry(AccessKey.ClientType.JAVA, "Java", JAVA_UUID, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(this.gate.admit(pc).toCompletableFuture().join().allowed());
        assertTrue(this.gate.protectedOperationsAllowed(channel));
    }

    // ---- transport state (no backend entry after denial) -----------------------

    @Test
    void deniedConnectionStaysClosedOnRepeatAdmission() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ProxyConnection pc = this.registerProxy(channel);
        setVerifiedJava(channel, JAVA_UUID);
        try {
            this.service.mutateNow(AccessPolicy.Kind.BLACKLIST,
                    List.of(new AccessEntry(AccessKey.ClientType.JAVA, "Java", JAVA_UUID, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        assertFalse(this.gate.admit(pc).toCompletableFuture().join().allowed());
        assertFalse(channel.isActive());

        // A repeat admission of the same channel cannot flip it back on.
        this.gate.admit(pc).toCompletableFuture().join();
        assertFalse(this.gate.isAllowed(channel));
        assertFalse(channel.isActive());
    }
}
