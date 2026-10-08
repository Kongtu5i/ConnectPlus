package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import dev.connectplus.testutil.ModernWorldPackets;
import dev.connectplus.testutil.OlderWorldPackets;
import net.raphimc.netminecraft.constants.MCPackets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A host dependency upgrade must review every new release protocol before shipping. */
class WorldResetUpgradeGateTest {
    @Test
    void everyOfficialRuntimeReleaseCanBuildItsWorldReset() {
        for (final var protocol : ProtocolVersion.getProtocols()) {
            if (protocol.getVersionType() != VersionType.RELEASE || protocol.olderThan(ProtocolVersion.v1_7_2)
                    || protocol.isSnapshot() || !protocol.isKnown()) continue;
            final int version = protocol.getVersion();
            assertTrue(TabListState.supports(version), "Audit player-list tracking and removal for new runtime protocol " + protocol);
            assertTrue(ScoreboardState.supports(version), "Audit scoreboard tracking and removal for new runtime protocol " + protocol);
            final var join = version >= 764 ? ModernWorldPackets.join(version, true) : OlderWorldPackets.join(version);
            final var resets = assertDoesNotThrow(() -> WorldReset.fromJoin(join, version),
                    "Audit JoinGame and Respawn for new runtime protocol " + protocol + " before releasing ConnectPlus");
            assertFalse(resets.isEmpty(), protocol.toString());
            for (final var reset : resets) {
                assertEquals(MCPackets.S2C_RESPAWN.getId(version), reset.packetId, protocol.toString());
                assertTrue(reset.packetId >= 0 && reset.data.length > 0, protocol.toString());
            }
        }
    }
}
