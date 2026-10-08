package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.LobbyServer;
import dev.connectplus.session.ConnectionInfo;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.SessionLease;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.compat.SwitchableProxyConnection;
import dev.connectplus.switching.SwitchInitiator;
import dev.connectplus.testutil.StubAccount;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viaproxy.ViaProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review round 1, IMPORTANT 1: the switch-account lease validator must key on the
 * CONNECTION id (the session-registry and lease key) carried through the switch
 * flow — never on the profile uuid, which two connections can share and which the
 * registry is deliberately not keyed by. The test wires the PRODUCTION path (a real
 * LobbyServer, whose constructor installs the coordinator-backed validator, plus the
 * real ConnectFlow → engine handoff) and pins the full switch-cache story: the
 * account is cached while the lease is current, reused by the engine's own retry
 * paths, and dropped once the session is displaced.
 */
class SwitchAccountLeaseTest {

    @TempDir
    File dataDir;

    private boolean oldProxyOnline;
    private boolean oldAccountLogin;

    @BeforeEach
    void setUp() {
        ViaProxyTestConfig.init();
        this.oldProxyOnline = ViaProxy.getConfig().isProxyOnlineMode();
        this.oldAccountLogin = CPConfig.allowAccountLogin;
        ViaProxy.getConfig().setProxyOnlineMode(true);
        CPConfig.allowAccountLogin = true;
    }

    @AfterEach
    void tearDown() {
        ViaProxy.getConfig().setProxyOnlineMode(this.oldProxyOnline);
        CPConfig.allowAccountLogin = this.oldAccountLogin;
        dev.connectplus.switching.SwitchEngine.setAccountLeaseValidator(null);
    }

    @Test
    void productionWiringCachesTheAccountWhileCurrentAndDropsItWhenDisplaced() throws Exception {
        //The production wiring: building the LobbyServer installs the coordinator-backed
        //validator (the exact wiring CoreMain relies on) — no custom test validator.
        final SessionRegistry registry = new SessionRegistry();
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final LobbyServer server = new LobbyServer(registry, new TokenStore(this.dataDir), store,
                new dev.connectplus.testutil.RecordingSwitchInitiator());
        try {
            //The production wiring: the LobbyServer's coordinator-backed validator
            //(exactly what CoreMain installs), keyed by CONNECTION id.
            SwitchEngine.setAccountLeaseValidator(server.accountLeaseValidator());
            final UUID connectionId = UUID.randomUUID();
            final UUID profileUuid = UUID.randomUUID(); //deliberately DIFFERENT from the connection id
            final PlayerSession session = new PlayerSession(profileUuid, "LinkedPlayer");
            session.connectionId = connectionId;
            final EmbeddedChannel c2p = new EmbeddedChannel();
            c2p.attr(dev.connectplus.compat.CPAttributeKeys.CONNECTION_ID).set(connectionId);
            session.c2pChannel = c2p;
            registry.register(session);

            //A real claim: the session holds B's profile AND account resource (§7).
            final dev.connectplus.session.AccountSessionCoordinator coordinator = server.sessionCoordinator();
            final SessionLease lease = coordinator.claim(session, dev.connectplus.identity.ProfileKey.javaProfile(profileUuid),
                    Set.of(profileUuid)).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            session.lease = lease;
            session.generation = lease.generation();

            assertTrue(dev.connectplus.switching.SwitchEngine.accountMayConnect(connectionId),
                    "The production validator must accept a connection whose lease is current");
            assertFalse(dev.connectplus.switching.SwitchEngine.accountMayConnect(profileUuid),
                    "The profile uuid is NOT a lease key: the old (buggy) lookup must stay dead");

            //The ConnectFlow → engine handoff carries the connection id: the account
            //enters the cache while the lease is current (validated by the production
            //validator, same predicate as the launch-time cache decision).
            final SwitchEngine engine = new SwitchEngine(() -> null);
            final ConnectionInfo target = new ConnectionInfo("backend.example.net:25565",
                    ProtocolVersion.v1_21_4, profileUuid, false, connectionId);
            final StubAccount account = new StubAccount();
            final SwitchInitiator.StartResult started = engine.startSwitch(newCachePc(connectionId), target, account, "LinkedPlayer");
            assertEquals(SwitchInitiator.StartResult.STARTED, started);
            assertEquals(account.uuid(), cachedAccountOrNull(engine, connectionId).uuid(),
                    "A current lease's account must be cached for the reconnect chain / on-target /connect");

            //The displacement: freeze (displaced flag) + lease invalidation, §5 step 2/3.
            session.displaced = true;
            coordinator.release(lease).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertFalse(dev.connectplus.switching.SwitchEngine.accountMayConnect(connectionId),
                    "The displaced connection's cached account must be refused");
            assertNull(cachedAccountOrNull(engine, connectionId),
                    "The retry paths (reconnect chain, transfer-follow, on-target /connect) must drop the displaced session's cached account");
        } finally {
            server.stop();
        }
    }

