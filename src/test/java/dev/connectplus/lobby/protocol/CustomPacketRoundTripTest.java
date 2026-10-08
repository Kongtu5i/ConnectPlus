package dev.connectplus.lobby.protocol;

import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import dev.connectplus.lobby.LobbyConstants;
import dev.connectplus.lobby.LobbyProtocol;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerButtonClickPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetDataPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenBookPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket;
import dev.connectplus.lobby.screen.ItemBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.lenni0451.mcstructs.text.components.StringComponent;
import net.raphimc.netminecraft.packet.Packet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write->read round-trips for the ten custom GUI packets. All packets implement
 * both directions (deviation from MiniConnect) so the scripted test client can
 * encode and decode them.
 */
class CustomPacketRoundTripTest {

    private static final int PROTOCOL_VERSION = LobbyProtocol.VERSION.getVersion();

    @BeforeAll
    static void initViaVersionStructuredDataKeys() {
        //ViaVersion has a static-init cycle: VersionedTypes.<clinit> constructs Types1_20_5,
        //whose keys trigger StructuredDataKey.<clinit>, which reads VersionedTypes.V1_20_5
        //before it is assigned. Touching StructuredDataKey first resolves the cycle.
        //(Inside ViaProxy, Via initializes long before any packet encoding, so only tests care.)
        assertNotNull(StructuredDataKey.CUSTOM_NAME);
    }

    private static <T extends Packet> T roundTrip(final T packet, final Supplier<T> factory) {
        final ByteBuf buf = Unpooled.buffer();
        packet.write(buf, PROTOCOL_VERSION);
        final T read = factory.get();
        read.read(buf, PROTOCOL_VERSION);
        assertEquals(0, buf.readableBytes(), "read() must consume the whole buffer");
        return read;
    }

    @Test
    void openScreenRoundTrip() {
        final S2COpenScreenPacket read = roundTrip(
                new S2COpenScreenPacket(1, 2, new StringComponent("Lobby GUI")),
                S2COpenScreenPacket::new);
        assertEquals(1, read.id);
        assertEquals(2, read.type);
        assertEquals("Lobby GUI", read.title.asUnformattedString());
    }

    @Test
    void containerSetContentRoundTrip() {
        final int stoneId = LobbyConstants.ITEMS.indexOf("stone");
        final Item stone = ItemBuilder.item("stone").get();
        final S2CContainerSetContentPacket read = roundTrip(
                new S2CContainerSetContentPacket(1, 0, new Item[]{stone}, StructuredItem.empty()),
                S2CContainerSetContentPacket::new);
        assertEquals(1, read.windowId);
        assertEquals(0, read.revision);
        assertEquals(1, read.items.length);
        assertEquals(stoneId, read.items[0].identifier());
        assertEquals(1, read.items[0].amount());
        assertTrue(read.cursor.isEmpty());
    }

    @Test
    void containerSetDataRoundTrip() {
        final S2CContainerSetDataPacket read = roundTrip(
                new S2CContainerSetDataPacket(1, 3, 42),
                S2CContainerSetDataPacket::new);
        assertEquals(1, read.syncId);
        assertEquals(3, read.propertyId);
        assertEquals(42, read.value);
    }

    @Test
    void s2cContainerCloseRoundTrip() {
        final S2CContainerClosePacket read = roundTrip(
                new S2CContainerClosePacket(1),
                S2CContainerClosePacket::new);
        assertEquals(1, read.id);
    }

    @Test
    void openBookRoundTrip() {
        final S2COpenBookPacket read = roundTrip(
                new S2COpenBookPacket(0),
                S2COpenBookPacket::new);
        assertEquals(0, read.hand);
    }

    @Test
    void chatWithSignatureRoundTrip() {
        final byte[] signature = new byte[256];
        for (int i = 0; i < signature.length; i++) signature[i] = (byte) i;
        final C2SChatPacket packet = new C2SChatPacket(
                "hello world", Instant.ofEpochMilli(1234567890L), 42L,
                signature, 7, BitSet.valueOf(new long[]{0b101010}));
        final C2SChatPacket read = roundTrip(packet, C2SChatPacket::new);
        assertEquals(packet.message, read.message);
        assertEquals(packet.timestamp, read.timestamp);
        assertEquals(packet.salt, read.salt);
        assertEquals(java.util.Arrays.toString(packet.signature), java.util.Arrays.toString(read.signature));
        assertEquals(packet.offset, read.offset);
        assertEquals(packet.acknowledged, read.acknowledged);
    }

    @Test
    void chatWithoutSignatureRoundTrip() {
        final C2SChatPacket packet = new C2SChatPacket(
                "no signature here", Instant.ofEpochMilli(42L), 0L,
                null, 0, new BitSet(24));
        final C2SChatPacket read = roundTrip(packet, C2SChatPacket::new);
        assertEquals(packet.message, read.message);
        assertEquals(packet.timestamp, read.timestamp);
        assertEquals(packet.salt, read.salt);
        assertTrue(read.signature == null, "signature must stay absent");
        assertEquals(packet.offset, read.offset);
        assertEquals(packet.acknowledged, read.acknowledged);
    }

    @Test
    void chatCommandRoundTrip() {
        final C2SChatCommandPacket read = roundTrip(
                new C2SChatCommandPacket("connect example.com"),
                C2SChatCommandPacket::new);
        assertEquals("connect example.com", read.message);
    }

    @Test
    void containerClickRoundTrip() {
        final int stoneId = LobbyConstants.ITEMS.indexOf("stone");
        final Map<Integer, Item> modifiedStacks = new HashMap<>();
        modifiedStacks.put(12, ItemBuilder.item("stone").get());
        final C2SContainerClickPacket packet = new C2SContainerClickPacket(
                1, 0, 12, 0, 2, modifiedStacks, StructuredItem.empty());
        final C2SContainerClickPacket read = roundTrip(packet, C2SContainerClickPacket::new);
        assertEquals(1, read.containerId);
        assertEquals(0, read.revision);
        assertEquals(12, read.slot);
        assertEquals(0, read.button);
        assertEquals(2, read.action);
        assertEquals(1, read.modifiedStacks.size());
        assertEquals(stoneId, read.modifiedStacks.get(12).identifier());
        assertEquals(1, read.modifiedStacks.get(12).amount());
        assertTrue(read.item.isEmpty());
    }

    @Test
    void containerClickRejectsTooManyEntries() {
        final Map<Integer, Item> modifiedStacks = new HashMap<>();
        for (int i = 0; i < 129; i++) {
            modifiedStacks.put(i, StructuredItem.empty());
        }
        final C2SContainerClickPacket packet = new C2SContainerClickPacket(
                1, 0, 0, 0, 0, modifiedStacks, StructuredItem.empty());
        final ByteBuf buf = Unpooled.buffer();
        packet.write(buf, PROTOCOL_VERSION);
        final DecoderException e = assertThrows(DecoderException.class, () -> new C2SContainerClickPacket().read(buf, PROTOCOL_VERSION));
        assertTrue(e.getMessage().contains("128"));
    }

    @Test
    void c2sContainerCloseRoundTrip() {
        final C2SContainerClosePacket read = roundTrip(
                new C2SContainerClosePacket(1),
                C2SContainerClosePacket::new);
        assertEquals(1, read.id);
    }

    @Test
    void containerButtonClickRoundTrip() {
        final C2SContainerButtonClickPacket read = roundTrip(
                new C2SContainerButtonClickPacket(1, 0),
                C2SContainerButtonClickPacket::new);
        assertEquals(1, read.syncId);
        assertEquals(0, read.buttonId);
    }
}
