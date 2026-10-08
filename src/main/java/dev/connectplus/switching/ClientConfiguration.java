package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigKeepAlivePacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.play.C2SPlayConfigurationAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayStartConfigurationPacket;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import io.netty.channel.ChannelFutureListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry;

import java.util.ArrayDeque;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Frontend configuration handshake, using ordinary public packet/connection APIs. */
final class ClientConfiguration {
    private enum Phase { PLAY, START_ACK, CONFIGURATION, FINISH_ACK }
    private final ProxyConnection connection;
    private final Supplier<SwitchJob> current;
    private final BiConsumer<SwitchJob, String> failure;
    private static final int MAX_BYTES = 32 * 1024 * 1024, MAX_PACKETS = 4096;
    private static final class Buffered {
        final Packet packet;
        final int size;
        boolean released;
        Buffered(Packet packet, int size) { this.packet = packet; this.size = size; }
    }
    private final ArrayDeque<Buffered> queued = new ArrayDeque<>();
    private Phase phase = Phase.PLAY;
    private SwitchJob pending, finishOwner;
    private boolean backendReady;
    private final Object budgetLock = new Object();
    private int reservedBytes, reservedPackets;
    private final Object readLock = new Object();
    private boolean readsPaused, previousAutoRead;

    ClientConfiguration(ProxyConnection connection, Supplier<SwitchJob> current, BiConsumer<SwitchJob, String> failure) {
        this.connection = connection;
        this.current = current;
        this.failure = failure;
        connection.getC2P().closeFuture().addListener(f -> clearQueue());
    }

    boolean enabled() {
        final ProtocolVersion version = connection.getClientVersion();
        return version != null && version.newerThanOrEqualTo(ProtocolVersion.v1_20_2)
                && WorldReset.supports(version.getOriginalVersion());
    }

    boolean waitingForClient() { return enabled() && phase != Phase.PLAY; }

    /** Retain input on the socket while the native host has no open backend. */
    void pauseReads() {
        if (!enabled()) return;
        synchronized (readLock) {
            if (readsPaused) return;
            previousAutoRead = connection.getC2P().config().isAutoRead();
            readsPaused = true;
            connection.getC2P().config().setAutoRead(false);
        }
    }

    private void resumeReads() {
        synchronized (readLock) {
            if (!readsPaused) return;
            readsPaused = false;
            connection.getC2P().config().setAutoRead(previousAutoRead);
        }
    }

    void begin(SwitchJob job) {
        if (!enabled()) return;
        onLoop(job, () -> {
            pending = job;
            backendReady = false;
            clearQueue();
            //Keep outstanding frontend ACKs across a failed backend/lobby recovery.
            if (phase == Phase.PLAY && connection.getC2pConnectionState() == ConnectionState.CONFIGURATION) phase = Phase.CONFIGURATION;
        });
    }

    void loggedIn(SwitchJob job) {
        if (!enabled()) return;
        onLoop(job, () -> {
            backendReady = true;
            if (phase == Phase.PLAY) start(job);
            else if (phase == Phase.CONFIGURATION) flush(job);
            resumeReads();
        });
    }

