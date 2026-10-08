package dev.connectplus.lobby;

import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import dev.connectplus.lobby.protocol.packets.play.S2CLevelChunkWithLightPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket;
import dev.connectplus.lobby.states.StateHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LobbySpawnSafetyTest {
    @Test
    void spawnHasRoomForGeysersVirtualContainerOnEitherSideOfThePlayer() {
        var channel = new EmbeddedChannel();
        try {
            LobbyConstants.sendSpawnInfo(new StateHandler(null, channel));
            var spawn = channel.outboundMessages().stream().filter(S2CPlayerPositionPacket.class::isInstance)
                    .map(S2CPlayerPositionPacket.class::cast).findFirst().orElseThrow();
            assertTrue(spawn.posY >= 16 && spawn.posY < 240,
                    "The End lobby must leave a margin above/below the player for native Bedrock container holders");
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void encodedLobbyChunksProvideInvisibleSupportWithoutStoneAtSpawn() {
        var channel = new EmbeddedChannel();
        try {
            LobbyConstants.sendSpawnInfo(new StateHandler(null, channel));
            var spawn = channel.outboundMessages().stream().filter(S2CPlayerPositionPacket.class::isInstance)
                    .map(S2CPlayerPositionPacket.class::cast).findFirst().orElseThrow();
            List<S2CLevelChunkWithLightPacket> chunks = new ArrayList<>();
            for (var packet : channel.outboundMessages()) {
                if (!(packet instanceof S2CLevelChunkWithLightPacket chunk)) continue;
                var wire = Unpooled.buffer();
                try {
                    chunk.write(wire, 769);
                    var decoded = new S2CLevelChunkWithLightPacket();
                    decoded.read(wire, 769);
                    assertEquals(0, wire.readableBytes());
                    for (var section : decoded.chunk.getSections()) {
                        int populated = 0;
                        for (int index = 0; index < 4096; index++) {
                            if (section.palette(PaletteType.BLOCKS).idAt(index) != 0) populated++;
                        }
                        assertEquals(populated, section.getNonAirBlocksCount(), "Geyser skips sections incorrectly marked empty");
                    }
                    chunks.add(decoded);
                } finally { wire.release(); }
            }
            int y = (int) Math.floor(spawn.posY);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                int x = (int) Math.floor(spawn.posX) + dx, z = (int) Math.floor(spawn.posZ) + dz;
                // Verified from official ViaVersion's 1.13 state enumeration
                // through its public mapping data to the fixed 1.21.4 lobby.
                assertEquals(11245, block(chunks, x, y - 1, z), "Use invisible barrier support instead of visible stone");
                assertEquals(0, block(chunks, x, y, z));
                assertEquals(0, block(chunks, x, y + 1, z));
                assertEquals(0, block(chunks, x, y + 3, z), "Leave the virtual chest holder free of block entities");
            }
        } finally { channel.finishAndReleaseAll(); }
    }

    private static int block(List<S2CLevelChunkWithLightPacket> chunks, int x, int y, int z) {
        var chunk = chunks.stream().filter(p -> p.chunk.getX() == (x >> 4) && p.chunk.getZ() == (z >> 4))
                .findFirst().orElseThrow().chunk;
        return chunk.getSections()[y >> 4].palette(PaletteType.BLOCKS).idAt(x & 15, y & 15, z & 15);
    }
}
