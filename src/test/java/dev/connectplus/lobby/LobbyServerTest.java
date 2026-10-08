package dev.connectplus.lobby;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.switching.SwitchInitiator;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyServerTest {

    private static final EventLoopGroup GROUP = new NioEventLoopGroup(1);

    @TempDir
    static File tempDir;

    @AfterAll
    static void shutdownGroup() {
        GROUP.shutdownGracefully();
    }

    private static LobbyServer newLobbyServer() {
        return new LobbyServer(new SessionRegistry(), new TokenStore(tempDir), new PlayerStore(new File(tempDir, "players")), (SwitchInitiator) null);
    }

    private static ChannelInitializer<Channel> noopInitializer() {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(final Channel ch) {
            }
        };
    }

    @Test
    void lobbyProtocolVersionIsPinned() {
        assertEquals(ProtocolVersion.v1_21_4, LobbyProtocol.VERSION);
    }

    @Test
    void startBindsToEphemeralLocalPort() {
        final LobbyServer server = newLobbyServer();
        server.start();
        try {
            final InetSocketAddress local = (InetSocketAddress) server.localAddress();
            assertTrue(local.getPort() > 0);
        } finally {
            server.stop();
        }
        assertThrows(IllegalStateException.class, server::localAddress);
    }

    @Test
    void occupiedPortThrowsLobbyBindException() throws Exception {
        final Channel occupier = new ServerBootstrap()
                .group(GROUP, GROUP)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, false)
                .childHandler(noopInitializer())
                .bind("127.0.0.1", 0)
                .sync()
                .channel();
        final int port = ((InetSocketAddress) occupier.localAddress()).getPort();
        try {
            final LobbyServer server = newLobbyServer();
            final LobbyBindException exception = assertThrows(LobbyBindException.class, () -> server.start(port));
            assertEquals(port, exception.getPort());
            assertThrows(IllegalStateException.class, server::localAddress);
        } finally {
            occupier.close().syncUninterruptibly();
        }
    }
}
