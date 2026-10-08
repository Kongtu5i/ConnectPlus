package dev.connectplus.lobby.screen.impl;

import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.lobby.states.StateHandler;
import dev.connectplus.session.*;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BookmarkInteractionTest {
    @TempDir File directory;
    private EmbeddedChannel lobby, client;
    private AccountSessionCoordinator coordinator;
    private PlayerStore store;
    private PlayerSession session;
    private ScreenHandler screens;
    private RecordingSwitchInitiator switches;
    private Path savedProfile;
    private StateHandler state;

    private void openList(String edition) {
        assertNotNull(StructuredDataKey.CUSTOM_NAME);
        dev.connectplus.testutil.ViaProxyTestConfig.init();
        lobby = new EmbeddedChannel();
        client = new EmbeddedChannel();
        store = new PlayerStore(new File(directory, "players"));
        coordinator = new AccountSessionCoordinator(store, () -> null);
        switches = new RecordingSwitchInitiator();
        var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), new TokenStore(directory), store,
                new IdentityLinkStore(store.playersDir()), coordinator, switches, null, new AtomicInteger());
        handler.loadSession(lobby, UUID.randomUUID(), "BookmarkPlayer");
        session = handler.getSession();
        session.c2pChannel = client;
        var identity = edition.equals("java") ? ClientIdentity.verifiedJava(session.uuid, session.name, session.uuid)
                : ClientIdentity.verifiedBedrock(session.uuid, session.name, "123456789", UUID.randomUUID(), "test-session");
        client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(identity);
        session.profileKey = edition.equals("bedrock") ? ProfileKey.bedrockProfile("123456789")
                : ProfileKey.javaProfile(edition.equals("java") ? session.uuid : UUID.randomUUID());
        // This is a GUI fixture: prepare landed data; authentication is tested separately.
        session.playerData = new PlayerData(session.profileKey);
        session.playerData.bookmarks.add(new Bookmark("Saved", "bookmark.example.net:25566", "1.18.2", 1, 0));
        session.serverAddress = "unchanged.example.net:25565";
        session.targetVersion = ProtocolVersion.v1_21_4;
        store.save(session.playerData);
        savedProfile = store.playersDir().toPath().resolve(edition.equals("bedrock") ? "bedrock" : "java")
                .resolve(session.profileKey.value() + ".json");
        state = new StateHandler(handler, lobby);
        screens = new ScreenHandler(state);
        screens.openScreen(new BookmarksScreen(Lang.EN, 0));
    }

    @AfterEach void close() {
        if (coordinator != null) coordinator.shutdown();
        if (lobby != null) lobby.finishAndReleaseAll();
        if (client != null) client.finishAndReleaseAll();
    }

    private void click(int slot, int button) {
        click(windowId(), slot, button);
    }

    private void click(int windowId, int slot, int button) {
        screens.handle(new C2SContainerClickPacket(windowId, 0, slot, button, 0, Map.of(), StructuredItem.empty()));
    }

    private int windowId() {
        return lobby.outboundMessages().stream().filter(S2COpenScreenPacket.class::isInstance)
                .map(S2COpenScreenPacket.class::cast).reduce((first, last) -> last).orElseThrow().id;
    }

    private long opens() {
        return lobby.outboundMessages().stream().filter(S2COpenScreenPacket.class::isInstance).count();
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock", "linkedBedrock"})
    void leftClickOpensDetailsWithoutConnectingOrWritingTheBookmark(String edition) throws Exception {
        openList(edition);
        byte[] before = Files.readAllBytes(savedProfile);
        click(0, 0); // Includes the button=0 packet produced by Geyser for a one-item right-click.
        assertInstanceOf(BookmarkDetailScreen.class, screens.getCurrentScreen());
        assertTrue(switches.requests().isEmpty(), "Opening an editor must never start a backend connection");
        assertEquals("unchanged.example.net:25565", session.serverAddress);
        assertEquals(ProtocolVersion.v1_21_4, session.targetVersion);
        assertArrayEquals(before, Files.readAllBytes(savedProfile));
        assertTrue(lobby.isActive());
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock", "linkedBedrock"})
    void connectionRequiresTheDetailButtonAndUsesTheBookmarksCurrentValues(String edition) throws Exception {
        openList(edition);
        screens.openScreen(new BookmarkDetailScreen(Lang.EN, "Saved", 0));
        // An edit after the screen opens must be reflected in the later connect.
        session.playerData.bookmarks.get(0).address = "edited.example.net:25567";
        session.playerData.bookmarks.get(0).versionName = "1.21.4";
        click(25, 0);
        assertEquals(1, switches.requests().size(), "Only the explicit connection button starts switching");
        var request = switches.requests().get(0);
        assertEquals("edited.example.net:25567", request.target().address());
        assertEquals(ProtocolVersion.v1_21_4, request.target().version());
        assertEquals(session.uuid, request.target().playerId());
        assertTrue(store.load(session.profileKey).bookmarks.get(0).lastConnectedAt > 0);
        assertTrue(lobby.isActive());
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock", "linkedBedrock"})
    void theNativeAutoEntryCanBeChosenForTheMainMenuAndSavedBookmarks(String edition) throws Exception {
        // Official ViaProxy registers this public selector during translator init.
        // Register only the selector in this standalone GUI fixture, not a host runtime.
        final var automatic = net.raphimc.viaproxy.protocoltranslator.ProtocolTranslator.AUTO_DETECT_PROTOCOL;
        if (!ProtocolVersion.getProtocols().contains(automatic)) ProtocolVersion.register(automatic);
        openList(edition);
        screens.openScreen(new MainScreen(Lang.EN));
        click(11, 0);
        chooseAutomaticEntry(automatic);
        assertSame(automatic, session.targetVersion);
        click(16, 0);
        assertSame(automatic, switches.requests().get(0).target().version());

        screens.openScreen(new BookmarkDetailScreen(Lang.EN, "Saved", 0));
        click(22, 0);
        chooseAutomaticEntry(automatic);
        assertEquals(automatic.getName(), store.load(session.profileKey).bookmarks.get(0).versionName,
                "Persisted automatic bookmarks must retain a recognized selector name");
        click(25, 0);
        assertEquals(2, switches.requests().size());
        assertSame(automatic, switches.requests().get(1).target().version(),
                "The switch engine must accept this native selector as a probe request");
        assertTrue(lobby.isActive());
    }

    private void chooseAutomaticEntry(ProtocolVersion automatic) {
        final var versions = new java.util.ArrayList<>(ProtocolVersion.getProtocols());
        versions.removeAll(net.raphimc.viabedrock.api.BedrockProtocolVersion.PROTOCOLS);
        java.util.Collections.reverse(versions);
        int index = versions.indexOf(automatic);
        assertTrue(index >= 0);
        for (int page = 0; page < index / 45; page++) click(53, 0);
        click(index % 45, 0);
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock", "linkedBedrock"})
    void unsupportedProtocolFromDetailsShowsAnUpdateNoticeWithoutDisconnecting(String edition) {
        openList(edition);
        switches.resultOverride = dev.connectplus.switching.SwitchInitiator.StartResult.REJECTED_UNSUPPORTED_PROTOCOL;
        screens.openScreen(new BookmarkDetailScreen(Lang.EN, "Saved", 0));
        final long beforeOpens = opens();
        final int beforeWindow = windowId();
        click(25, 0);
        assertTrue(lobby.outboundMessages().stream().anyMatch(packet ->
                packet instanceof dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket chat
                        && chat.message.asUnformattedString().contains("update ConnectPlus")));
        assertTrue(lobby.isActive());
        assertInstanceOf(BookmarkDetailScreen.class, screens.getCurrentScreen());
        assertEquals(beforeOpens, opens(), "Unsupported versions must not reopen the menu");
        assertEquals(beforeWindow, windowId());
    }

    @Test void removedBookmarkCannotBeConnectedFromAnOldDetailScreen() {
        openList("bedrock");
        screens.openScreen(new BookmarkDetailScreen(Lang.EN, "Saved", 0));
        session.playerData.bookmarks.clear();
        click(25, 0);
        assertTrue(switches.requests().isEmpty());
        assertInstanceOf(BookmarksScreen.class, screens.getCurrentScreen());
        assertEquals("unchanged.example.net:25565", session.serverAddress);
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock", "linkedBedrock"})
    void fullWidthAddressesAreNormalizedInBothInputScreens(String edition) throws Exception {
        openList(edition);
        screens.openScreen(new MainScreen(Lang.EN));
        click(10, 0);
        assertTrue(session.chatListener.apply("　ｍｃ．ｅｘａｍｐｌｅ．ｉｎｖａｌｉｄ：２５５６５　"));
        assertEquals("mc.example.invalid:25565", session.serverAddress);
        screens.openScreen(new BookmarkDetailScreen(Lang.EN, "Saved", 0));
        click(23, 0);
        assertTrue(session.chatListener.apply("ｍｃ．ｅｘａｍｐｌｅ．ｉｎｖａｌｉｄ：２５５６６"));
        assertEquals("mc.example.invalid:25566", store.load(session.profileKey).bookmarks.get(0).address);
    }

    @Test void widthNormalizationDoesNotBypassLocalAddressRestrictions() throws Exception {
        final boolean blocked = dev.connectplus.config.CPConfig.blockLocalTargets;
        try {
            dev.connectplus.config.CPConfig.blockLocalTargets = true;
            openList("java");
            screens.openScreen(new MainScreen(Lang.EN));
            click(10, 0);
            assertFalse(session.chatListener.apply("１２７．０．０．１：２５５６５"));
            assertEquals("unchanged.example.net:25565", session.serverAddress);
            screens.openScreen(new BookmarkDetailScreen(Lang.EN, "Saved", 0));
            click(23, 0);
            assertTrue(session.chatListener.apply("１２７．０．０．１：２５５６５"));
            assertEquals("bookmark.example.net:25566", store.load(session.profileKey).bookmarks.get(0).address);
        } finally { dev.connectplus.config.CPConfig.blockLocalTargets = blocked; }
    }

    @Test void rightClickInTheListCannotStartAConnection() {
        openList("java");
        click(0, 1);
        assertTrue(switches.requests().isEmpty());
        assertEquals(0, session.playerData.bookmarks.get(0).lastConnectedAt);
    }

    @Test void listClicksQueuedBeforeDetailsCannotTriggerAConnection() {
        openList("bedrock");
        // The original bookmark (createdAt=1) sorts behind these 31 entries.
        for (int i = 0; i < 31; i++) {
            session.playerData.bookmarks.add(new Bookmark("Other " + i, "other.example.net:25565", null, 2, 0));
        }
        screens.openScreen(new BookmarksScreen(Lang.EN, 0));
        int listWindow = windowId();
        click(listWindow, 31, 0);
        click(listWindow, 31, 0); // A second list click already queued before the details reach the client.
        assertInstanceOf(BookmarkDetailScreen.class, screens.getCurrentScreen());
        assertTrue(switches.requests().isEmpty(), "Repeated clicks on a list bookmark must not connect");
        assertEquals("unchanged.example.net:25565", session.serverAddress);
    }

    @Test void detailLayoutPlacesInformationConnectionAndDeleteAtTheRequestedLocations() {
        openList("bedrock");
        click(0, 0);
        var content = lobby.outboundMessages().stream().filter(S2CContainerSetContentPacket.class::isInstance)
                .map(S2CContainerSetContentPacket.class::cast).reduce((first, last) -> last).orElseThrow();
        assertEquals(54, content.items.length);
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("written_book"), content.items[19].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("name_tag"), content.items[21].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("anvil"), content.items[22].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("writable_book"), content.items[23].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("oak_door"), content.items[25].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("ender_pearl"), content.items[49].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("barrier"), content.items[53].identifier());
        assertTrue(content.items[13].isEmpty());
        assertTrue(content.items[50].isEmpty());
        click(53, 0);
        assertInstanceOf(BookmarkDeleteConfirmScreen.class, screens.getCurrentScreen());
        assertEquals(1, session.playerData.bookmarks.size());
        assertTrue(switches.requests().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"bedrock", "linkedBedrock"})
    void bedrockNavigationKeepsTheNativeContainerReuseConditionsStable(String edition) {
        openList(edition);
        screens.openScreen(new MainScreen(Lang.EN));
        int mainWindow = windowId();
        var main = latestOpen();
        assertEquals(5, main.type, "Bedrock must start with the same 6-row container used by bookmark pages");
        assertEquals("§aConnectPlus", main.title.asUnformattedString());
        var mainContent = latestContent();
        assertEquals(54, mainContent.items.length);
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("name_tag"), mainContent.items[10].identifier(),
                "The top row keeps its positions");
        assertTrue(mainContent.items[47].isEmpty(), "No screen shows a page-name paper anymore");
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("book"), mainContent.items[45].identifier(),
                "The bottom actions moved from row four to row six");
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("tnt"), mainContent.items[46].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("ender_chest"), mainContent.items[49].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("barrier"), mainContent.items[53].identifier());
        click(49, 0);
        var bookmarks = latestOpen();
        assertNotEquals(mainWindow, bookmarks.id, "Logical Java windows must still isolate delayed clicks");
        assertEquals(main.type, bookmarks.type);
        assertEquals(main.title.asUnformattedString(), bookmarks.title.asUnformattedString());
        click(0, 0);
        var details = latestOpen();
        assertEquals(main.type, details.type);
        assertEquals(main.title.asUnformattedString(), details.title.asUnformattedString());
        var content = latestContent();
        assertEquals(54, content.items.length);
        assertTrue(content.items[47].isEmpty(), "No screen shows the page-name paper anymore");
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("oak_door"), content.items[25].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("name_tag"), content.items[21].identifier());
        screens.refreshScreen();
        assertTrue(latestContent().items[47].isEmpty());
        assertEquals(details.id, latestContent().windowId);
        click(49, 0);
        assertInstanceOf(BookmarksScreen.class, screens.getCurrentScreen(), "The explicit back button still returns to the parent");
    }

    @ParameterizedTest @ValueSource(strings = {"bedrock", "linkedBedrock"})
    void aBedrockCloseLeavesTheGuiWithoutOpeningParentsOrAcceptingDelayedClicks(String edition) {
        openList(edition);
        click(0, 0);
        int detailWindow = windowId();
        long before = opens();
        screens.handle(new C2SContainerClosePacket(detailWindow));
        screens.handle(new C2SContainerClosePacket(detailWindow));
        assertNull(screens.getCurrentScreen(), "A failed/closed Bedrock container must not reopen another virtual container");
        assertEquals(before, opens(), "Do not generate the details -> list -> main close/reopen cascade");
        click(detailWindow, 25, 0);
        assertTrue(switches.requests().isEmpty(), "A delayed closed detail click must not connect");
        screens.openScreen(new MainScreen(Lang.EN));
        assertNotEquals(detailWindow, windowId());
        screens.handle(new C2SContainerClosePacket(detailWindow));
        assertInstanceOf(MainScreen.class, screens.getCurrentScreen());
    }

    @Test void javaNavigationKeepsItsOriginalTitlesRowsAndCloseToParentBehavior() {
        openList("java");
        screens.openScreen(new MainScreen(Lang.EN));
        assertEquals(3, latestOpen().type);
        assertEquals("§aConnectPlus", latestOpen().title.asUnformattedString());
        var mainContent = latestContent();
        assertEquals(36, mainContent.items.length, "Java keeps the original 4-row container");
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("book"), mainContent.items[27].identifier(),
                "Java keeps the bottom actions on row four");
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("tnt"), mainContent.items[28].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("ender_chest"), mainContent.items[31].identifier());
        assertEquals(dev.connectplus.lobby.LobbyConstants.ITEMS.indexOf("barrier"), mainContent.items[35].identifier());
        click(31, 0);
        assertEquals(5, latestOpen().type);
        assertEquals("§aBookmarks", latestOpen().title.asUnformattedString());
        click(0, 0);
        screens.handle(new C2SContainerClosePacket(windowId()));
        assertInstanceOf(BookmarksScreen.class, screens.getCurrentScreen());
    }

    private S2COpenScreenPacket latestOpen() {
        return lobby.outboundMessages().stream().filter(S2COpenScreenPacket.class::isInstance)
                .map(S2COpenScreenPacket.class::cast).reduce((first, last) -> last).orElseThrow();
    }

    private S2CContainerSetContentPacket latestContent() {
        return lobby.outboundMessages().stream().filter(S2CContainerSetContentPacket.class::isInstance)
                .map(S2CContainerSetContentPacket.class::cast).reduce((first, last) -> last).orElseThrow();
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock", "linkedBedrock"})
    void pageNavigationReplacesTheWindowWithoutAnIntermediateServerClose(String edition) {
        openList(edition);
        screens.openScreen(new MainScreen(Lang.EN));
        lobby.outboundMessages().clear();
        screens.openScreen(new BookmarksScreen(Lang.EN, 0));
        click(0, 0);
        click(49, 0);
        assertInstanceOf(BookmarksScreen.class, screens.getCurrentScreen());
        assertEquals(3, opens());
        assertFalse(lobby.outboundMessages().stream().anyMatch(S2CContainerClosePacket.class::isInstance),
                "Page replacement must let the translator retain the old holder until it handles OpenScreen");
        final int current = windowId();
        screens.closeScreen();
        final var closes = lobby.outboundMessages().stream().filter(S2CContainerClosePacket.class::isInstance)
                .map(S2CContainerClosePacket.class::cast).toList();
        assertEquals(1, closes.size(), "Actually leaving a GUI must still send a close");
        assertEquals(current, closes.get(0).id);
        assertNull(screens.getCurrentScreen());
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock", "linkedBedrock"})
    void delayedMainWindowCloseDoesNotCloseTheFirstBookmarksPage(String edition) {
        openList(edition);
        screens.openScreen(new MainScreen(Lang.EN));
        int mainWindow = windowId();
        // The bookmarks entry sits on row four for Java and on row six inside
        // the stable Bedrock container.
        click(edition.equals("java") ? 31 : 49, 0);
        long before = opens();
        screens.handle(new C2SContainerClosePacket(mainWindow));
        screens.handle(new C2SContainerClosePacket(mainWindow));
        assertInstanceOf(BookmarksScreen.class, screens.getCurrentScreen());
        assertEquals(before, opens(), "Old window close must not reopen the parent or close the new page");
        // The currently displayed window must still accept its own interactions.
        click(0, 0);
        assertInstanceOf(BookmarkDetailScreen.class, screens.getCurrentScreen());
        assertTrue(switches.requests().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock", "linkedBedrock"})
    void queuedListClicksCannotActivateTheRelocatedConnectButton(String edition) {
        openList(edition);
        for (int i = 0; i < 25; i++) {
            session.playerData.bookmarks.add(new Bookmark("Other " + i, "other.example.net:25565", null, 2, 0));
        }
        screens.openScreen(new BookmarksScreen(Lang.EN, 0));
        int listWindow = windowId();
        click(listWindow, 25, 0);
        click(listWindow, 25, 0);
        assertInstanceOf(BookmarkDetailScreen.class, screens.getCurrentScreen());
        assertTrue(switches.requests().isEmpty(), "A delayed list click cannot act on the new connection button");
        assertEquals("unchanged.example.net:25565", session.serverAddress);
        click(25, 0);
        assertEquals(1, switches.requests().size(), "A fresh click from the detail window can connect");
    }

    @Test void missingBookmarkDuringInitOnlyOpensItsFallbackPage() {
        openList("bedrock");
        long before = opens();
        screens.openScreen(new BookmarkDetailScreen(Lang.EN, "Deleted", 0));
        assertInstanceOf(BookmarksScreen.class, screens.getCurrentScreen());
        assertEquals(before + 1, opens(), "A redirected init must not send a second, invalid open packet");
        click(0, 0);
        assertInstanceOf(BookmarkDetailScreen.class, screens.getCurrentScreen());
    }

    @Test void returningToTheLobbyDoesNotReuseThePreviousHandlersWindow() {
        openList("bedrock");
        int previousWindow = windowId();
        screens = new ScreenHandler(state);
        screens.openScreen(new BookmarksScreen(Lang.EN, 0));
        screens.handle(new C2SContainerClosePacket(previousWindow));
        assertInstanceOf(BookmarksScreen.class, screens.getCurrentScreen());
        assertNotEquals(previousWindow, windowId());
    }

    @Test void refreshingADeletedBookmarkCannotOverwriteItsFallbackWindow() {
        openList("bedrock");
        click(0, 0);
        session.playerData.bookmarks.clear();
        screens.refreshScreen();
        assertInstanceOf(BookmarksScreen.class, screens.getCurrentScreen());
        var content = lobby.outboundMessages().stream().filter(S2CContainerSetContentPacket.class::isInstance)
                .map(S2CContainerSetContentPacket.class::cast).reduce((first, last) -> last).orElseThrow();
        assertEquals(windowId(), content.windowId);
        assertFalse(content.items[22].isEmpty(), "The fallback must show the empty-bookmarks notice");
        assertTrue(content.items[25].isEmpty(), "The abandoned details must not overwrite the fallback");
    }

    @Test void repeatedNavigationKeepsCompatibleIdsAndRejectsEachPreviousWindowsClose() {
        openList("bedrock");
        for (int i = 0; i < 105; i++) {
            int previous = windowId();
            screens.openScreen(new BookmarksScreen(Lang.EN, 0));
            int current = windowId();
            assertTrue(current >= 1 && current <= 100, "IDs must fit vanilla's chest window range");
            assertNotEquals(previous, current);
            screens.handle(new C2SContainerClosePacket(previous));
            assertInstanceOf(BookmarksScreen.class, screens.getCurrentScreen());
            assertEquals(current, client.attr(CPAttributeKeys.LOBBY_WINDOW_ID).get());
        }
        screens.closeScreen();
        assertEquals(0, client.attr(CPAttributeKeys.LOBBY_WINDOW_ID).get());
    }
}
