package dev.connectplus.session;

import dev.connectplus.accounts.CPAccount;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.states.LoginStateHandler;
import dev.connectplus.switching.SwitchInitiator;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import dev.connectplus.testutil.StubAccount;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6: stale-callback invalidation across the whole chain. Device-code
 * results, token-refresh results, switch target selection and the backend
 * failure fallback capture (connectionId, lease generation) at start and
 * validate it at commit: a result that arrives after a timeout, a
 * displacement, a switch or a lobby return must be dropped entirely — no
 * account install, no profile write, no backend reconnect carrying the old
 * session's credentials.
 */
class StaleAccountOperationTest {

    @TempDir
    File dataDir;

    private static final String WIRE_NAME = "StalePlayer";
    private static final UUID WIRE_UUID = UUID.fromString("cccccccc-0000-4000-8000-0000000000cc");
    private static final UUID JAVA_UUID = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000bb");

    private boolean oldProxyOnline;
    private boolean oldAccountLogin;

    @BeforeEach
    void setUp() {
        ViaProxyTestConfig.init();
        this.oldProxyOnline = net.raphimc.viaproxy.ViaProxy.getConfig().isProxyOnlineMode();
        this.oldAccountLogin = dev.connectplus.config.CPConfig.allowAccountLogin;
        net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(true);
        dev.connectplus.config.CPConfig.allowAccountLogin = true;
    }

    @AfterEach
    void tearDown() {
        net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(this.oldProxyOnline);
        dev.connectplus.config.CPConfig.allowAccountLogin = this.oldAccountLogin;
    }

    @Test
    void linkedBedrockRestoresTheSavedJavaAccountWithProxyOnlineModeOff() throws Exception {
        final boolean oldGeyser = dev.connectplus.config.CPConfig.GeyserSupport.enabled;
        net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(false);
        dev.connectplus.config.CPConfig.GeyserSupport.enabled = true;
        try (Rig rig = new Rig()) {
            final String xuid = "4242424242424";
            dev.connectplus.testutil.TestClientIdentity.bedrock(rig.client,
                    dev.connectplus.identity.ClientIdentity.verifiedBedrock(WIRE_UUID, WIRE_NAME,
                            xuid, UUID.randomUUID(), UUID.randomUUID().toString()));
            new dev.connectplus.identity.IdentityLinkStore(rig.store.playersDir()).replace(0, java.util.Map.of(xuid, JAVA_UUID));
            final PlayerData seeded = new PlayerData(JAVA_UUID);
            seeded.accountBlob = rig.tokens.encrypt(restoreFixtureJson());
            rig.store.save(seeded);
            final PlayerSession session = rig.login();
            rig.pump();
            assertNotNull(session.lease);
            assertEquals(ProfileKey.javaProfile(JAVA_UUID), session.profileKey);
            assertNotNull(session.account, "Bridge-authenticated players must restore credentials independently of Java entry auth");
            assertEquals(WIRE_UUID, session.account.uuid());
            assertEquals(seeded.accountBlob, rig.store.load(ProfileKey.javaProfile(JAVA_UUID)).accountBlob);
        } finally {
            dev.connectplus.config.CPConfig.GeyserSupport.enabled = oldGeyser;
        }
    }

    /** The lobby-side rig: real coordinator + c2p channel + one lobby handler. */
    private final class Rig implements AutoCloseable {

        final UUID connectionId = UUID.randomUUID();
        final EmbeddedChannel client = new EmbeddedChannel();
        final EmbeddedChannel lobby = new EmbeddedChannel();
        final UUID link = UUID.randomUUID();
        final SessionRegistry registry = new SessionRegistry();
        final ExecutorService storage = Executors.newSingleThreadExecutor();
        final AtomicInteger uncaught = new AtomicInteger();
        final TokenStore tokens;
        final PlayerStore store = new PlayerStore(new File(dataDir, "players"));
        final AccountSessionCoordinator coordinator = new AccountSessionCoordinator(this.store, () -> null);
        final RecordingSwitchInitiator initiator = new RecordingSwitchInitiator();
        /** When true, decrypt blocks until the gate opens (restore timing control). */
        volatile boolean holdDecrypt;
        final java.util.concurrent.CountDownLatch decryptGate = new java.util.concurrent.CountDownLatch(1);
        volatile LobbyServerHandler handler;

