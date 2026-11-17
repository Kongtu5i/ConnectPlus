package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.packet.UnknownPacket;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MemoryBudgetTest {
    @Test void configurationBudgetIsSharedAndReturnedOnDisconnect() {
        ViaProxyTestConfig.init();
        var clients = new ArrayList<EmbeddedChannel>();
        var failures = new AtomicInteger();
        try {
            byte[] block = new byte[8 * 1024 * 1024];
            for (int i = 0; i < 5; i++) {
                EmbeddedChannel c = new EmbeddedChannel(); clients.add(c);
                var engine = new SwitchEngine(() -> null);
                var pc = MemoryLifecycleTest.connection(c, engine);
                pc.setClientVersion(ProtocolVersion.v1_21_4);
                var job = SwitchJob.starting(new SwitchJob.Target("example.invalid:1", null, java.util.UUID.randomUUID(), "Budget", false, 1));
                var configuration = new ClientConfiguration(pc, () -> job, (j, reason) -> failures.incrementAndGet());
                configuration.begin(job);
                for (int p = 0; p < 4; p++) configuration.accept(job, new UnknownPacket(0, block));
            }
            assertTrue(failures.get() > 0, "Five clients must not independently reserve 160 MiB");
        } finally { clients.forEach(EmbeddedChannel::finishAndReleaseAll); }
        EmbeddedChannel fresh = new EmbeddedChannel();
        try {
            var pc = MemoryLifecycleTest.connection(fresh, new SwitchEngine(() -> null));
            var job = SwitchJob.starting(new SwitchJob.Target("example.invalid:1", null, java.util.UUID.randomUUID(), "Budget", false, 1));
            int before = failures.get();
            var configuration = new ClientConfiguration(pc, () -> job, (j, reason) -> failures.incrementAndGet());
            configuration.accept(job, new UnknownPacket(0, new byte[1024]));
            assertEquals(before, failures.get(), "Disconnected clients must return the shared budget");
        } finally { fresh.finishAndReleaseAll(); }
    }

    @Test void scoreboardAndWaypointsEnforceBothCountAndByteLimits() {
        var scoreboard = new ScoreboardState();
        assertThrows(RuntimeException.class, () -> {
            for (int i = 0; i < 4097; i++) scoreboard.observe(
                    dev.connectplus.testutil.ScoreboardPackets.objective(769, "objective" + i, 0), 769);
        });
        var longNames = new ScoreboardState();
        assertThrows(RuntimeException.class, () -> {
            for (int i = 0; i < 100; i++) longNames.observe(
                    dev.connectplus.testutil.ScoreboardPackets.objective(769, "x".repeat(16000) + i, 0), 769);
        });
        var markers = new WaypointState();
        assertThrows(RuntimeException.class, () -> {
            for (int i = 0; i < 4097; i++) {
                var bytes = io.netty.buffer.Unpooled.buffer();
                try {
                    net.raphimc.netminecraft.packet.PacketTypes.writeVarInt(bytes, 0);
                    bytes.writeBoolean(true).writeLong(0).writeLong(i);
                    net.raphimc.netminecraft.packet.PacketTypes.writeString(bytes, "minecraft:default");
                    bytes.writeBoolean(false).writeByte(0);
                    markers.observe(new UnknownPacket(0, io.netty.buffer.ByteBufUtil.getBytes(bytes)));
                } finally { bytes.release(); }
            }
        });
    }
}
