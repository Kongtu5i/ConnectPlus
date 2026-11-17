package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.SwitchableProxyConnection;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.LobbyNotices;
import dev.connectplus.lobby.LobbyTransfers;
import dev.connectplus.session.*;
import dev.connectplus.testutil.StubAccount;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for the memory lifecycle audit. */
class MemoryLifecycleTest {
    @TempDir File temp;

    @Test void shutdownClosesManagedClientsAndReturnsConfigurationBudget() throws Exception {
        ViaProxyTestConfig.init();
        EmbeddedChannel c2p = new EmbeddedChannel();
        SwitchEngine engine = new SwitchEngine(() -> null);
        try {
            var pc = connection(c2p, engine);
            assertEquals(SwitchInitiator.StartResult.STARTED, engine.startSwitch(pc,
                    new ConnectionInfo("127.0.0.1:1",ProtocolVersion.v1_21_4,UUID.randomUUID(),false,
                            c2p.attr(CPAttributeKeys.CONNECTION_ID).get()),null,"Audit"));
            var suppression = (SwitchSuppressionHandler)pc.getPacketHandlers().stream()
                    .filter(h -> h instanceof SwitchSuppressionHandler).findFirst().orElseThrow();
            var config = (ClientConfiguration)field(suppression,"clientConfiguration");
            config.accept(suppression.currentJob(),new UnknownPacket(0,new byte[1024]));
            engine.shutdown(); c2p.runPendingTasks();
            assertFalse(c2p.isActive());
            assertEquals(0,field(config,"reservedBytes"));
        } finally { engine.shutdown(); c2p.finishAndReleaseAll(); }
    }

