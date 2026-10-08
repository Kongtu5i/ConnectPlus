package dev.connectplus.lobby;

import dev.connectplus.logging.DebugLog;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.identity.IdentityLinkService;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionLeaseGranter;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.switching.SwitchInitiator;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import dev.connectplus.lobby.haproxy.HAProxyDetectHandler;
import io.netty.channel.Channel;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;

import javax.annotation.Nullable;

/**
 * Channel pipeline for the lobby server. Derived from MiniConnect's
 * LobbyServerInitializer (MIT, Copyright (c) 2024 Lenni0451).
 *
 * <p>Deviation from MiniConnect: the HAProxy decoder is not installed unconditionally
 * (netty's decoder would stall bare connections); {@link HAProxyDetectHandler} only
 * installs it for connections that actually start with a proxy protocol preamble.</p>
 */
public class LobbyChannelInitializer extends MinecraftChannelInitializer {

    public LobbyChannelInitializer(final Set<Channel> sessions, final SessionRegistry sessionRegistry, final TokenStore tokenStore, final PlayerStore playerStore, final IdentityLinkStore identityLinkStore, final SessionLeaseGranter leaseGranter, final SwitchInitiator switchInitiator, final ExecutorService storageExecutor, final AtomicInteger uncaughtExceptions) {
        this(sessions, sessionRegistry, tokenStore, playerStore, identityLinkStore, leaseGranter, switchInitiator, storageExecutor, uncaughtExceptions, null);
    }

    public LobbyChannelInitializer(final Set<Channel> sessions, final SessionRegistry sessionRegistry, final TokenStore tokenStore, final PlayerStore playerStore, final IdentityLinkStore identityLinkStore, final SessionLeaseGranter leaseGranter, final SwitchInitiator switchInitiator, final ExecutorService storageExecutor, final AtomicInteger uncaughtExceptions, @Nullable final IdentityLinkService linkService) {
        this(sessions, sessionRegistry, tokenStore, playerStore, identityLinkStore, leaseGranter, switchInitiator, storageExecutor, uncaughtExceptions, linkService, null);
    }

    public LobbyChannelInitializer(final Set<Channel> sessions, final SessionRegistry sessionRegistry, final TokenStore tokenStore, final PlayerStore playerStore, final IdentityLinkStore identityLinkStore, final SessionLeaseGranter leaseGranter, final SwitchInitiator switchInitiator, final ExecutorService storageExecutor, final AtomicInteger uncaughtExceptions, @Nullable final IdentityLinkService linkService, @Nullable final dev.connectplus.session.PlayerVisitStore visitStore) {
        super(() -> new LobbyServerHandler(sessions, sessionRegistry, tokenStore, playerStore, identityLinkStore, leaseGranter, switchInitiator, storageExecutor, uncaughtExceptions, linkService, visitStore));
    }

    @Override
    protected void initChannel(final Channel channel) {
        channel.pipeline().addLast("connectplus-haproxy-detect", new HAProxyDetectHandler());
        super.initChannel(channel);
        channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new dev.connectplus.lobby.protocol.LobbyPacketRegistry());
        //Opt-in diagnostic: dump large inbound client packets (chat / session updates / clicks)
        //before the packet codec decodes them. A decode failure on this channel ("byte
        //array bigger than the maximum allowed") means a real client's packet layout is
        //not fully consumed by a lobby reader; the dump shows the raw bytes to fix it.
        final String codecName = channel.pipeline().names().stream()
                .filter(n -> channel.pipeline().get(n) instanceof net.raphimc.netminecraft.netty.codec.PacketCodec)
                .findFirst().orElse(null);
        if (codecName != null) {
            channel.pipeline().addBefore(codecName, "connectplus-c2s-dump", new io.netty.channel.ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(final io.netty.channel.ChannelHandlerContext ctx, final Object msg) {
                    if (DebugLog.enabled() && msg instanceof io.netty.buffer.ByteBuf buf && buf.readableBytes() >= 200) {
                        final io.netty.buffer.ByteBuf view = buf.duplicate();
                        final int id = view.getUnsignedByte(0); //first byte of the id varint (play-state ids here are < 128)
                        final int size = view.readableBytes();
                        DebugLog.log("[C2S dump] state={} id=0x{} {}B hex={}",
                                channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get().getConnectionState(),
                                Integer.toHexString(id), size,
                                io.netty.buffer.ByteBufUtil.hexDump(view, 0, Math.min(size, 96)));
                    }
                    ctx.fireChannelRead(msg);
                }
            });
        }
    }
}
