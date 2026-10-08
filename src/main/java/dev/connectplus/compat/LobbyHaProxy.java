package dev.connectplus.compat;

import dev.connectplus.config.CPConfig;
import dev.connectplus.lobby.haproxy.HAProxyUtil;
import dev.connectplus.CoreMain;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.events.Proxy2ServerChannelInitializeEvent;
import net.raphimc.viaproxy.plugins.events.ViaProxyLoadedEvent;
import net.raphimc.viaproxy.plugins.events.ClientLoggedInEvent;
import dev.connectplus.session.PlayerIdentity;
import net.raphimc.viaproxy.plugins.events.types.ITyped;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.net.SocketAddress;
import java.util.UUID;
import java.util.function.Supplier;

import static net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerChannelInitializer.VIAPROXY_HAPROXY_ENCODER_NAME;

/**
 * Forwards the HAProxy proxy protocol information of connections routed into the
 * lobby from ViaProxy's p2s side: for p2s channels whose target is the built-in
 * lobby, a v2 message with the real client address, the original handshake data
 * (0xE0 TLV) and the switch link id (0xE1 TLV) is sent before any MC traffic.
 * Connections to real server targets stay vanilla-looking — a backend that did not
 * ask for proxy protocol data would reject the preamble (manual verification V2).
 *
 * <p>Derived from MiniConnect's HAProxyEnableHandler (MIT, Copyright (c) 2024 Lenni0451).</p>
 */
public class LobbyHaProxy {

    private final Supplier<SocketAddress> lobbyAddress;

    public LobbyHaProxy(final Supplier<SocketAddress> lobbyAddress) {
        this.lobbyAddress = lobbyAddress;
    }

    @EventHandler
    public void onClientLoggedIn(final ClientLoggedInEvent event) {
        final var connection = event.getProxyConnection();
        final var client = connection.getC2P();
        final var profile = connection.getGameProfile();
        if (client == null || profile == null || profile.getId() == null || profile.getName() == null
                || !Boolean.TRUE.equals(client.attr(CPAttributeKeys.ENABLE_HAPROXY).get())) return;
        // This event follows client authentication and precedes fillPlayerData.
        // Store once on c2p: p2s resets and target accounts must not change the owner
        // of persisted lobby data. Old login packets cannot carry this UUID.
        client.attr(CPAttributeKeys.PLAYER_IDENTITY).setIfAbsent(new PlayerIdentity(profile.getId(), profile.getName()));
    }

    @EventHandler
    public void onViaProxyLoaded(final ViaProxyLoadedEvent event) {
        if (!"lobby".equalsIgnoreCase(CPConfig.mode)) {
            return;
        }
        // The per-connection forwarding below conflicts with ViaProxy's global backend
        // HAProxy handling, so the global handling is turned off for lobby mode.
        ViaProxy.getConfig().setBackendHaProxy(false);
        CoreMain.logger().info("Disabled global backend HAProxy handling; ConnectPlus forwards HAProxy data per lobby connection");
    }

    @EventHandler
    public void onProxy2ServerChannelInitialize(final Proxy2ServerChannelInitializeEvent event) {
        if (!event.getType().equals(ITyped.Type.POST)) {
            return;
        }
        final ProxyConnection proxyConnection = ProxyConnection.fromChannel(event.getChannel());
        if (proxyConnection == null || proxyConnection.getC2P() == null) {
            return;
        }
        if (!proxyConnection.getC2P().hasAttr(CPAttributeKeys.ENABLE_HAPROXY)) {
            return;
        }
        final SocketAddress lobby = this.lobbyAddress.get();
        if (lobby == null || !lobby.equals(proxyConnection.getServerAddress())) {
            return;
        }
        //The link id binds this p2s channel to the lobby's accepted channel: it is
        //registered here and travels to the lobby inside the HAProxy 0xE1 TLV, where
        //HAProxyHandler stores it as the accepted channel's LOBBY_LINK_ID attribute.
        final UUID linkId = UUID.randomUUID();
        event.getChannel().pipeline().addFirst("connectplus-haproxy-writer", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                super.channelActive(ctx);
                LobbyLink.register(linkId, proxyConnection);
                // Cleanup is installed before writes, including failures building the preamble.
                ctx.channel().closeFuture().addListener(future -> LobbyLink.unregister(linkId));
                ctx.writeAndFlush(HAProxyUtil.createMessage(
                                proxyConnection.getC2P(),
                                ctx.channel(),
                                proxyConnection.getClientHandshakeAddress(),
                                proxyConnection.getClientVersion(),
                                linkId))
                        .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            }
        });
        event.getChannel().pipeline().addFirst(VIAPROXY_HAPROXY_ENCODER_NAME, HAProxyMessageEncoder.INSTANCE);
    }
}
