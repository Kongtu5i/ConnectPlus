package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayKeepAlivePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keepalive generation gate (M7): pre-1.8 clients were excluded from the
 * switch/reconnect keepalives because of an M4-era wire format concern. The
 * concern does not hold — NetMinecraft's S2CKeepAlivePacket picks the payload
 * encoding from the protocol version it is written with (long >= 340, varint
 * 47..339, int < 47) and the c2p codec always passes the client version — so
 * 1.7 clients get keepalives too. The wire format test pins exactly that
 * dependency: a NetMinecraft upgrade dropping the version-aware write turns
 * this red before any 1.7 player can be hurt by a malformed keepalive.
 */
class SwitchEngineKeepAliveTest {

    @Test
    void keepAliveGateCoversOnePointSevenAndNewer() {
        assertTrue(SwitchEngine.keepAliveSupported(ProtocolVersion.v1_7_2), "1.7 clients must be kept alive during switches");
        assertTrue(SwitchEngine.keepAliveSupported(ProtocolVersion.v1_7_6), "1.7.6+ clients must be kept alive during switches");
        assertTrue(SwitchEngine.keepAliveSupported(ProtocolVersion.v1_8), "1.8 keepalive support must stay");
        assertTrue(SwitchEngine.keepAliveSupported(ProtocolVersion.v1_21_4), "modern keepalive support must stay");
    }

    @Test
    void onePointSevenKeepAliveWireFormatIsInt() {
        final S2CPlayKeepAlivePacket packet = new S2CPlayKeepAlivePacket(7);

        final io.netty.buffer.ByteBuf legacy = Unpooled.buffer();
        packet.write(legacy, ProtocolVersion.v1_7_2.getVersion());
        assertEquals(4, legacy.readableBytes(), "A 1.7 client keepalive must carry a 4-byte int id");
        assertEquals(7, legacy.readInt());

        final io.netty.buffer.ByteBuf modern = Unpooled.buffer();
        packet.write(modern, ProtocolVersion.v1_21_4.getVersion());
        assertEquals(8, modern.readableBytes(), "A 1.13+ client keepalive must carry an 8-byte long id");
        assertEquals(7L, modern.readLong());
    }
}
