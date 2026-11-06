package dev.connectplus.compat;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.channel.*;
import net.raphimc.netminecraft.constants.*;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.netty.connection.NetServer;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.impl.handshaking.C2SHandshakingClientIntentionPacket;
import net.raphimc.netminecraft.packet.impl.status.*;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolAutoDetectionTest {
    @org.junit.jupiter.api.Test @Timeout(15)
    void newClientDetectsAnOlderVanillaServer() {
        assertEquals(765, probeFixture(ProtocolVersion.v26_3.getOriginalVersion(), requested -> 765));
    }

    @org.junit.jupiter.api.Test @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void rejectedNewClientProbeStillDetectsTheOlderServer() {
        assertEquals(765, probeFixture(ProtocolVersion.v26_3.getOriginalVersion(),
                requested -> requested < 0 ? 765 : null),
                "Rejecting the client-version status handshake must not prevent independent detection");
    }

    @org.junit.jupiter.api.Test @Timeout(value = 8, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void silentClientProbeTimesOutBeforeIndependentDetection() {
        assertEquals(765, probeFixture(777, requested -> requested < 0 ? 765 : null,
                false, "Status fixture", false));
    }

    @org.junit.jupiter.api.Test @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void failedProbesNeverInventAServerVersion() {
        assertThrows(RuntimeException.class, () -> probeFixture(ProtocolVersion.v26_3.getOriginalVersion(),
                requested -> null));
    }

    @org.junit.jupiter.api.Test @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void invalidIndependentResponseCannotRecoverAFailedClientProbe() {
        assertThrows(RuntimeException.class, () -> probeFixture(ProtocolVersion.v26_3.getOriginalVersion(),
                requested -> requested < 0 ? -1 : null));
    }

    @ParameterizedTest @ValueSource(ints = {575, 578, 777}) @Timeout(15)
    void velocityEchoDoesNotMistakeTheClientForTheTargetVersion(int clientProtocol) {
        assertEquals(765, probeFixture(clientProtocol, requested -> requested < 0 ? 765 : requested),
                "Velocity mirrors supported clients but exposes its maximum for an unknown status protocol");
    }

    @org.junit.jupiter.api.Test @Timeout(15)
    void matchingVanillaVersionIsRetained() {
        assertEquals(578, probeFixture(578, requested -> 578));
    }

    @org.junit.jupiter.api.Test @Timeout(15)
    void rejectedIndependentProbeKeepsTheSuccessfulOriginalResponse() {
        assertEquals(578, probeFixture(578, requested -> requested < 0 ? null : 578));
    }

    @org.junit.jupiter.api.Test @Timeout(15)
    void anUnknownEchoNeverBecomesTheLoginVersion() {
        assertEquals(578, probeFixture(578, requested -> requested));
    }

    @org.junit.jupiter.api.Test @Timeout(15)
    void aValidIndependentStatusResponseDoesNotRequireAPong() {
        assertEquals(765, probeFixture(578, requested -> requested < 0 ? 765 : requested, true));
    }

    @org.junit.jupiter.api.Test @Timeout(15)
    void unregisteredProtocolRetainsTheHostsVersionNameFallback() {
        assertEquals(765, probeFixture(777, requested -> 999999, false, "Velocity 1.7.2-1.20.4"));
    }

    @org.junit.jupiter.api.Test @Timeout(15)
    void unregisteredProtocolWithoutARecognizableNameFailsDetection() {
        assertThrows(RuntimeException.class, () -> probeFixture(777, requested -> 999999));
    }

    private int probeFixture(int clientProtocol, java.util.function.IntFunction<Integer> response) {
        return probeFixture(clientProtocol, response, false);
    }

    private int probeFixture(int clientProtocol, java.util.function.IntFunction<Integer> response,
                             boolean closeAfterIndependentStatus) {
        return probeFixture(clientProtocol, response, closeAfterIndependentStatus, "Status fixture");
    }

    private int probeFixture(int clientProtocol, java.util.function.IntFunction<Integer> response,
                             boolean closeAfterIndependentStatus, String versionName) {
        return probeFixture(clientProtocol, response, closeAfterIndependentStatus, versionName, true);
    }

    private int probeFixture(int clientProtocol, java.util.function.IntFunction<Integer> response,
                             boolean closeAfterIndependentStatus, String versionName, boolean closeRejectedStatus) {
        dev.connectplus.testutil.ViaProxyTestConfig.init();
        final Set<Channel> children = ConcurrentHashMap.newKeySet();
        final NetServer server = new NetServer(new MinecraftChannelInitializer(() -> new SimpleChannelInboundHandler<Packet>() {
            private int requestedProtocol;
            @Override public void handlerAdded(ChannelHandlerContext ctx) { children.add(ctx.channel()); }
            @Override protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
                if (packet instanceof C2SHandshakingClientIntentionPacket handshake) {
                    requestedProtocol = handshake.protocolVersion;
                    ctx.channel().attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get()
                            .setConnectionState(handshake.intendedState.getConnectionState());
                } else if (packet instanceof C2SStatusRequestPacket) {
                    final Integer protocol = response.apply(requestedProtocol);
                    if (protocol == null) {
                        if (closeRejectedStatus) ctx.close();
                        return;
                    }
                    ctx.writeAndFlush(new S2CStatusResponsePacket("{\"version\":{\"name\":\"" + versionName + "\",\"protocol\":"
                            + protocol + "},\"players\":{\"max\":20,\"online\":0},\"description\":{\"text\":\"test\"}}"))
                            .addListener(f -> { if (requestedProtocol < 0 && closeAfterIndependentStatus) ctx.close(); });
                } else if (packet instanceof C2SStatusPingRequestPacket ping) {
                    ctx.writeAndFlush(new S2CStatusPongResponsePacket(ping.startTime));
                }
            }
        }) {
            @Override protected void initChannel(Channel channel) {
                super.initChannel(channel);
                channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(false, 774));
            }
        });
        try {
            server.bind(new InetSocketAddress("127.0.0.1", 0), false);
            return CompatSwitchSupport.detectProtocolVersion(server.getChannel().localAddress(),
                    ProtocolVersion.getProtocol(clientProtocol)).getOriginalVersion();
        } finally {
            if (server.getChannel() != null) server.getChannel().close().syncUninterruptibly();
            for (Channel channel : children) channel.close().syncUninterruptibly();
        }
    }

    @ParameterizedTest @ValueSource(ints = {758, 765, 769, 774}) @Timeout(15)
    void detectionUsesTheServersAdvertisedVersionRatherThanTheClientVersion(int serverProtocol) {
        dev.connectplus.testutil.ViaProxyTestConfig.init();
        final Set<Channel> children = ConcurrentHashMap.newKeySet();
        final AtomicInteger requests = new AtomicInteger();
        final NetServer server = new NetServer(new MinecraftChannelInitializer(() -> new SimpleChannelInboundHandler<Packet>() {
            @Override public void handlerAdded(ChannelHandlerContext ctx) { children.add(ctx.channel()); }
            @Override protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
                if (packet instanceof C2SHandshakingClientIntentionPacket handshake) {
                    ctx.channel().attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get().setConnectionState(handshake.intendedState.getConnectionState());
                } else if (packet instanceof C2SStatusRequestPacket) {
                    requests.incrementAndGet();
                    ctx.writeAndFlush(new S2CStatusResponsePacket("{\"version\":{\"name\":\"Detection fixture\",\"protocol\":"
                            + serverProtocol + "},\"players\":{\"max\":20,\"online\":0},\"description\":{\"text\":\"test\"}}"));
                } else if (packet instanceof C2SStatusPingRequestPacket ping) {
                    ctx.writeAndFlush(new S2CStatusPongResponsePacket(ping.startTime));
                }
            }
        }) {
            @Override protected void initChannel(Channel channel) {
                super.initChannel(channel);
                channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).set(new DefaultPacketRegistry(false, 774));
            }
        });
        try {
            server.bind(new InetSocketAddress("127.0.0.1", 0), false);
            final ProtocolVersion client = ProtocolVersion.v1_21_4;
            final ProtocolVersion detected = CompatSwitchSupport.detectProtocolVersion(server.getChannel().localAddress(), client);
            assertEquals(serverProtocol, detected.getOriginalVersion(), "Use the status response even when the client is another version");
            assertTrue(requests.get() > 0, "Detection must actually query the target server");
        } finally {
            if (server.getChannel() != null) server.getChannel().close().syncUninterruptibly();
            for (Channel channel : children) channel.close().syncUninterruptibly();
        }
    }
}