    static Object field(Object owner, String name) throws Exception {
        Class<?> type = owner.getClass();
        while (type != null) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
            catch (NoSuchFieldException e) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    @Test void expiredRateLimitWindowsReleasePlayerKeys() throws Exception {
        ConnectRateLimiter limiter = new ConnectRateLimiter(3);
        long old = System.nanoTime() - TimeUnit.SECONDS.toNanos(61);
        for (int i = 0; i < 10000; i++) {
            UUID id = UUID.randomUUID();
            assertTrue(limiter.tryAcquireAt(id, old, List.of()));
            assertEquals(0, limiter.currentCount(id));
        }
        Map<?,?> entries = (Map<?,?>) field(limiter, "attempts");
        assertEquals(0, entries.size());
    }

    @Test void releasedLeasesDoNotRetainConnectionGenerationKeys() throws Exception {
        AccountSessionCoordinator coordinator = new AccountSessionCoordinator(new PlayerStore(new File(temp, "players")), () -> null);
        try {
            for (int i = 0; i < 1000; i++) {
                PlayerSession s = new PlayerSession(UUID.randomUUID(), "Audit");
                s.connectionId = UUID.randomUUID();
                SessionLease lease = coordinator.claim(s, ProfileKey.javaProfile(s.uuid), Set.of()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                coordinator.release(lease).toCompletableFuture().get(3, TimeUnit.SECONDS);
            }
            assertEquals(0, ((Map<?,?>)field(coordinator,"tracked")).size());
            assertEquals(0, ((Map<?,?>)field(coordinator,"holders")).size());
            Object generations = field(coordinator,"generations");
            assertFalse(generations instanceof Map, "Generation allocation must not retain historical connection keys");
        } finally { coordinator.shutdown(); }
    }

    static SwitchableProxyConnection connection(EmbeddedChannel c2p, SwitchEngine engine) {
        UUID id = UUID.randomUUID();
        c2p.attr(CPAttributeKeys.CONNECTION_ID).set(id);
        UUID javaId = UUID.randomUUID();
        c2p.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.verifiedJava(javaId, "Audit", javaId));
        SwitchableProxyConnection pc = new SwitchableProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), c2p);
        pc.setClientVersion(ProtocolVersion.v1_21_4);
        pc.getPacketHandlers().add(new SwitchSuppressionHandler(pc, engine));
        return pc;
    }

    @Test void closedClientsReleaseCachedAccounts() throws Exception {
        ViaProxyTestConfig.init();
        boolean oldOnline = ViaProxy.getConfig().isProxyOnlineMode(), oldLogin = CPConfig.allowAccountLogin;
        int oldLimit = CPConfig.maxConnectAttemptsPerMinute;
        ViaProxy.getConfig().setProxyOnlineMode(true); CPConfig.allowAccountLogin = true; CPConfig.maxConnectAttemptsPerMinute = 0;
        SwitchEngine engine = new SwitchEngine(() -> null);
        SwitchEngine.setAccountLeaseValidator(id -> true);
        try {
            for (int i = 0; i < 100; i++) {
                EmbeddedChannel c2p = new EmbeddedChannel();
                SwitchableProxyConnection pc = connection(c2p, engine);
                UUID id = c2p.attr(CPAttributeKeys.CONNECTION_ID).get();
                assertEquals(SwitchInitiator.StartResult.STARTED, engine.startSwitch(pc,
                        new ConnectionInfo("127.0.0.1:1", ProtocolVersion.v1_21_4, UUID.randomUUID(), false, id), new StubAccount(), "Audit"));
                // Model a completed switch using the actual terminal callback, without backend IO.
                SwitchSuppressionHandler suppression = (SwitchSuppressionHandler) pc.getPacketHandlers().stream()
                        .filter(h -> h instanceof SwitchSuppressionHandler).findFirst().orElseThrow();
                SwitchJob job = suppression.currentJob();
                job.markForward(); engine.onSwitchComplete(pc, job);
                c2p.close(); c2p.runPendingTasks(); c2p.finishAndReleaseAll();
            }
            assertEquals(0, engine.inFlightTargetCount());
            assertEquals(0, ((Map<?,?>) field(engine, "tasks")).size());
            assertEquals(0, ((Map<?,?>) field(engine, "accountsByConnection")).size());
        } finally {
            SwitchEngine.setAccountLeaseValidator(null); ViaProxy.getConfig().setProxyOnlineMode(oldOnline);
            CPConfig.allowAccountLogin=oldLogin; CPConfig.maxConnectAttemptsPerMinute=oldLimit;
        }
    }

    @Test void failedLobbyRecoveryReleasesPendingTransfer() throws Exception {
        ViaProxyTestConfig.init();
        String old = CPConfig.transferPolicy; CPConfig.transferPolicy = "confirm";
        LobbyTransfers.consumeAll();
        EmbeddedChannel c2p = new EmbeddedChannel();
        try {
            SwitchEngine engine = new SwitchEngine(() -> null);
            SwitchableProxyConnection pc = connection(c2p, engine);
            SwitchJob job = SwitchJob.starting(new SwitchJob.Target("127.0.0.1:1", ProtocolVersion.v1_21_4, UUID.randomUUID(), "Audit", false, 1));
            engine.onTargetTransfer(pc, job, "example.invalid", 25565);
            c2p.runPendingTasks(); c2p.close(); c2p.runPendingTasks();
            assertEquals(0, LobbyTransfers.size());
        } finally { c2p.finishAndReleaseAll(); LobbyTransfers.consumeAll(); CPConfig.transferPolicy=old; }
    }

    static UnknownPacket profiles(int first, int count) {
        ByteBuf b = Unpooled.buffer();
        try {
            b.writeByte(1); PacketTypes.writeVarInt(b,count);
            for (int i = first; i < first+count; i++) {
                b.writeLong(0).writeLong(i); PacketTypes.writeString(b,"Audit"); PacketTypes.writeVarInt(b,0);
            }
            return new UnknownPacket(MCPackets.S2C_PLAYER_INFO_UPDATE.getId(777), ByteBufUtil.getBytes(b));
        } finally { b.release(); }
    }

    @Test void playerLimitAppliesAcrossPacketsAtomically() throws Exception {
        TabListState state = new TabListState();
        state.observe(profiles(0,35000),777,null,null);
        assertThrows(RuntimeException.class, () -> state.observe(profiles(35000,35000),777,null,null));
        assertEquals(35000, ((Set<?>)field(state,"players")).size(), "Overflow must not partially mutate state");
        state.clear(777);
        assertEquals(0, ((Set<?>)field(state,"players")).size());
    }

    static int listenerCount(Object future) throws Exception {
        Object listeners = field(future, "listeners");
        if (listeners == null) return 0;
        try { return (int)field(listeners,"size"); } catch (NoSuchFieldException e) { return 1; }
    }

    @Test void consumedNoticesReleaseCloseListeners() throws Exception {
        EmbeddedChannel c2p = new EmbeddedChannel();
        UUID id = UUID.randomUUID();
        LobbyNotices.consumeAll();
        try {
            int before = listenerCount(c2p.closeFuture());
            for (int i=0; i<1000; i++) {
                LobbyNotices.post(id, List.of(LobbyNotices.Notice.raw("audit-"+i)), c2p);
                assertNotNull(LobbyNotices.consume(id));
            }
            assertEquals(0,LobbyNotices.size());
            assertEquals(before,listenerCount(c2p.closeFuture()));
        } finally { c2p.finishAndReleaseAll(); LobbyNotices.consumeAll(); }
    }
}