    private void start(SwitchJob job) {
        phase = Phase.START_ACK;
        connection.getC2P().writeAndFlush(new S2CPlayStartConfigurationPacket())
                .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    void accept(SwitchJob job, Packet packet) {
        if (!enabled() || current.get() != job || !job.isSwitching() || !connection.getC2P().isActive()) return;
        final Buffered entry;
        if (packet instanceof UnknownPacket raw) {
            if (raw.data == null) throw new IllegalArgumentException("Missing configuration payload");
            if (!reserve(job, raw.data.length)) return;
            entry = new Buffered(new UnknownPacket(raw.packetId, raw.data.clone()), raw.data.length);
        } else {
            //Bound even the temporary typed encoding, then reserve before copying/scheduling.
            final ByteBuf encoded = Unpooled.buffer(256, MAX_BYTES);
            try {
                packet.write(encoded, connection.getClientVersion().getVersion());
                final int size = encoded.readableBytes();
                final var registry = new DefaultPacketRegistry(false, connection.getClientVersion().getVersion());
                registry.setConnectionState(ConnectionState.CONFIGURATION);
                final int id = registry.getPacketId(packet);
                if (!reserve(job, size)) return;
                entry = new Buffered(packet instanceof S2CConfigFinishConfigurationPacket
                        ? new S2CConfigFinishConfigurationPacket() : new UnknownPacket(id, ByteBufUtil.getBytes(encoded)), size);
            } finally { encoded.release(); }
        }
        final Runnable delivery = () -> {
            if (current.get() != job || !job.isSwitching() || !connection.getC2P().isActive()) { release(entry); return; }
            try {
                queued.add(entry);
                if (phase == Phase.CONFIGURATION && backendReady) flush(job);
            } catch (RuntimeException error) { clearQueue(); release(entry); failure.accept(job, error.getMessage()); }
        };
        try {
            if (connection.getC2P().eventLoop().inEventLoop()) delivery.run();
            else connection.getC2P().eventLoop().execute(delivery);
        } catch (RuntimeException error) { release(entry); throw error; }
    }

    private boolean reserve(SwitchJob job, int size) {
        synchronized (budgetLock) {
            if (reservedPackets < MAX_PACKETS && size <= MAX_BYTES - reservedBytes) {
                reservedPackets++; reservedBytes += size; return true;
            }
        }
        failure.accept(job, "Target configuration exceeds CP buffer limit");
        return false;
    }

    private void release(Buffered entry) {
        synchronized (budgetLock) {
            if (entry.released) return;
            entry.released = true;
            reservedPackets--; reservedBytes -= entry.size;
        }
    }

    private void clearQueue() { while (!queued.isEmpty()) release(queued.remove()); }

    private void flush(SwitchJob job) {
        while (phase == Phase.CONFIGURATION && !queued.isEmpty()) {
            final Buffered entry = queued.remove();
            final Packet packet = entry.packet;
            try {
                connection.getC2P().writeAndFlush(packet).addListener(f -> {
                    release(entry);
                    ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE.operationComplete((io.netty.channel.ChannelFuture) f);
                });
            } catch (RuntimeException error) { release(entry); throw error; }
            if (packet instanceof S2CConfigFinishConfigurationPacket) {
                finishOwner = job;
                phase = Phase.FINISH_ACK;
            }
        }
    }

    /** Runs on the frontend event loop. True means ordinary host forwarding. */
    boolean handleClient(Packet packet, SwitchJob job) {
        if (!enabled()) return false;
        if (packet instanceof C2SPlayConfigurationAcknowledgedPacket) {
            if (phase != Phase.START_ACK) return false;
            connection.setC2pConnectionState(ConnectionState.CONFIGURATION);
            phase = Phase.CONFIGURATION;
            if (job != null && job.isSwitching() && pending == job && backendReady) flush(job);
            return false; //This ACK belongs to CP's synthetic frontend start, not backend login.
        }
        if (packet instanceof C2SConfigKeepAlivePacket) return false;
        if (packet instanceof C2SConfigFinishConfigurationPacket) {
            if (phase != Phase.FINISH_ACK) return false;
            phase = Phase.PLAY;
            connection.setC2pConnectionState(ConnectionState.PLAY);
            if (job != null && job.isSwitching() && finishOwner == job && pending == job && backendReady) {
                backendReady = false;
                return true; //Native ConfigurationPacketHandler and its actual write listener finish p2s.
            }
            //An old backend finished just before recovery. Never acknowledge the new backend early.
            if (job != null && job.isSwitching() && pending == job && backendReady) start(job);
            return false;
        }
        return job != null && job.isSwitching() && phase == Phase.CONFIGURATION && pending == job && backendReady
                && connection.getP2sConnectionState() == ConnectionState.CONFIGURATION;
    }

    /** Observe normal live-backend reconfiguration without changing native handling. */
    void observeServer(Packet packet, SwitchJob job) {
        if (!enabled()) return;
        if (packet instanceof S2CPlayStartConfigurationPacket) observeOnLoop(() -> phase = Phase.START_ACK);
        else if (packet instanceof S2CConfigFinishConfigurationPacket) observeOnLoop(() -> {
            phase = Phase.FINISH_ACK; finishOwner = job;
        });
    }

    private void observeOnLoop(Runnable action) {
        //Native forwarding has already accepted the packet. Its queued client write
        //survives backend replacement, so this frontend observation must survive too.
        final Runnable observe = () -> { if (connection.getC2P().isActive()) action.run(); };
        if (connection.getC2P().eventLoop().inEventLoop()) observe.run();
        else connection.getC2P().eventLoop().execute(observe);
    }

    void observeClient(Packet packet) {
        if (packet instanceof C2SPlayConfigurationAcknowledgedPacket) phase = Phase.CONFIGURATION;
        else if (packet instanceof C2SConfigFinishConfigurationPacket) phase = Phase.PLAY;
    }

    private void onLoop(SwitchJob job, Runnable action) {
        final Runnable guarded = () -> {
            if (current.get() != job || !connection.getC2P().isActive()) return;
            try { action.run(); }
            catch (RuntimeException error) { failure.accept(job, error.getMessage()); }
        };
        if (connection.getC2P().eventLoop().inEventLoop()) guarded.run();
        else connection.getC2P().eventLoop().execute(guarded);
    }
}