        {
            final TokenStore plain = new TokenStore(dataDir);
            this.tokens = new TokenStore(dataDir) {
                @Override
                public String decrypt(final String blob) {
                    if (StaleAccountOperationTest.Rig.this.holdDecrypt) {
                        try {
                            StaleAccountOperationTest.Rig.this.decryptGate.await(60, TimeUnit.SECONDS);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    }
                    return plain.decrypt(blob);
                }
            };
        }

        Rig() {
            final var proxy = new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), this.client);
            LobbyLink.register(this.link, proxy);
            this.client.attr(CPAttributeKeys.CONNECTION_ID).set(this.connectionId);
            //A verified Java identity: the join resolves its own JAVA profile,
            //claims the lease and holds protected state the tests can invalidate.
            this.client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(
                    dev.connectplus.identity.ClientIdentity.verifiedJava(WIRE_UUID, WIRE_NAME, JAVA_UUID));
            this.client.attr(CPAttributeKeys.PLAYER_IDENTITY).set(new PlayerIdentity(WIRE_UUID, WIRE_NAME));
            this.lobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(this.link);
        }

        LobbyServerHandler newHandler() {
            final LobbyServerHandler handler = new LobbyServerHandler(Set.of(), this.registry,
                    this.tokens, this.store, new dev.connectplus.identity.IdentityLinkStore(this.store.playersDir()),
                    this.coordinator, this.initiator, this.storage, this.uncaught);
            this.handler = handler;
            return handler;
        }

        PlayerSession login() {
            new LoginStateHandler(this.newHandler(), this.lobby).handle(
                    new C2SLoginHelloPacket(WIRE_NAME, null, null, null, WIRE_UUID));
            return this.handler.getSession();
        }

        void pump() throws Exception {
            for (int round = 0; round < 16; round++) {
                this.coordinator.awaitIdle().toCompletableFuture().get(3, TimeUnit.SECONDS);
                try {
                    this.storage.submit(() -> {
                    }).get(3, TimeUnit.SECONDS);
                } catch (final java.util.concurrent.TimeoutException held) {
                    //A held restore blocks the storage queue by design (the decrypt
                    //gate is closed); drain the rest without waiting for it.
                }
                this.lobby.runPendingTasks();
                this.client.runPendingTasks();
            }
        }

