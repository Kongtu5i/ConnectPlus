package dev.connectplus.switching;

import com.mojang.authlib.GameProfile;
import com.viaversion.viaversion.api.minecraft.ProfileKey;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_0;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_1;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_3;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.connection.UserConnection;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.SwitchableProxyConnection;
import dev.connectplus.config.CPConfig;
import dev.connectplus.session.ConnectionInfo;
import dev.connectplus.session.PlayerIdentity;
import dev.connectplus.testutil.StubAccount;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OfflineSwitchTest {
    private EmbeddedChannel client, oldBackend, backend;
    private SwitchableProxyConnection pc;
    private SwitchEngine engine;
    private SwitchSuppressionHandler suppression;
    private boolean oldProxyOnline, oldLogin;
    private int oldRateLimit;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        ViaProxyTestConfig.init();
        this.oldProxyOnline = ViaProxy.getConfig().isProxyOnlineMode();
        this.oldLogin = CPConfig.allowAccountLogin;
        this.oldRateLimit = CPConfig.maxConnectAttemptsPerMinute;
        ViaProxy.getConfig().setProxyOnlineMode(true);
        CPConfig.allowAccountLogin = true;
        CPConfig.maxConnectAttemptsPerMinute = 0;
        this.client = new EmbeddedChannel();
        this.oldBackend = new EmbeddedChannel();
        this.backend = new EmbeddedChannel();
        this.oldBackend.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, 769));
        this.backend.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(true, 769));
        this.pc = new SwitchableProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), this.client) {
            { this.channelFuture = oldBackend.newSucceededFuture(); }
            @Override public ProtocolVersion getServerVersion() { return this.getClientVersion(); }
            @Override public ChannelFuture connectToServer(final SocketAddress address, final ProtocolVersion version) {
                return this.channelFuture = backend.newSucceededFuture();
            }
        };
        // Only the storage boundary is fake; the engine, profile and login replay are real.
        final java.util.Map<Class<?>, Object> stored = new java.util.HashMap<>();
        this.pc.setUserConnection((UserConnection) java.lang.reflect.Proxy.newProxyInstance(
                UserConnection.class.getClassLoader(), new Class<?>[]{UserConnection.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "put" -> { stored.put(args[0].getClass(), args[0]); yield null; }
                        case "has" -> stored.containsKey(args[0]);
                        case "remove" -> stored.remove(args[0]);
                        case "get" -> stored.get(args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                }));
        this.client.attr(CPAttributeKeys.PLAYER_IDENTITY).set(new PlayerIdentity(this.owner, "LobbyPlayer"));
        final UUID previousAccount = UUID.randomUUID();
        this.pc.setGameProfile(new GameProfile(previousAccount, "PreviousAccount"));
        final var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(512);
        final var keys = generator.generateKeyPair();
        this.pc.setLoginHelloPacket(new C2SLoginHelloPacket("PreviousAccount", Instant.now(), keys.getPublic(), new byte[]{1}, previousAccount));
        final var profileKey = new ProfileKey(100L, keys.getPublic().getEncoded(), new byte[]{1});
        this.pc.getUserConnection().put(new ChatSession1_19_0(previousAccount, keys.getPrivate(), profileKey));
        this.pc.getUserConnection().put(new ChatSession1_19_1(previousAccount, keys.getPrivate(), profileKey));
        this.pc.getUserConnection().put(new ChatSession1_19_3(previousAccount, keys.getPrivate(), profileKey));
        this.engine = new SwitchEngine(() -> new InetSocketAddress("127.0.0.1", 25570));
        this.suppression = new SwitchSuppressionHandler(this.pc, this.engine);
        this.pc.getPacketHandlers().add(this.suppression);
    }

    @AfterEach
    void cleanUp() {
        this.client.finishAndReleaseAll();
        this.oldBackend.finishAndReleaseAll();
        this.backend.finishAndReleaseAll();
        ViaProxy.getConfig().setProxyOnlineMode(this.oldProxyOnline);
        CPConfig.allowAccountLogin = this.oldLogin;
        CPConfig.maxConnectAttemptsPerMinute = this.oldRateLimit;
    }

    @ParameterizedTest
    @MethodSource("dev.connectplus.switching.LobbySwitchInventoryTest#supportedVersions")
    void offlineSwitchOmitsAccountAndReplacesPreviousProfileAndCertificates(final int version) {
        this.pc.setClientVersion(ProtocolVersion.getProtocol(version));
        this.pc.setC2pConnectionState(ConnectionState.PLAY);
        assertEquals(SwitchInitiator.StartResult.STARTED, this.engine.startSwitch(this.pc,
                new ConnectionInfo("127.0.0.1:25566", this.pc.getClientVersion(), this.owner, true),
                new StubAccount(), "LobbyPlayer"));
        this.client.runPendingTasks();
        this.backend.runPendingTasks();
        final var job = this.suppression.currentJob();
        assertFalse(job.authenticated(), "An offline request must omit even a supplied cached account");
        assertNull(this.pc.getUserOptions().account());
        final C2SLoginHelloPacket hello = this.backend.outboundMessages().stream()
                .filter(C2SLoginHelloPacket.class::isInstance).map(C2SLoginHelloPacket.class::cast)
                .findFirst().orElseThrow();
        final UUID offlineId = UUID.nameUUIDFromBytes("OfflinePlayer:LobbyPlayer".getBytes(StandardCharsets.UTF_8));
        assertEquals("LobbyPlayer", hello.name);
        assertEquals(offlineId, hello.uuid);
        assertEquals(offlineId, this.pc.getGameProfile().getId());
        assertNull(hello.key);
        assertNull(hello.keySignature);
        assertNull(hello.expiresAt);
        assertFalse(this.pc.getUserConnection().has(ChatSession1_19_0.class));
        assertFalse(this.pc.getUserConnection().has(ChatSession1_19_1.class));
        assertFalse(this.pc.getUserConnection().has(ChatSession1_19_3.class));
        assertEquals(this.owner, this.client.attr(CPAttributeKeys.PLAYER_IDENTITY).get().uuid(),
                "Backend offline identity must not change the owner of saved account data");
    }
}
