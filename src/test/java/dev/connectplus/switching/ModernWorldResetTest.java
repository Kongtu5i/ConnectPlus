package dev.connectplus.switching;

import dev.connectplus.testutil.ModernWorldPackets;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.UnknownPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ModernWorldResetTest {
    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    void java26_2RespawnPreservesSpawnAndExcludesJoinOnlyFlags(final boolean deathLocation,
                                                             final boolean onlineMode,
                                                             final boolean secureChat) {
        final UnknownPacket join = ModernWorldPackets.join(776, deathLocation, onlineMode, secureChat);
        final byte[] original = join.data.clone();
        final var resets = WorldReset.fromJoin(join, 776);
        assertEquals(1, resets.size());
        assertEquals(MCPackets.S2C_RESPAWN.getId(776), resets.get(0).packetId);
        assertArrayEquals(ModernWorldPackets.respawn(776, deathLocation), resets.get(0).data);
        assertArrayEquals(original, join.data, "The original JoinGame still goes to the client unchanged");
    }

    @Test
    void futureProtocolStillRequiresAnAudit() {
        final var error = assertThrows(IllegalArgumentException.class,
                () -> WorldReset.fromJoin(new UnknownPacket(0, new byte[0]), 778));
        assertEquals("Unaudited world reset protocol: 778", error.getMessage());
    }

    @Test
    void java26_2JoinWithExtraTrailerIsRejected() {
        final UnknownPacket join = ModernWorldPackets.join(776);
        final byte[] payload = Arrays.copyOf(join.data, join.data.length + 1);
        final var error = assertThrows(IllegalArgumentException.class,
                () -> WorldReset.fromJoin(new UnknownPacket(join.packetId, payload), 776));
        assertEquals("Unexpected JoinGame trailer", error.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    void java26_3RespawnUsesVarIntGamemodesAndExcludesJoinFlags(final boolean deathLocation,
                                                            final boolean onlineMode,
                                                            final boolean secureChat) {
        final var resets = WorldReset.fromJoin(ModernWorldPackets.join(777, deathLocation, onlineMode, secureChat), 777);
        assertEquals(1, resets.size());
        assertEquals(MCPackets.S2C_RESPAWN.getId(777), resets.get(0).packetId);
        assertArrayEquals(ModernWorldPackets.respawn(777, deathLocation), resets.get(0).data);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {48, 750, 778, 800, 1073742601})
    void unknownGapsAndFutureProtocolsAreRejectedByTheDecoder(final int version) {
        final var error = assertThrows(IllegalArgumentException.class,
                () -> WorldReset.fromJoin(new UnknownPacket(0, new byte[0]), version));
        assertEquals("Unaudited world reset protocol: " + version, error.getMessage());
    }

    @Test
    void java26_3GamemodesAreDecodedAsVariableWidthFields() {
        // Replacing the two VarInt reads with skipBytes(2) must fail this regression.
        final var join = ModernWorldPackets.join(777, true, true, true, true);
        final var reset = WorldReset.fromJoin(join, 777).get(0);
        assertArrayEquals(ModernWorldPackets.respawn(777, true, true), reset.data);
    }

    @Test
    void java26_3PacketIdsMatchTheOfficialViaVersionPacketTypes() {
        assertEquals(com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3.LOGIN.getId(),
                MCPackets.S2C_LOGIN.getId(777));
        assertEquals(com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3.RESPAWN.getId(),
                MCPackets.S2C_RESPAWN.getId(777));
    }
}