    @Test
    void verifiedBedrockAccountSurvivesTheLaunchGateWithJavaEntryAuthOff() {
        final boolean oldGeyser = CPConfig.GeyserSupport.enabled;
        ViaProxy.getConfig().setProxyOnlineMode(false);
        CPConfig.GeyserSupport.enabled = true;
        final UUID connectionId = UUID.randomUUID();
        final var pc = newCachePc(connectionId);
        try {
            SwitchEngine.setAccountLeaseValidator(id -> connectionId.equals(id));
            dev.connectplus.testutil.TestClientIdentity.bedrock(pc.getC2P(),
                    dev.connectplus.identity.ClientIdentity.verifiedBedrock(UUID.randomUUID(), "BedrockPlayer",
                            "4242424242424", UUID.randomUUID(), UUID.randomUUID().toString()));
            final SwitchEngine engine = new SwitchEngine(() -> null);
            final StubAccount account = new StubAccount();
            assertEquals(SwitchInitiator.StartResult.STARTED, engine.startSwitch(pc,
                    new ConnectionInfo("backend.example.net:25565", ProtocolVersion.v1_21_4,
                            UUID.randomUUID(), false, connectionId), account, "BedrockPlayer"));
            assertSame(account, cachedAccountOrNull(engine, connectionId));
        } finally {
            pc.getC2P().close();
            CPConfig.GeyserSupport.enabled = oldGeyser;
        }
    }

    /** Package-private observation of the engine's §6 cache (test seam). */
    private static dev.connectplus.accounts.CPAccount cachedAccountOrNull(final SwitchEngine engine, final UUID connectionId) {
        return engine.cachedAccountForTest(connectionId);
    }

    /**
     * A minimal switchable proxy connection whose c2p carries the connection id —
     * enough for {@code startSwitch} to reach the launch-time cache decision; the
     * scheduled run itself fails fast on the embedded channel without a backend
     * (its outcome is irrelevant here: the cache decision happens synchronously in
     * launch, exactly the window the §6 rule governs).
     */
    private static net.raphimc.viaproxy.proxy.session.ProxyConnection newCachePc(final UUID connectionId) {
        final EmbeddedChannel c2p = new EmbeddedChannel();
        c2p.attr(dev.connectplus.compat.CPAttributeKeys.CONNECTION_ID).set(connectionId);
        final UUID javaUuid = UUID.randomUUID();
        c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).set(
                dev.connectplus.identity.ClientIdentity.verifiedJava(javaUuid, "LinkedPlayer", javaUuid));
        final SwitchableProxyConnection pc = new SwitchableProxyConnection(
                new net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer(
                        net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler::new), c2p);
        pc.setClientVersion(ProtocolVersion.v1_21_4);
        //launch requires the suppression handler (the job registry) to be installed,
        //exactly like the real session installer does.
        pc.getPacketHandlers().add(new SwitchSuppressionHandler(pc, new SwitchSuppressionHandler.Owner() {
            @Override public void onSwitchComplete(final net.raphimc.viaproxy.proxy.session.ProxyConnection pc, final SwitchJob job) { }
            @Override public void onSwitchFailed(final net.raphimc.viaproxy.proxy.session.ProxyConnection pc, final SwitchJob job, final String reason) { }
            @Override public void onTargetCommand(final net.raphimc.viaproxy.proxy.session.ProxyConnection pc, final dev.connectplus.commands.LobbyCommands.Command command) { }
            @Override public void onForwardKick(final net.raphimc.viaproxy.proxy.session.ProxyConnection pc, final SwitchJob job, final String reason) { }
            @Override public void onForwardDeath(final net.raphimc.viaproxy.proxy.session.ProxyConnection pc, final SwitchJob job) { }
            @Override public void onTargetTransfer(final net.raphimc.viaproxy.proxy.session.ProxyConnection pc, final SwitchJob job, final String host, final int port) { }
        }));
        return pc;
    }
}
