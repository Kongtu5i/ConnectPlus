package dev.connectplus.lobby;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.protocol.LobbyPacketRegistry;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket;
import dev.connectplus.lobby.states.PlayStateHandler;
import dev.connectplus.session.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayDisconnectPacket;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LobbyHotbarTest {
    @TempDir File directory;
    private EmbeddedChannel channel;
    private PlayStateHandler play;
    private PlayerSession session;
    private int lastWindowId = 1;
    private final LobbyPacketRegistry registry = new LobbyPacketRegistry();

    @BeforeEach void join() {
        assertNotNull(com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.CUSTOM_NAME);
        dev.connectplus.testutil.ViaProxyTestConfig.init();
        channel = new EmbeddedChannel();
        var store = new PlayerStore(new File(directory, "players"));
        var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), new TokenStore(directory), store,
                new IdentityLinkStore(new File(directory, "players")), new AccountSessionCoordinator(store, () -> null),
                null, null, new AtomicInteger());
        handler.loadSession(channel, UUID.randomUUID(), "HotbarPlayer");
        session = handler.getSession();
        session.playerData = new PlayerData(session.uuid);
        registry.setConnectionState(ConnectionState.PLAY);
        play = new PlayStateHandler(handler, channel);
    }

    @AfterEach void close() { channel.finishAndReleaseAll(); }

    private void receive(MCPackets type, String hex) {
        var bytes = Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex));
        try {
            var packet = registry.createPacket(type.getId(769), bytes);
            assertEquals(0, bytes.readableBytes(), "Known client packet must be fully decoded");
            play.handle(packet);
        } finally { bytes.release(); }
    }

    private S2CContainerSetContentPacket inventory() {
        return channel.outboundMessages().stream().filter(p -> p instanceof S2CContainerSetContentPacket content && content.windowId == 0)
                .map(S2CContainerSetContentPacket.class::cast).reduce((first, last) -> last).orElseThrow();
    }

    private long screens() { return channel.outboundMessages().stream().filter(S2COpenScreenPacket.class::isInstance).count(); }
    private int windowId() {
        lastWindowId = channel.outboundMessages().stream().filter(S2COpenScreenPacket.class::isInstance)
                .map(S2COpenScreenPacket.class::cast).reduce((first, last) -> last).map(p -> p.id).orElse(lastWindowId);
        return lastWindowId;
    }
    private void closeMenu() { play.handle(new C2SContainerClosePacket(windowId())); }
    private void clearPackets() {
        windowId();
        channel.outboundMessages().clear();
    }

    private void assertShortcuts() {
        var items = inventory().items;
        assertEquals(46, items.length);
        assertEquals(LobbyConstants.ITEMS.indexOf("compass"), items[40].identifier());
        assertEquals(LobbyConstants.ITEMS.indexOf("barrier"), items[44].identifier());
        for (int i = 0; i < items.length; i++) assertEquals(i == 40 || i == 44 ? 1 : 0, items[i].amount(), "slot " + i);
    }

    private void assertEmptyInventory() {
        assertEquals(46, inventory().items.length);
        for (var item : inventory().items) assertEquals(0, item.amount());
        assertEquals(0, inventory().cursor.amount());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void joinHidesShortcutsUntilMainMenuClosesForEitherProfile(boolean bedrock) {
        session.profileKey = bedrock ? ProfileKey.bedrockProfile("123456789") : ProfileKey.javaProfile(session.uuid);
        assertEmptyInventory();
        assertTrue(channel.outboundMessages().stream().anyMatch(p -> p instanceof net.raphimc.netminecraft.packet.UnknownPacket unknown
                && unknown.packetId == MCPackets.S2C_SET_HELD_SLOT.getId(769) && Arrays.equals(new byte[]{4}, unknown.data)),
                "Every join/return must synchronize the selected slot with the client");
        closeMenu();
        assertShortcuts();
    }

    @Test void lobbyAllowsNativeHandheldControlsWhileKeepingFlightAndInvulnerability() {
        var join = channel.outboundMessages().stream().filter(dev.connectplus.lobby.protocol.packets.play.S2CLoginPacket.class::isInstance)
                .map(dev.connectplus.lobby.protocol.packets.play.S2CLoginPacket.class::cast).findFirst().orElseThrow();
        assertEquals(2, join.spawnInfo.gamemode, "Spectator mode replaces native item selection/use controls");
        var abilities = channel.outboundMessages().stream().filter(dev.connectplus.lobby.protocol.packets.play.S2CPlayerAbilitiesPacket.class::isInstance)
                .map(dev.connectplus.lobby.protocol.packets.play.S2CPlayerAbilitiesPacket.class::cast).findFirst().orElseThrow();
        assertTrue(abilities.invulnerable);
        assertTrue(abilities.isFlying);
        assertTrue(abilities.canFly);
        assertFalse(abilities.instabuild);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void closingMainMenuAndSendingPlainChatDoesNotAdvertiseOrOpenAnyGui(boolean bedrock) {
        session.profileKey = bedrock ? ProfileKey.bedrockProfile("123456789") : ProfileKey.javaProfile(session.uuid);
        clearPackets();
        closeMenu();
        assertFalse(channel.outboundMessages().stream().anyMatch(dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket.class::isInstance),
                "Closing the main menu must not send the obsolete chat reopen hint");
        assertShortcuts();
        play.handle(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket("hi", java.time.Instant.now(), 0L, null, 0, new BitSet(3)));
        play.handle(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandPacket("unknowncommand"));
        play.handle(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandSignedPacket("unknowncommand"));
        assertEquals(0, screens(), "Neither plain chat nor unknown commands may open a GUI");
        receive(MCPackets.C2S_SET_CARRIED_ITEM, "0004");
        receive(MCPackets.C2S_USE_ITEM, "00070000000000000000");
        assertEquals(1, screens(), "The compass must still open the main menu");
    }

    @Test void legacyTutorialBookDoesNotInstallAnArbitraryChatReopenListener() {
        session.clientVersion = com.viaversion.viaversion.api.protocol.version.ProtocolVersion.v1_18_2;
        clearPackets();
        play.handle(new C2SContainerClickPacket(windowId(), 0, 27, 0, 0, new HashMap<>(),
                com.viaversion.viaversion.api.minecraft.item.StructuredItem.empty()));
        assertNull(session.chatListener, "The tutorial book must not reopen the GUI on arbitrary chat");
        assertFalse(channel.outboundMessages().stream().anyMatch(dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket.class::isInstance));
        assertShortcuts();
        play.handle(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatPacket("hi", java.time.Instant.now(), 0L, null, 0, new BitSet(3)));
        assertEquals(0, screens());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void movementBelowWorldBoundsIsCorrectedBeforeTheMenuCanReopen(boolean rotation) {
        var spawn = channel.outboundMessages().stream()
                .filter(dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket.class::isInstance)
                .map(dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket.class::cast).findFirst().orElseThrow();
        closeMenu();
        clearPackets();
        receivePosition(24, -12.5, 24, rotation);
        var correction = channel.outboundMessages().stream()
                .filter(dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket.class::isInstance)
                .map(dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket.class::cast).findFirst().orElseThrow(
                        () -> new AssertionError("A returning client can fall in the void; normal lobby movement must restore a usable menu position"));
        assertEquals(spawn.posX, correction.posX);
        assertEquals(spawn.posY, correction.posY);
        assertEquals(spawn.posZ, correction.posZ);
        assertEquals(0, correction.velocityX);
        assertEquals(0, correction.velocityY);
        assertEquals(0, correction.velocityZ);
        assertEquals(0, correction.flagMask);
        assertTrue(correction.teleportId > spawn.teleportId);
        receivePosition(24, -13, 24, rotation);
        assertEquals(1, channel.outboundMessages().stream().filter(dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket.class::isInstance).count(),
                "Old movement arriving during correction must not flood teleports");
        assertEquals(0, screens(), "Position correction must leave a deliberately closed menu closed");
        receive(MCPackets.C2S_USE_ITEM, "00070000000000000000");
        assertEquals(1, screens());
        assertEmptyInventory();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void normalPositionEchoDoesNotChangeInventoryOrReopenMenus(boolean rotation) {
        var spawn = channel.outboundMessages().stream()
                .filter(dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket.class::isInstance)
                .map(dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket.class::cast).findFirst().orElseThrow();
        clearPackets();
        receivePosition(spawn.posX, spawn.posY, spawn.posZ, rotation);
        assertTrue(channel.outboundMessages().isEmpty());
    }

    private void receivePosition(double x, double y, double z, boolean rotation) {
        var wire = Unpooled.buffer();
        try {
            wire.writeDouble(x).writeDouble(y).writeDouble(z);
            if (rotation) wire.writeFloat(45).writeFloat(10);
            wire.writeByte(2); // 1.21.4 movement flags: horizontal collision, airborne
            var packet = registry.createPacket((rotation ? MCPackets.C2S_MOVE_PLAYER_POS_ROT : MCPackets.C2S_MOVE_PLAYER_POS).getId(769), wire);
            assertEquals(0, wire.readableBytes());
            play.handle(packet);
        } finally { wire.release(); }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"10000,64,24.5", "24.5,32767,24.5", "NaN,64,24.5", "24.5,Infinity,24.5", "24.5,64,-Infinity"})
    void staleBackendAndNonFinitePositionsCannotLeaveTheLobbyPlatform(double x, double y, double z) {
        clearPackets();
        receivePosition(x, y, z, true);
        var position = channel.outboundMessages().stream()
                .filter(dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket.class::isInstance)
                .map(dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket.class::cast).findFirst().orElseThrow();
        assertTrue(LobbyConstants.isSafePosition(position.posX, position.posY, position.posZ));
        assertEquals(0, screens());
    }

    @Test void mainHandSwingSupportsTouchClientsWithoutAnAirUseButton() {
        closeMenu();
        receive(MCPackets.C2S_SET_CARRIED_ITEM, "0004");
        receive(MCPackets.C2S_SWING, "01");
        assertEquals(1, screens(), "Offhand swing must do nothing");
        receive(MCPackets.C2S_SWING, "00");
        assertEquals(2, screens());
        receive(MCPackets.C2S_USE_ITEM, "00010000000000000000");
        assertEquals(2, screens(), "Follow-up interaction must not reopen the same menu");
    }

    @Test void staleMenuUseCannotReopenTheGuiWhileItsShortcutIsHidden() {
        receive(MCPackets.C2S_USE_ITEM, "00070000000000000000");
        receive(MCPackets.C2S_SWING, "00");
        assertEquals(1, screens());
        assertEmptyInventory();
    }

    @Test void staleDisconnectUseCannotDisconnectWhileItsShortcutIsHidden() {
        receive(MCPackets.C2S_SET_CARRIED_ITEM, "0008");
        receive(MCPackets.C2S_USE_ITEM, "00070000000000000000");
        receive(MCPackets.C2S_SWING, "00");
        assertTrue(channel.isActive());
        assertFalse(channel.outboundMessages().stream().anyMatch(S2CPlayDisconnectPacket.class::isInstance));
        assertEmptyInventory();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mainHandUseOpensMenuAndCancelsPendingChatForEitherProfile(boolean bedrock) {
        session.profileKey = bedrock ? ProfileKey.bedrockProfile("123456789") : ProfileKey.javaProfile(session.uuid);
        closeMenu();
        session.chatListener = text -> { fail("Canceled chat input must not run"); return true; };
        receive(MCPackets.C2S_SET_CARRIED_ITEM, "0004");
        receive(MCPackets.C2S_USE_ITEM, "00070000000000000000"); // hand 0, sequence 7, yaw/pitch 0
        assertEquals(2, screens());
        assertNull(session.chatListener);
        assertTrue(channel.isActive());
        assertEmptyInventory();
        var packets = new ArrayList<>(channel.outboundMessages());
        var latestOpen = packets.stream().filter(S2COpenScreenPacket.class::isInstance)
                .reduce((first, last) -> last).orElseThrow();
        assertTrue(packets.indexOf(inventory()) < packets.indexOf(latestOpen),
                "Clear the player shortcuts before opening the chest");
        closeMenu();
        assertShortcuts();
    }

    @Test void usingOnABlockAlsoOpensMenuAndAcknowledgesPrediction() {
        closeMenu();
        receive(MCPackets.C2S_SET_CARRIED_ITEM, "0004");
        receive(MCPackets.C2S_USE_ITEM_ON, "00000000000000000001000000000000000000000000000009");
        assertEquals(2, screens());
        assertTrue(channel.outboundMessages().stream().anyMatch(p -> p instanceof net.raphimc.netminecraft.packet.UnknownPacket unknown
                && unknown.packetId == MCPackets.S2C_BLOCK_CHANGED_ACK.getId(769) && Arrays.equals(new byte[]{9}, unknown.data)));
    }

    @Test void selectingDisconnectDoesNotDisconnectUntilMainHandUse() {
        closeMenu();
        receive(MCPackets.C2S_SET_CARRIED_ITEM, "0008");
        assertTrue(channel.isActive());
        receive(MCPackets.C2S_USE_ITEM, "01010000000000000000"); // offhand
        assertTrue(channel.isActive());
        receive(MCPackets.C2S_USE_ITEM, "00020000000000000000");
        assertFalse(channel.isActive());
        assertTrue(channel.outboundMessages().stream().anyMatch(S2CPlayDisconnectPacket.class::isInstance));
    }

    @Test void emptyAndInvalidSelectedSlotsCannotTriggerAnEntry() {
        closeMenu();
        receive(MCPackets.C2S_SET_CARRIED_ITEM, "0000");
        receive(MCPackets.C2S_USE_ITEM, "00010000000000000000");
        receive(MCPackets.C2S_SET_CARRIED_ITEM, "0009");
        receive(MCPackets.C2S_USE_ITEM, "00020000000000000000");
        assertEquals(1, screens());
        assertTrue(channel.isActive());
    }

    @Test void playerInventoryClickDoesNotRunAChestButtonAndRepairsPredictedInventory() {
        var click = new C2SContainerClickPacket();
        click.containerId = 0;
        click.slot = 35; // main menu disconnect button, but not a chest click
        play.handle(click);
        assertTrue(channel.isActive());
        assertEmptyInventory();
    }

    @Test void droppingOrCreativeDeletingRestoresShortcuts() {
        closeMenu();
        clearPackets();
        receive(MCPackets.C2S_PLAYER_ACTION, "0400000000000000000000"); // drop item, position 0, face 0, sequence 0
        assertEquals(1, inventory().items[40].amount());
        clearPackets();
        receive(MCPackets.C2S_SET_CREATIVE_MODE_SLOT, "002800"); // slot 40, empty stack
        assertEquals(1, inventory().items[40].amount());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void navigatingMenusAndClosingToAParentNeverIssuesShortcuts(boolean bedrock) {
        session.profileKey = bedrock ? ProfileKey.bedrockProfile("123456789") : ProfileKey.javaProfile(session.uuid);
        clearPackets();
        play.handle(new C2SContainerClickPacket(1, 0, 31, 0, 0, new HashMap<>(),
                com.viaversion.viaversion.api.minecraft.item.StructuredItem.empty()));
        assertEmptyInventory();
        closeMenu(); // Bookmarks closes back to the main menu.
        assertEquals(2, screens());
        assertEmptyInventory();
        for (var packet : channel.outboundMessages()) {
            if (packet instanceof S2CContainerSetContentPacket content && content.windowId == 0) {
                for (var item : content.items) assertEquals(0, item.amount(), "Navigation must not briefly issue shortcuts");
            }
        }
        closeMenu(); // Closing the main menu actually leaves the GUI.
        assertShortcuts();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void closingGuiForChatIssuesShortcutsAndChatCompletionHidesThem(boolean bedrock) {
        session.profileKey = bedrock ? ProfileKey.bedrockProfile("123456789") : ProfileKey.javaProfile(session.uuid);
        play.handle(new C2SContainerClickPacket(1, 0, 10, 0, 0, new HashMap<>(),
                com.viaversion.viaversion.api.minecraft.item.StructuredItem.empty()));
        assertNotNull(session.chatListener);
        assertShortcuts();
        play.handle(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SChatCommandPacket("cphelp"));
        assertNull(session.chatListener);
        assertEquals(2, screens());
        assertEmptyInventory();
    }

    @Test void inventoryRepairsAndLocaleChangesKeepShortcutsHiddenWithAnOpenGui() {
        clearPackets();
        receive(MCPackets.C2S_PLAYER_ACTION, "0400000000000000000000");
        assertEmptyInventory();
        receive(MCPackets.C2S_SET_CREATIVE_MODE_SLOT, "002800");
        assertEmptyInventory();
        play.handle(new dev.connectplus.lobby.protocol.packets.config.C2SPlayClientInformationPacket(
                "zh_cn", 12, 0, true, 127, 1, false, true));
        assertEmptyInventory();
        closeMenu();
        assertShortcuts();
    }
}