        @Override
        public void close() {
            LobbyLink.unregister(this.link);
            this.storage.shutdownNow();
            this.coordinator.shutdown();
            this.client.finishAndReleaseAll();
            this.lobby.finishAndReleaseAll();
        }
    }

    /** A plain state handler standing in for the lobby play state (no GUI side effects). */
    private static dev.connectplus.lobby.states.StateHandler plainState(final LobbyServerHandler handler,
                                                                        final EmbeddedChannel channel) {
        return new dev.connectplus.lobby.states.StateHandler(handler, channel) {
        };
    }

    /**
     * §6: a stale backend-fallback (the engine reconnects on the OLD session's
     * behalf after the player returned to the lobby) must not carry the account
     * credentials to a backend: the switch target selection re-checks the lease
     * at handoff and passes no account for a session whose lease is no longer
     * current.
     */
    @Test
    void staleSwitchTargetSelectionPassesNoAccountAfterTheLeaseWasLost() throws Exception {
        try (Rig rig = new Rig()) {
            final PlayerSession session = rig.login();
            rig.pump();
            assertNotNull(session.lease, "the rig needs a granted lease");
            final CPAccount account = new StubAccount();
            session.account = account;
            session.serverAddress = "backend.example.net:25565";
            final var state = plainState(rig.handler, rig.lobby);

            //The switch starts, THEN the session is displaced (or the lease lost):
            //the handoff decision must re-check the lease at commit time.
            final SessionLease lease = session.lease;
            final SwitchInitiator.StartResult before = dev.connectplus.lobby.ConnectFlow.start(
                    session, state, rig.initiator);
            assertEquals(SwitchInitiator.StartResult.STARTED, before);
            assertEquals(account, rig.initiator.requests().get(0).account(), "A current lease carries the account");

            //Freeze + invalidate exactly like a displacement (§5 step 2/3).
            rig.coordinator.release(lease);
            rig.pump();

            final SwitchInitiator.StartResult after = dev.connectplus.lobby.ConnectFlow.start(
                    session, state, rig.initiator);
            assertEquals(SwitchInitiator.StartResult.STARTED, after);
            assertNull(rig.initiator.requests().get(1).account(),
                    "A stale lease must not hand the account to the backend (no revival of the old session)");
        }
    }

    /**
     * §6: a stale token-restore result (the refresh completed after the session
     * was displaced) must not land the restored account on the session: the
     * landing validates the lease currency captured at start, so the displaced
     * session stays logged out and the profile stays untouched.
     */
    @Test
    void staleRestoreResultCannotLandOnADisplacedSession() throws Exception {
        try (Rig rig = new Rig()) {
            final PlayerData seeded = new PlayerData(JAVA_UUID);
            seeded.accountBlob = rig.tokens.encrypt(restoreFixtureJson());
            rig.store.save(seeded);

            //Hold the restore inside decrypt: the restore has "started" (captured
            //its lease) but its result must wait past the displacement.
            rig.holdDecrypt = true;
            final PlayerSession session = rig.login();
            rig.pump();
            assertNotNull(session.lease, "The restore must have captured a granted lease");

            //The displacement invalidates the lease while the restore is mid-flight.
            session.displaced = true;
            rig.coordinator.release(session.lease);
            rig.pump();

            //Now let the restore finish: its stale result must be dropped.
            rig.holdDecrypt = false;
            rig.decryptGate.countDown();
            rig.pump();

            assertNull(session.account, "A displaced session must not receive a restored account");
            assertEquals(seeded.accountBlob, rig.store.load(ProfileKey.javaProfile(JAVA_UUID)).accountBlob,
                    "The profile blob stays for the new holder's own load");
        }
    }

    /**
     * The decrypted JSON of a valid (non-expiring) account; built through the
     * real MinecraftAuth serialization so the restore path parses it without
     * any network (all tokens far-future).
     */
    static String restoreFixtureJson() {
        final var manager = net.raphimc.minecraftauth.java.JavaAuthManager.create(
                        net.raphimc.minecraftauth.MinecraftAuth.createHttpClient())
                .login(new net.raphimc.minecraftauth.msa.model.MsaToken(Long.MAX_VALUE, "test-only-access", "test-only-refresh"));
        manager.getMinecraftToken().set(new net.raphimc.minecraftauth.java.model.MinecraftToken(Long.MAX_VALUE, "Bearer", "t"));
        manager.getMinecraftProfile().set(new net.raphimc.minecraftauth.java.model.MinecraftProfile(WIRE_UUID, WIRE_NAME));
        return net.raphimc.minecraftauth.java.JavaAuthManager.toJson(manager).toString();
    }

    /**
     * §5: a stale lease release (the old generation's late close callback after
     * a re-grant) must not release the NEW holder's lease — the coordinator's
     * release compares holder+generation (pinned here end to end through the
     * registry, R5).
     */
    @Test
    void staleGenerationReleaseCannotRemoveTheNewHolderSlot() throws Exception {
        try (Rig rig = new Rig()) {
            final PlayerSession session = rig.login();
            rig.pump();
            final SessionLease first = session.lease;
            assertNotNull(first);

            //A re-claim by the same connection (e.g. a lobby return race): the
            //coordinator supersedes the orphaned lease and grows the generation.
            final SessionLease second = rig.coordinator.claim(session, ProfileKey.javaProfile(JAVA_UUID), Set.of(JAVA_UUID))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            session.lease = second;
            session.generation = second.generation();
            rig.pump();
            assertTrue(second.generation() > first.generation(), "The re-grant must grow the generation");

            //The OLD close callback finally fires, releasing the stale lease.
            rig.coordinator.release(first).toCompletableFuture().get(3, TimeUnit.SECONDS);
            rig.pump();
            assertTrue(rig.coordinator.isCurrent(second),
                    "The stale release must not invalidate the new lease (R5)");
            assertSame(session, rig.registry.get(session.connectionId),
                    "The re-granted holder keeps its registry slot");
            assertTrue(rig.coordinator.accountMayConnect(session.connectionId),
                    "The current holder can still use its account");

            rig.coordinator.release(second).toCompletableFuture().get(3, TimeUnit.SECONDS);
            rig.pump();
            assertFalse(rig.coordinator.isCurrent(second));
            assertFalse(rig.coordinator.accountMayConnect(session.connectionId));
        }
    }
}
