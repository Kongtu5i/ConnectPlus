package dev.connectplus.switching;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.util.function.Supplier;

/** Retains client-format settings across backend replacements on one connection. */
final class ClientSettings {
    // Settings contain a short locale and scalar options. Do not retain arbitrary
    // oversized client payloads for the lifetime of a connection.
    private static final int MAX_BYTES = 4096;
    private final ProxyConnection connection;
    private final Supplier<SwitchJob> current;
    private volatile byte[] latest;
    // Only accessed on the backend event loop; each backend has its own replay.
    private Channel sentBackend;
    private byte[] sentSettings;

    ClientSettings(final ProxyConnection connection, final Supplier<SwitchJob> current) {
        this.connection = connection;
        this.current = current;
    }

    void capture(final Packet packet) {
        if (!(packet instanceof UnknownPacket raw) || raw.data == null
                || raw.data.length == 0 || raw.data.length > MAX_BYTES) return;
        final int version = this.connection.getClientVersion().getVersion();
        final ConnectionState state = this.connection.getC2pConnectionState();
        final MCPackets type;
        if (state == ConnectionState.PLAY) type = MCPackets.C2S_CLIENT_INFORMATION;
        else if (state == ConnectionState.CONFIGURATION) type = MCPackets.C2S_CONFIG_CLIENT_INFORMATION;
        else return;
        final int id = type.getId(version);
        if (id >= 0 && raw.packetId == id) this.latest = raw.data.clone();
    }

    void replay(final SwitchJob job, final Channel backend, final ConnectionState state) {
        if (backend == null) return;
        final Runnable send = () -> {
            if (this.current.get() != job || job.isFailed() || this.connection.getChannel() != backend
                    || !backend.isActive() || !this.connection.getC2P().isActive()
                    || this.connection.getP2sConnectionState() != state) return;
            // Read inside the backend loop: a settings change queued by the client
            // must not be overwritten by an older snapshot scheduled from another loop.
            final byte[] payload = this.latest;
            if (payload == null || (this.sentBackend == backend && this.sentSettings == payload)) return;
            final MCPackets type = state == ConnectionState.CONFIGURATION
                    ? MCPackets.C2S_CONFIG_CLIENT_INFORMATION : MCPackets.C2S_CLIENT_INFORMATION;
            final int id = type.getId(this.connection.getClientVersion().getVersion());
            if (id < 0) return;
            backend.writeAndFlush(new UnknownPacket(id, payload.clone()))
                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            this.sentBackend = backend;
            this.sentSettings = payload;
        };
        if (backend.eventLoop().inEventLoop()) send.run();
        else backend.eventLoop().execute(send);
    }
}
