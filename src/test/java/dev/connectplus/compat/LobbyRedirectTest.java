package dev.connectplus.compat;

import com.google.common.net.HostAndPort;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.config.CPConfig;
import dev.connectplus.lobby.LobbyProtocol;
import dev.connectplus.lobby.LobbyServer;
import dev.connectplus.session.PlayerStore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.IntendedState;
import net.raphimc.viaproxy.plugins.events.PreConnectEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyRedirectTest {

    private static final SocketAddress DIRECT_ADDRESS = new InetSocketAddress("203.0.113.10", 25565);
    private static final ProtocolVersion DIRECT_VERSION = ProtocolVersion.v1_8;

    private static LobbyServer lobby;
    private static SocketAddress lobbyAddress;

    @TempDir
    static File tempDir;

    private LobbyRedirect redirect;

    @BeforeAll
    static void startLobby() throws Exception {
        lobby = new LobbyServer(new dev.connectplus.session.SessionRegistry(), new TokenStore(tempDir), new PlayerStore(new File(tempDir, "players")), null);
        lobby.start();
        lobbyAddress = lobby.localAddress();
    }

    @AfterAll
    static void stopLobby() {
        if (lobby != null) {
            lobby.stop();
            lobby = null;
        }
    }

    @BeforeEach
    void setUp() {
        CPConfig.mode = "lobby";
        this.redirect = new LobbyRedirect(() -> lobby);
    }

    @AfterEach
    void tearDown() {
        CPConfig.mode = "lobby";
    }

    private static PreConnectEvent event(final IntendedState state, final String host, final EmbeddedChannel channel) {
        return new PreConnectEvent(DIRECT_ADDRESS, DIRECT_VERSION, ProtocolVersion.v1_21_4,
                HostAndPort.fromParts(host, 25565), state, channel);
    }

    @Test
    void loginGoesToLobbyWithHaProxy() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final PreConnectEvent event = event(IntendedState.LOGIN, "lobby.example.com", channel);

        this.redirect.onPreConnect(event);

        assertEquals(lobbyAddress, event.getServerAddress());
        assertEquals(LobbyProtocol.VERSION, event.getServerVersion());
        assertEquals(Boolean.TRUE, channel.attr(CPAttributeKeys.ENABLE_HAPROXY).get());
    }

    @Test
    void transferIntentFallsBackToLobbyWithoutHaProxy() {
        //Backend-initiated transfers (M5's follow policy) reach the lobby without the
        //HAProxy forwarding, exactly like a plain login routed into the lobby would.
        final EmbeddedChannel channel = new EmbeddedChannel();
        final PreConnectEvent event = event(IntendedState.TRANSFER, "lobby.example.com", channel);

        this.redirect.onPreConnect(event);

        assertEquals(lobbyAddress, event.getServerAddress());
        assertEquals(LobbyProtocol.VERSION, event.getServerVersion());
        assertFalse(channel.hasAttr(CPAttributeKeys.ENABLE_HAPROXY));
    }

    @Test
    void wildcardHostStaysDirect() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final PreConnectEvent event = event(IntendedState.LOGIN, "mysrv.example.com.viaproxy.localhost", channel);

        this.redirect.onPreConnect(event);

        assertEquals(DIRECT_ADDRESS, event.getServerAddress());
        assertEquals(DIRECT_VERSION, event.getServerVersion());
        assertFalse(channel.hasAttr(CPAttributeKeys.ENABLE_HAPROXY));
    }

    @Test
    void proxyModeStaysDirect() {
        CPConfig.mode = "proxy";
        final EmbeddedChannel channel = new EmbeddedChannel();
        final PreConnectEvent event = event(IntendedState.LOGIN, "lobby.example.com", channel);

        this.redirect.onPreConnect(event);

        assertEquals(DIRECT_ADDRESS, event.getServerAddress());
        assertEquals(DIRECT_VERSION, event.getServerVersion());
        assertFalse(channel.hasAttr(CPAttributeKeys.ENABLE_HAPROXY));
    }

    @Test
    void statusIntentLandsInTheLobbyWithoutHaProxy() {
        //The lobby answers status pings itself (its own protocol version), so the
        //switch engine's version auto detection also works against a target that is
        //another ConnectPlus lobby; bare ping connections carry no HAProxy preamble.
        final EmbeddedChannel channel = new EmbeddedChannel();
        final PreConnectEvent event = event(IntendedState.STATUS, "lobby.example.com", channel);

        this.redirect.onPreConnect(event);

        assertEquals(lobbyAddress, event.getServerAddress());
        assertEquals(LobbyProtocol.VERSION, event.getServerVersion());
        assertFalse(channel.hasAttr(CPAttributeKeys.ENABLE_HAPROXY));
    }

    @Test
    void statusIntentToWildcardHostStaysDirect() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final PreConnectEvent event = event(IntendedState.STATUS, "mysrv.example.com.viaproxy.localhost", channel);

        this.redirect.onPreConnect(event);

        assertEquals(DIRECT_ADDRESS, event.getServerAddress());
        assertEquals(DIRECT_VERSION, event.getServerVersion());
    }
}
