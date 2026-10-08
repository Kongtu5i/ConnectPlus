package dev.connectplus.lobby;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: the full click-through connect flow — join, main screen,
 * version selector, address chat input, transfer packet, session cleanup.
 */
class LobbyGuiIT {

    @Test
    @Timeout(15)
    void firstBookmarksVisitSurvivesTwoDelayedMainWindowCloses() throws Exception {
        startServer();
        final UUID uuid = UUID.randomUUID();
        final PlayerData data = new PlayerData(uuid);
        data.bookmarks.add(new dev.connectplus.session.Bookmark("Saved", "saved.example.net:25565", "1.21.4", 1, 0));
        this.playerStore.save(data);
        final LobbyTestClient client = this.join("FirstBookmarks", uuid);
        final var main = client.await(S2COpenScreenPacket.class, 1);
        client.clickContainer(31, 0);
        final var bookmarks = client.await(S2COpenScreenPacket.class, 2);
        assertNotEquals(main.id, bookmarks.id);
        client.write(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket(main.id));
        client.write(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket(main.id));
        client.clickContainer(0, 0);
        final var detail = client.await(S2COpenScreenPacket.class, 3);
        assertEquals("§aBookmark details", detail.title.asUnformattedString());
        assertNotEquals(bookmarks.id, detail.id);
        final var content = client.awaitChestContent(5);
        assertEquals(detail.id, content.windowId);
        assertEquals(LobbyConstants.ITEMS.indexOf("written_book"), content.items[19].identifier());
        assertEquals(LobbyConstants.ITEMS.indexOf("oak_door"), content.items[25].identifier());
        assertEquals(LobbyConstants.ITEMS.indexOf("barrier"), content.items[53].identifier());
        assertTrue(this.switchInitiator.requests().isEmpty());
        client.clickContainer(25, 0);
        assertEquals("saved.example.net:25565", this.switchInitiator.awaitRequests(1).get(0).target().address());
        assertTrue(client.isActive());
    }

    @Test
    @Timeout(15)
    void hotbarMenuAndDisconnectWorkThroughTheActualLobbyPacketCodec() throws Exception {
        startServer();
        final UUID uuid = UUID.randomUUID();
        final LobbyTestClient client = this.join("ShortcutPlayer", uuid);
        assertEquals(2, client.await(dev.connectplus.lobby.protocol.packets.play.S2CLoginPacket.class).spawnInfo.gamemode);
        final var hidden = client.awaitContainerContent(0, 2);
        for (final var item : hidden.items) assertEquals(0, item.amount(), "The initial chest must hide player shortcuts");
        client.closeContainer();
        final var inventory = client.awaitContainerContent(0, 3);
        assertEquals(LobbyConstants.ITEMS.indexOf("compass"), inventory.items[40].identifier());
        assertEquals(LobbyConstants.ITEMS.indexOf("barrier"), inventory.items[44].identifier());
        client.write(new net.raphimc.netminecraft.packet.UnknownPacket(
                net.raphimc.netminecraft.constants.MCPackets.C2S_SET_CARRIED_ITEM.getId(769), new byte[]{0, 4}));
        client.write(new net.raphimc.netminecraft.packet.UnknownPacket(
                net.raphimc.netminecraft.constants.MCPackets.C2S_USE_ITEM.getId(769), new byte[10]));
        assertEquals(3, client.await(S2COpenScreenPacket.class, 2).type);
        final var reopened = client.awaitContainerContent(0, 4);
        for (final var item : reopened.items) assertEquals(0, item.amount(), "Reopening must hide player shortcuts again");
        client.closeContainer();
        final var restored = client.awaitContainerContent(0, 5);
        assertEquals(1, restored.items[40].amount());
        assertEquals(1, restored.items[44].amount());
        client.write(new net.raphimc.netminecraft.packet.UnknownPacket(
                net.raphimc.netminecraft.constants.MCPackets.C2S_SET_CARRIED_ITEM.getId(769), new byte[]{0, 8}));
        client.write(new net.raphimc.netminecraft.packet.UnknownPacket(
                net.raphimc.netminecraft.constants.MCPackets.C2S_USE_ITEM.getId(769), new byte[10]));
        client.await(net.raphimc.netminecraft.packet.impl.play.S2CPlayDisconnectPacket.class);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.sessionRegistry.get(uuid) != null && System.nanoTime() < deadline) Thread.sleep(25);
        assertNull(this.sessionRegistry.get(uuid), "The disconnect shortcut must clean up the lobby session");
    }

    @Test
    @Timeout(15)
    void legacyTutorialBookRestoresBothShortcuts() throws Exception {
        startServer();
        final UUID uuid = UUID.randomUUID();
        final LobbyTestClient client = this.join("BookMenuPlayer", uuid);
        client.awaitContainerContent(0, 1);
        this.sessionRegistry.get(uuid).clientVersion = com.viaversion.viaversion.api.protocol.version.ProtocolVersion.v1_18;
        client.clickContainer(27, 0);
        client.await(dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenBookPacket.class);
        // Initial clear and selected-slot sync, click repair, GUI close,
        // temporary book, then restoration.
        final var restored = client.awaitContainerContent(0, 6);
        assertEquals(LobbyConstants.ITEMS.indexOf("compass"), restored.items[40].identifier());
        assertEquals(LobbyConstants.ITEMS.indexOf("barrier"), restored.items[44].identifier());
    }

    private LobbyServer server;
    private SessionRegistry sessionRegistry;
    private PlayerStore playerStore;
    private RecordingSwitchInitiator switchInitiator;
    private boolean originalProxyOnlineMode;
    private boolean originalAccountLogin;

    @TempDir
    File dataDir;

    private LobbyServer startServer() {
        this.sessionRegistry = new SessionRegistry();
        this.switchInitiator = new RecordingSwitchInitiator();
        this.playerStore = new PlayerStore(new File(this.dataDir, "players"));
        final LobbyServer server = new LobbyServer(this.sessionRegistry, new TokenStore(this.dataDir), this.playerStore, this.switchInitiator);
        server.start();
        this.server = server;
        return server;
    }

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop();
            this.server = null;
        }
        net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(this.originalProxyOnlineMode);
        dev.connectplus.config.CPConfig.allowAccountLogin = this.originalAccountLogin;
    }

    @BeforeEach
    void clearStaticState() {
        dev.connectplus.testutil.ViaProxyTestConfig.init();
        this.originalProxyOnlineMode = net.raphimc.viaproxy.ViaProxy.getConfig().isProxyOnlineMode();
        this.originalAccountLogin = dev.connectplus.config.CPConfig.allowAccountLogin;
        net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(true);
        dev.connectplus.config.CPConfig.allowAccountLogin = true;
        //LobbyTransfers is a global static map: a leaked pending transfer from a
        //prior test would open an unrelated confirmation screen for this test
        dev.connectplus.lobby.LobbyTransfers.consumeAll();
    }

    @Test
    @Timeout(15)
    void offlineProxyCannotLogoutOrDeleteAnExistingAccount() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("AccountPlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = this.join("AccountPlayer", uuid);
        client.await(S2COpenScreenPacket.class, 1);
        final var session = this.sessionRegistry.get(uuid);
        session.account = new dev.connectplus.testutil.StubAccount();
        session.playerData.accountBlob = "existing-encrypted-account";
        this.playerStore.save(session.playerData);
        net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(false);
        client.clickContainer(13, 0);
        final S2CSystemChatPacket notice = client.await(S2CSystemChatPacket.class, 3);
        assertTrue(notice.message.asUnformattedString().contains("disabled"));
        assertEquals("existing-encrypted-account", this.playerStore.load(ProfileKey.javaProfile(uuid)).accountBlob,
                "An offline identity must not erase somebody else's saved credentials");
        assertFalse(session.loginInProgress);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,true", "true,false", "true,true"})
    @Timeout(15)
    void accountHandoffAndDisplayRequireBothSwitches(final boolean proxyOnline, final boolean pluginLogin) throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("AccountPlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = this.joinWithSavedBookmark("AccountPlayer", uuid);
        final var account = new dev.connectplus.testutil.StubAccount();
        this.sessionRegistry.get(uuid).account = account;
        net.raphimc.viaproxy.ViaProxy.getConfig().setProxyOnlineMode(proxyOnline);
        dev.connectplus.config.CPConfig.allowAccountLogin = pluginLogin;
        client.clickContainer(49, 0);
        client.await(S2COpenScreenPacket.class, 5);
        if (!proxyOnline || !pluginLogin) {
            assertEquals(0, account.displayReads.get(), "Disabled accounts must not reveal metadata in the GUI");
        }
        client.clickContainer(16, 0);
        final var request = this.switchInitiator.awaitRequests(1).get(0);
        if (proxyOnline && pluginLogin) org.junit.jupiter.api.Assertions.assertSame(account, request.account());
        else assertNull(request.account(), "Disabled cached credentials must not reach the backend");
    }

    private InetSocketAddress serverAddress() {
        return (InetSocketAddress) this.server.localAddress();
    }

    @Test
    @Timeout(15)
    void offlineTogglePreservesLoginAndControlsTheMovedConnectButton() throws Exception {
        startServer();
        final UUID uuid = UUID.randomUUID();
        final LobbyTestClient client = this.joinWithSavedBookmark("OfflinePlayer", uuid);
        final var session = this.sessionRegistry.get(uuid);
        final var account = new dev.connectplus.testutil.StubAccount();
        session.account = account;
        session.playerData.accountBlob = "saved-encrypted-account";
        this.playerStore.save(session.playerData);
        client.clickContainer(49, 0); // back to main
        client.await(S2COpenScreenPacket.class, 5);
        final var initial = client.receivedSnapshot().stream()
                .filter(p -> p instanceof S2CContainerSetContentPacket)
                .map(p -> (S2CContainerSetContentPacket) p)
                .filter(p -> p.items.length == 36).reduce((a, b) -> b).orElseThrow();
        assertEquals(LobbyConstants.ITEMS.indexOf("redstone_torch"), initial.items[14].identifier(),
                "Offline toggle must occupy the slot left of the old connect slot");
        assertFalse(initial.items[16].isEmpty(), "Connect must move one slot right");

        client.clickContainer(14, 0);
        client.await(S2CSystemChatPacket.class, 5);
        assertTrue(this.switchInitiator.requests().isEmpty(), "Toggling must not initiate a connection");
        org.junit.jupiter.api.Assertions.assertSame(account, session.account);
        assertEquals("saved-encrypted-account", this.playerStore.load(ProfileKey.javaProfile(uuid)).accountBlob);
        assertTrue(session.playerData.saveLoginInfo);
        client.clickContainer(16, 0);
        assertNull(this.switchInitiator.awaitRequests(1).get(0).account(), "Offline connection must omit the logged-in account");

        client.clickContainer(14, 0);
        client.await(S2CSystemChatPacket.class, 6);
        client.clickContainer(16, 0);
        org.junit.jupiter.api.Assertions.assertSame(account, this.switchInitiator.awaitRequests(2).get(1).account(),
                "Disabling offline mode must reuse the login without asking for login again");
        assertEquals("saved-encrypted-account", this.playerStore.load(ProfileKey.javaProfile(uuid)).accountBlob);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"main", "bookmark", "transfer"})
    @Timeout(15)
    void persistedOfflineModeAppliesToEveryLobbyConnectPath(final String path) throws Exception {
        startServer();
        final UUID uuid = UUID.randomUUID();
        final PlayerData saved = new PlayerData(uuid);
        saved.offlineMode = true;
        this.playerStore.save(saved);
        final LobbyTestClient client;
        if (path.equals("transfer")) {
            LobbyTransfers.post(uuid, "offline.example.net", 25565);
            client = this.join("OfflinePlayer", uuid);
            client.await(S2COpenScreenPacket.class, 1);
        } else {
            client = this.joinWithSavedBookmark("OfflinePlayer", uuid);
        }
        final var session = this.sessionRegistry.get(uuid);
        assertTrue(session.offlineMode(), "The player's selection must survive rejoining");
        final var account = new dev.connectplus.testutil.StubAccount();
        session.account = account;
        session.playerData.accountBlob = "existing-encrypted-account";
        this.playerStore.save(session.playerData);
        switch (path) {
            case "bookmark" -> {
                client.clickContainer(0, 0);
                client.await(S2COpenScreenPacket.class, 5);
                client.clickContainer(25, 0);
            }
            case "transfer" -> client.clickContainer(11, 0);
            default -> {
                client.clickContainer(49, 0);
                client.await(S2COpenScreenPacket.class, 5);
                client.clickContainer(16, 0);
            }
        }
        final var request = this.switchInitiator.awaitRequests(1).get(0);
        assertTrue(request.target().offlineMode());
        assertNull(request.account());
        org.junit.jupiter.api.Assertions.assertSame(account, session.account);
        assertEquals(uuid, request.target().playerId());
        assertEquals("existing-encrypted-account", this.playerStore.load(ProfileKey.javaProfile(uuid)).accountBlob);
    }

    private LobbyTestClient join(final String name) throws InterruptedException {
        final LobbyTestClient client = new LobbyTestClient();
        client.connect(this.serverAddress());
        final UUID uuid = UUID.randomUUID();
        client.login(name, uuid);
        client.await(S2CLoginGameProfilePacket.class);
        final var session = this.sessionRegistry.get(uuid);
        dev.connectplus.testutil.TestClientIdentity.javaIdentity(session, session.lobbyChannel);
        client.write(new C2SLoginAcknowledgedPacket());
        return client;
    }

    private LobbyTestClient join(final String name, final UUID uuid) throws InterruptedException {
        final LobbyTestClient client = new LobbyTestClient();
        client.connect(this.serverAddress());
        client.login(name, uuid);
        client.await(S2CLoginGameProfilePacket.class);
        final var session = this.sessionRegistry.get(uuid);
        dev.connectplus.testutil.TestClientIdentity.javaIdentity(session, session.lobbyChannel);
        client.write(new C2SLoginAcknowledgedPacket());
        return client;
    }

    @Test
    @Timeout(15)
    void accountItemPresentAndLoggedOut() throws Exception {
        startServer();
        final LobbyTestClient client = this.join("AccountPlayer");
        client.await(S2COpenScreenPacket.class);

        final S2CContainerSetContentPacket content = client.awaitChestContent(1);
        assertNotNull(content.items[13], "The account item (slot 13) must be present");
        assertTrue(!content.items[13].isEmpty());
    }

    @Test
    @Timeout(15)
    void bookmarkRoundTripPersistsAcrossReconnect() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("BookmarkPlayer".getBytes(StandardCharsets.UTF_8));

        //First visit: set the address via the chat input, open the bookmarks screen and save the current address
        final LobbyTestClient client = this.join("BookmarkPlayer", uuid);
        client.await(S2COpenScreenPacket.class, 1);
        client.clickContainer(10, 0);
        client.await(S2CSystemChatPacket.class, 3);
        client.sendChat("bmtest.example.net:25565");
        client.await(S2COpenScreenPacket.class, 2);

        client.clickContainer(31, 0);
        final S2COpenScreenPacket bookmarks = client.await(S2COpenScreenPacket.class, 3);
        assertEquals(5, bookmarks.type, "The bookmarks screen (6 rows) must open");

        client.clickContainer(51, 0); //save current
        final S2CSystemChatPacket saved = client.await(S2CSystemChatPacket.class, 4);
        assertTrue(saved.message.asUnformattedString().contains("saved") || saved.message.asUnformattedString().contains("Saved"),
                "A save confirmation must be sent, got: " + saved.message.asUnformattedString());
        client.disconnect();

        //Second visit with the same UUID: the bookmark is restored from disk
        final LobbyTestClient client2 = this.join("BookmarkPlayer", uuid);
        client2.await(S2COpenScreenPacket.class, 1);
        client2.clickContainer(31, 0);
        client2.await(S2COpenScreenPacket.class, 2);
        final S2CContainerSetContentPacket content = client2.awaitChestContent(3);
        assertEquals(54, content.items.length);
        assertNotNull(content.items[0], "The saved bookmark must survive a reconnect");
    }

    @Test
    @Timeout(15)
    void bookmarkDetailConnectHandsOffToSwitchAndUpdatesLastUsed() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("BookmarkPlayer".getBytes(StandardCharsets.UTF_8));

        final LobbyTestClient client = new LobbyTestClient();
        client.connect(this.serverAddress());
        client.sendHaProxyPreamble("lobbytest.example.org", 25565);
        client.login("BookmarkPlayer", uuid);
        client.await(S2CLoginGameProfilePacket.class);
        client.write(new C2SLoginAcknowledgedPacket());
        client.await(S2COpenScreenPacket.class, 1);
        client.clickContainer(10, 0);
        client.await(S2CSystemChatPacket.class, 3);
        client.sendChat("bmtest.example.net:25565");
        client.await(S2COpenScreenPacket.class, 2);
        client.clickContainer(31, 0);
        client.await(S2COpenScreenPacket.class, 3);
        client.clickContainer(51, 0);
        client.await(S2CSystemChatPacket.class, 4);

        //The list opens details; only the detail button hands the target off.
        client.clickContainer(0, 0);
        client.await(S2COpenScreenPacket.class, 5);
        assertTrue(this.switchInitiator.requests().isEmpty());
        client.clickContainer(25, 0);
        final RecordingSwitchInitiator.Request request = this.switchInitiator.awaitRequests(1).get(0);
        assertEquals("bmtest.example.net:25565", request.target().address(), "The bookmark target is handed off");
        assertNull(request.target().version(), "The bookmark was saved with auto detect (no version)");
        assertEquals(uuid, request.target().playerId());
        Thread.sleep(300);
        assertFalse(client.receivedSnapshot().stream().anyMatch(p -> p.getClass().getSimpleName().contains("Transfer")),
                "The hot switch must not send any transfer packet to the client");
        assertTrue(client.isActive());

        //The lastConnectedAt timestamp was persisted
        final PlayerData data = this.playerStore.load(ProfileKey.javaProfile(uuid));
        assertEquals(1, data.bookmarks.size());
        assertTrue(data.bookmarks.get(0).lastConnectedAt > 0, "lastConnectedAt must be updated on bookmark connect");
    }

    @Test
    @Timeout(15)
    void undecryptableAccountDropsToLoggedOutAndKeepsBookmarks() throws Exception {
        //Review Focus 2 (as reworked by task 6 §6): a blob encrypted with a different
        //secret.key (rotated/lost key) is CORRUPT — the session drops to "not logged in"
        //and bookmarks load normally, but the ciphertext is NEVER cleared by the failed
        //restore: only an explicit logout removes stored credentials, and a future join
        //(e.g. after the key is restored from backup) can retry.
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("KeyRotatePlayer".getBytes(StandardCharsets.UTF_8));

        //Seed the player file: one bookmark + an account blob from a DIFFERENT key
        final PlayerData seeded = new PlayerData(uuid);
        seeded.bookmarks.add(new dev.connectplus.session.Bookmark("kept", "keep.example.net", "1.21.4", 1L, 2L));
        final File otherKeyDir = new File(this.dataDir, "other-key");
        otherKeyDir.mkdirs();
        seeded.accountBlob = new TokenStore(otherKeyDir).encrypt("{}");
        this.playerStore.save(seeded);

        final LobbyTestClient client = this.join("KeyRotatePlayer", uuid);
        final S2COpenScreenPacket openScreen = client.await(S2COpenScreenPacket.class);
        assertEquals(3, openScreen.type, "The join must succeed normally despite the undecryptable blob");
        assertTrue(client.isActive());

        final dev.connectplus.session.PlayerSession session = this.sessionRegistry.get(uuid);
        assertNotNull(session, "The session must be registered");
        assertNull(session.account, "The undecryptable account must drop to not-logged-in");
        assertEquals(seeded.accountBlob, session.playerData.accountBlob,
                "A failed restore is never a logout: the encrypted blob stays on the session");
        assertEquals(seeded.accountBlob, this.playerStore.load(ProfileKey.javaProfile(uuid)).accountBlob,
                "The persisted blob must stay untouched by the failed restore");
        assertEquals(1, session.playerData.bookmarks.size(), "Bookmarks must survive the account drop");
        assertEquals("kept", session.playerData.bookmarks.get(0).name);
    }

    @Test
    @Timeout(15)
    void accountLoginBlockedByAllowlist() throws Exception {
        //M6 F8.1: a non-empty allowlist only lets listed player names start the login
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("AllowlistPlayer".getBytes(StandardCharsets.UTF_8));
        final java.util.List<String> original = dev.connectplus.config.CPConfig.accountLoginAllowlist;
        dev.connectplus.config.CPConfig.accountLoginAllowlist = java.util.List.of("SomebodyElse");
        try {
            final LobbyTestClient client = this.join("AllowlistPlayer", uuid);
            client.await(S2COpenScreenPacket.class, 1);

            client.clickContainer(13, 0);
            final S2CSystemChatPacket notice = client.await(S2CSystemChatPacket.class, 3);
            assertTrue(notice.message.asUnformattedString().contains("allowlist"),
                    "The allowlist rejection must be sent, got: " + notice.message.asUnformattedString());
            Thread.sleep(200);

            final dev.connectplus.session.PlayerSession session = this.sessionRegistry.get(uuid);
            assertNotNull(session, "The session must be registered");
            assertNull(session.account, "No account may be attached for a name outside the allowlist");
            assertFalse(session.loginInProgress, "The device code flow must not have started");
            assertTrue(client.isActive(), "The lobby connection must stay alive");
        } finally {
            dev.connectplus.config.CPConfig.accountLoginAllowlist = original;
        }
    }

    @Test
    @Timeout(15)
    void saveLoginToggleChatsStateAndPersists() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("TogglePlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = this.join("TogglePlayer", uuid);
        client.await(S2COpenScreenPacket.class, 1);

        //Toggle off: the chat states the no-save consequence and the flag persists
        client.clickContainer(12, 0);
        final S2CSystemChatPacket off = client.await(S2CSystemChatPacket.class, 3);
        assertTrue(off.message.asUnformattedString().contains("will not be saved"),
                "The off notice must announce the wipe on disconnect, got: " + off.message.asUnformattedString());
        assertFalse(this.playerStore.load(ProfileKey.javaProfile(uuid)).saveLoginInfo, "The off state must persist");

        //Toggle on again: a different notice and the flag flips back
        client.clickContainer(12, 0);
        final S2CSystemChatPacket on = client.await(S2CSystemChatPacket.class, 4);
        assertTrue(on.message.asUnformattedString().contains("will be saved"),
                "The on notice must announce the saving, got: " + on.message.asUnformattedString());
        assertTrue(this.playerStore.load(ProfileKey.javaProfile(uuid)).saveLoginInfo, "The on state must persist");
        assertTrue(client.isActive());
    }

    @Test
    @Timeout(15)
    void saveLoginOffDropsStoredLoginImmediatelyAndKeepsItGoneAfterDisconnect() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("SaveLoginPlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = this.join("SaveLoginPlayer", uuid);
        client.await(S2COpenScreenPacket.class, 1);
        final var session = this.sessionRegistry.get(uuid);
        session.account = new dev.connectplus.testutil.StubAccount();
        session.playerData.accountBlob = "saved-blob";
        this.playerStore.save(session.playerData);

        //Toggling off drops the persisted login right away, while the live
        //login stays valid for the current session
        client.clickContainer(12, 0);
        client.await(S2CSystemChatPacket.class, 3);
        assertNull(this.playerStore.load(ProfileKey.javaProfile(uuid)).accountBlob, "Toggling off must drop the stored login immediately");
        assertNotNull(session.account, "The live login must stay valid for the current session");

        client.disconnect();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && this.server.sessionRegistry().size() > 0) {
            Thread.sleep(50);
        }
        assertNull(this.playerStore.load(ProfileKey.javaProfile(uuid)).accountBlob, "No account data may reappear after the disconnect");
        assertFalse(this.playerStore.load(ProfileKey.javaProfile(uuid)).saveLoginInfo);
    }

    @Test
    @Timeout(15)
    void saveLoginOffSkipsRestoreAndWipesStaleBlobOnDisconnect() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("StaleBlobPlayer".getBytes(StandardCharsets.UTF_8));
        final PlayerData seeded = new PlayerData(uuid);
        seeded.saveLoginInfo = false;
        seeded.accountBlob = "stale-blob";
        seeded.bookmarks.add(new dev.connectplus.session.Bookmark("kept", "keep.example.net", null, 1L, 2L));
        this.playerStore.save(seeded);

        final LobbyTestClient client = this.join("StaleBlobPlayer", uuid);
        client.await(S2COpenScreenPacket.class, 1);
        final var session = this.sessionRegistry.get(uuid);
        assertNull(session.account, "A player with save-login off must not get a stored login restored");

        client.disconnect();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && this.server.sessionRegistry().size() > 0) {
            Thread.sleep(50);
        }
        final PlayerData after = this.playerStore.load(ProfileKey.javaProfile(uuid));
        assertNull(after.accountBlob, "The stale blob must be wiped on disconnect");
        assertFalse(after.saveLoginInfo);
        assertEquals(1, after.bookmarks.size(), "Bookmarks are not login data and must survive");
    }

    @Test
    @Timeout(15)
    void deleteAllDataRequiresConfirmationAndWipesEverything() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("WipePlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = this.join("WipePlayer", uuid);
        client.await(S2COpenScreenPacket.class, 1);
        final var session = this.sessionRegistry.get(uuid);
        session.account = new dev.connectplus.testutil.StubAccount();
        session.playerData.accountBlob = "blob";
        session.playerData.bookmarks.add(new dev.connectplus.session.Bookmark("bm", "bm.example.net", null, 1L, 2L));
        session.serverAddress = "bm.example.net:25565";
        this.playerStore.save(session.playerData);

        //The delete-all item opens the 3-row confirmation screen first
        client.clickContainer(28, 0);
        final S2COpenScreenPacket confirm = client.await(S2COpenScreenPacket.class, 2);
        assertEquals(2, confirm.type, "The delete-all confirmation (3 rows) must open");

        //Cancel returns to the main screen without deleting anything
        client.clickContainer(15, 0);
        assertEquals(3, client.await(S2COpenScreenPacket.class, 3).type, "Cancel must return to the main screen");
        assertEquals("blob", this.playerStore.load(ProfileKey.javaProfile(uuid)).accountBlob, "Cancel must not delete");
        assertEquals(1, this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.size(), "Cancel must not delete");

        //Confirm: the data file is gone and the session drops to logged out
        client.clickContainer(28, 0);
        assertEquals(2, client.await(S2COpenScreenPacket.class, 4).type);
        client.clickContainer(11, 0);
        final S2CSystemChatPacket deleted = client.await(S2CSystemChatPacket.class, 3);
        assertTrue(deleted.message.asUnformattedString().contains("was deleted"),
                "A deletion notice must be sent, got: " + deleted.message.asUnformattedString());
        assertEquals(3, client.await(S2COpenScreenPacket.class, 5).type, "The main screen reopens after the wipe");
        assertNull(this.playerStore.load(ProfileKey.javaProfile(uuid)).accountBlob);
        assertTrue(this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.isEmpty());
        assertNull(session.account, "The wipe logs the session out");
        assertNull(session.serverAddress);
        assertFalse(new File(new File(this.dataDir, "players"), uuid + ".json").exists(),
                "The player data file must be gone from disk");
        assertTrue(client.isActive());
    }

    @Test
    @Timeout(15)
    void loginClickClosesTheGuiBeforeTheLinkArrives() throws Exception {
        startServer();
        final LobbyTestClient client = this.join("LoginGuiPlayer");
        client.await(S2COpenScreenPacket.class, 1);

        //The login click closes the container so the chat link is clickable
        client.clickContainer(13, 0);
        client.await(S2CContainerClosePacket.class, 1);
        final S2CSystemChatPacket loading = client.await(S2CSystemChatPacket.class, 3);
        assertTrue(loading.message.asUnformattedString().contains("Starting the Microsoft device code login"),
                "The loading notice must follow the GUI close, got: " + loading.message.asUnformattedString());
        assertTrue(this.server.sessionRegistry().size() > 0, "The player stays connected");
    }

    @Test
    @Timeout(15)
    void welcomeReflectsTheSaveLoginToggle() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("WelcomePlayer".getBytes(StandardCharsets.UTF_8));

        //Default (saving on): the welcome announces stored settings and tokens
        final LobbyTestClient client = this.join("WelcomePlayer", uuid);
        final S2CSystemChatPacket stored = client.await(S2CSystemChatPacket.class, 2);
        assertTrue(stored.message.asUnformattedString().contains("stored on this proxy"),
                "The saving-on welcome must announce storage, got: " + stored.message.asUnformattedString());
        client.disconnect();

        //Toggle off through the GUI, reconnect: the welcome switches to the wipe notice
        final LobbyTestClient toggler = this.join("WelcomePlayer", uuid);
        toggler.await(S2COpenScreenPacket.class, 1);
        toggler.clickContainer(12, 0);
        toggler.await(S2CSystemChatPacket.class, 3);
        toggler.disconnect();

        final LobbyTestClient client2 = this.join("WelcomePlayer", uuid);
        final S2CSystemChatPacket off = client2.await(S2CSystemChatPacket.class, 2);
        assertTrue(off.message.asUnformattedString().contains("wiped when you disconnect"),
                "The saving-off welcome must announce the wipe, got: " + off.message.asUnformattedString());
        assertFalse(off.message.asUnformattedString().contains("stored on this proxy"));
    }

    /**
     * Joins, sets an address through the chat input and saves it as a bookmark;
     * returns the client sitting on the reopened bookmarks screen (slot 0 holds
     * the bookmark).
     */
    private LobbyTestClient joinWithSavedBookmark(final String name, final UUID uuid) throws Exception {
        final LobbyTestClient client = this.join(name, uuid);
        client.await(S2COpenScreenPacket.class, 1);
        client.clickContainer(10, 0);
        client.await(S2CSystemChatPacket.class, 3);
        client.sendChat("bmtest.example.net:25565");
        client.await(S2COpenScreenPacket.class, 2);
        client.clickContainer(31, 0);
        client.await(S2COpenScreenPacket.class, 3);
        client.clickContainer(51, 0); //save current
        client.await(S2CSystemChatPacket.class, 4);
        client.await(S2COpenScreenPacket.class, 4); //bookmarks reopen after the save
        return client;
    }

    @Test
    @Timeout(15)
    void bookmarkDetailDeleteRequiresConfirmationAndPersists() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("BookmarkPlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = this.joinWithSavedBookmark("BookmarkPlayer", uuid);

        //Left-click opens the detail screen for the bookmark
        client.clickContainer(0, 0);
        final S2COpenScreenPacket detail = client.await(S2COpenScreenPacket.class, 5);
        assertEquals(5, detail.type, "The detail screen (6 rows) must open on left click");

        //Delete entry opens the confirmation screen
        client.clickContainer(53, 0);
        final S2COpenScreenPacket confirm = client.await(S2COpenScreenPacket.class, 6);
        assertEquals(2, confirm.type, "The confirmation screen (3 rows) must open");

        //Cancel returns to the detail screen without deleting
        client.clickContainer(15, 0);
        client.await(S2COpenScreenPacket.class, 7);
        assertEquals(1, this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.size(), "Cancel must not delete");

        //Delete -> confirm: the bookmark is gone from the persisted data
        client.clickContainer(53, 0);
        client.await(S2COpenScreenPacket.class, 8);
        client.clickContainer(11, 0);
        final S2CSystemChatPacket deleted = client.await(S2CSystemChatPacket.class, 5);
        assertTrue(deleted.message.asUnformattedString().contains("deleted"),
                "A deletion notice must be sent, got: " + deleted.message.asUnformattedString());
        assertEquals(0, this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.size(), "The confirmed deletion must persist");
        assertTrue(client.isActive());
    }

    @Test
    @Timeout(15)
    void bookmarkDetailRenameAndAddressChangePersist() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("BookmarkPlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = this.joinWithSavedBookmark("BookmarkPlayer", uuid);

        //Left-click -> detail; rename via the chat input
        client.clickContainer(0, 0);
        client.await(S2COpenScreenPacket.class, 5);
        client.clickContainer(21, 0);
        client.await(S2CSystemChatPacket.class, 5); //the rename prompt (GUI closed)
        client.sendChat("renamed");
        client.await(S2CSystemChatPacket.class, 6);
        client.await(S2COpenScreenPacket.class, 6); //the detail screen reopens
        assertEquals("renamed", this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.get(0).name, "The rename must persist");

        //Change the address via the chat input
        client.clickContainer(23, 0);
        client.await(S2CSystemChatPacket.class, 7);
        client.sendChat("other.example.net:25566");
        client.await(S2CSystemChatPacket.class, 8);
        assertEquals("other.example.net:25566", this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.get(0).address,
                "The address change must persist");

        //A local address is rejected (the shared address rule) and keeps the stored one
        client.clickContainer(23, 0);
        client.await(S2CSystemChatPacket.class, 9);
        client.sendChat("127.0.0.1:25565");
        final S2CSystemChatPacket invalid = client.await(S2CSystemChatPacket.class, 10);
        assertTrue(invalid.message.asUnformattedString().contains("Invalid server address"),
                "The invalid address must be rejected, got: " + invalid.message.asUnformattedString());
        assertEquals("other.example.net:25566", this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.get(0).address,
                "A rejected address must not change the bookmark");
        assertTrue(client.isActive());
    }

    @Test
    @Timeout(15)
    void bookmarkLeftClickOpensDetailsAndOnlyItsConnectButtonStartsSwitching() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("BookmarkPlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = this.joinWithSavedBookmark("BookmarkPlayer", uuid);

        client.clickContainer(0, 0);
        final S2COpenScreenPacket detail = client.await(S2COpenScreenPacket.class, 5);
        assertEquals("§aBookmark details", detail.title.asUnformattedString());
        assertTrue(this.switchInitiator.requests().isEmpty(), "Opening details must not connect");
        client.clickContainer(25, 0);
        final RecordingSwitchInitiator.Request request = this.switchInitiator.awaitRequests(1).get(0);
        assertEquals("bmtest.example.net:25565", request.target().address(), "The detail button hands the bookmark off");
        assertTrue(client.isActive());
    }

    private LobbyTestClient openBookmarkVersionSelector(final UUID uuid) throws Exception {
        final LobbyTestClient client = this.joinWithSavedBookmark("BmVersionPlayer", uuid);
        client.clickContainer(0, 0);
        client.await(S2COpenScreenPacket.class, 5);
        final S2CContainerSetContentPacket detail = client.awaitChestContent(9);
        assertFalse(detail.items[22].isEmpty(), "Version edit must sit between rename (21) and address (23)");
        client.clickContainer(22, 0);
        assertEquals("§aSelect Version", client.await(S2COpenScreenPacket.class, 6).title.asUnformattedString());
        return client;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1})
    @Timeout(15)
    void bookmarkVersionSelectionPersistsAcrossPagesAndConnectUsesIt(final int page) throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("BmVersionPlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = this.openBookmarkVersionSelector(uuid);
        final dev.connectplus.session.Bookmark before = this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.get(0);
        final java.util.List<com.viaversion.viaversion.api.protocol.version.ProtocolVersion> versions =
                new java.util.ArrayList<>(com.viaversion.viaversion.api.protocol.version.ProtocolVersion.getProtocols());
        versions.removeAll(net.raphimc.viabedrock.api.BedrockProtocolVersion.PROTOCOLS);
        java.util.Collections.reverse(versions);
        final var expected = versions.get(page * 45);
        if (page == 1) {
            client.clickContainer(53, 0);
            client.await(S2COpenScreenPacket.class, 7);
        }
        client.clickContainer(0, 0);
        final S2COpenScreenPacket returned = client.await(S2COpenScreenPacket.class, 7 + page);
        assertEquals("§aBookmark details", returned.title.asUnformattedString());
        final dev.connectplus.session.Bookmark saved = this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.get(0);
        assertEquals(expected.getName(), saved.versionName, "The bookmark version must be saved to disk");
        assertEquals(before.name, saved.name);
        assertEquals(before.address, saved.address);
        assertEquals(before.createdAt, saved.createdAt);
        assertEquals(before.lastConnectedAt, saved.lastConnectedAt);
        assertNull(this.sessionRegistry.get(uuid).targetVersion, "Editing a bookmark must leave the current target alone");
        client.clickContainer(49, 0);
        client.await(S2COpenScreenPacket.class, 8 + page);
        client.clickContainer(0, 0);
        client.await(S2COpenScreenPacket.class, 9 + page);
        client.clickContainer(25, 0);
        final RecordingSwitchInitiator.Request request = this.switchInitiator.awaitRequests(1).get(0);
        assertEquals(expected, request.target().version(), "Connecting must use the edited bookmark version");
    }

    @Test
    @Timeout(15)
    void bookmarkVersionBackAndCloseCancelWithoutChangingTheSavedVersion() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("BmVersionPlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = this.openBookmarkVersionSelector(uuid);
        client.clickContainer(49, 0);
        assertEquals("§aBookmark details", client.await(S2COpenScreenPacket.class, 7).title.asUnformattedString());
        client.clickContainer(22, 0);
        client.await(S2COpenScreenPacket.class, 8);
        client.closeContainer();
        assertEquals("§aBookmark details", client.await(S2COpenScreenPacket.class, 9).title.asUnformattedString());
        assertNull(this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.get(0).versionName, "Cancelling must keep auto detection");
        assertNull(this.sessionRegistry.get(uuid).targetVersion);
    }

    @Test
    @Timeout(15)
    void joinsAndOpensMainScreen() throws Exception {
        startServer();
        final LobbyTestClient client = this.join("GuiPlayer");

        final Packet firstPlay = client.firstPlayPacket();
        assertTrue(firstPlay instanceof dev.connectplus.lobby.protocol.packets.play.S2CLoginPacket,
                "First play packet must be the JoinGame packet, got: " + firstPlay.getClass().getName());

        final S2CSystemChatPacket welcome = client.await(S2CSystemChatPacket.class);
        assertTrue(welcome.message.asUnformattedString().contains("Welcome to the ConnectPlus lobby"),
                "Welcome chat must be sent, got: " + welcome.message.asUnformattedString());

        final S2COpenScreenPacket openScreen = client.await(S2COpenScreenPacket.class);
        assertEquals(3, openScreen.type, "The main screen (4 rows) must open right after the spawn");

        final S2CContainerSetContentPacket content = client.awaitChestContent(1);
        assertEquals(1, content.windowId);
        assertEquals(36, content.items.length);
        for (final int slot : new int[]{10, 11, 12, 13, 14, 16, 27, 28, 31, 35}) {
            assertNotNull(content.items[slot], "Main screen slot " + slot + " must hold an item");
            assertTrue(!content.items[slot].isEmpty(), "Main screen slot " + slot + " must not be empty");
        }
        assertTrue(content.items[29].isEmpty(), "The former third-column bookmark entry must be empty");
        assertEquals(LobbyConstants.ITEMS.indexOf("ender_chest"), content.items[31].identifier());
    }

    @Test
    @Timeout(15)
    void versionSelectorRoundTrip() throws Exception {
        startServer();
        final LobbyTestClient client = this.join("VersionPlayer");
        client.await(S2COpenScreenPacket.class);

        //Click the anvil (slot 11): the version selector (6 rows = type 5) opens
        client.clickContainer(11, 0);
        final S2COpenScreenPacket selector = client.await(S2COpenScreenPacket.class, 2);
        assertEquals(5, selector.type, "The version selector screen (6 rows) must open");

        final S2CContainerSetContentPacket content = client.awaitChestContent(3);
        //occurrence 2 is the click echo (current screen re-sent); occurrence 3 is the selector content
        assertEquals(54, content.items.length);
        assertNotNull(content.items[0], "The first version entry must hold an item");
        assertTrue(!content.items[0].isEmpty());

        //Click the first (newest) version: the main screen opens again
        client.clickContainer(0, 0);
        final S2COpenScreenPacket back = client.await(S2COpenScreenPacket.class, 3);
        assertEquals(3, back.type, "Picking a version must return to the main screen");
    }

    @Test
    @Timeout(15)
    void addressChatInputAndConnectHandsOffToSwitch() throws Exception {
        startServer();
        final LobbyTestClient client = new LobbyTestClient();
        client.connect(this.serverAddress());
        client.sendHaProxyPreamble("lobbytest.example.org", 25565);
        client.login("TransferPlayer");
        client.await(S2CLoginGameProfilePacket.class);
        client.write(new C2SLoginAcknowledgedPacket());
        client.await(S2COpenScreenPacket.class, 1);

        //Click the name tag (slot 10): the GUI closes and the chat prompt arrives
        client.clickContainer(10, 0);
        final S2CSystemChatPacket info = client.await(S2CSystemChatPacket.class, 3);
        assertTrue(info.message.asUnformattedString().contains("enter the server ip"),
                "The address input prompt must be sent, got: " + info.message.asUnformattedString());

        //Type the address into the chat: the input is accepted and the main screen reopens
        client.sendChat("lobbytest.example.org:25565");
        client.await(S2COpenScreenPacket.class, 2);

        //Click the door (slot 16): the connect flow hands the target to the switch engine
        client.clickContainer(16, 0);
        final RecordingSwitchInitiator.Request request = this.switchInitiator.awaitRequests(1).get(0);
        assertEquals("lobbytest.example.org:25565", request.target().address(), "The entered address is handed off");
        assertNull(request.target().version(), "Version stays unset -> auto detect");
        Thread.sleep(300);
        assertFalse(client.receivedSnapshot().stream().anyMatch(p -> p.getClass().getSimpleName().contains("Transfer")),
                "The hot switch must not send any transfer packet to the client");
        assertTrue(client.isActive(), "The lobby connection stays alive (the switch runs proxy-side)");
    }

    @Test
    @Timeout(15)
    void addressInputCancelledByCommand() throws Exception {
        startServer();
        final LobbyTestClient client = this.join("CancelPlayer");
        client.await(S2COpenScreenPacket.class, 1);

        client.clickContainer(10, 0);
        client.await(S2CSystemChatPacket.class, 3); //the input prompt (after the 2-line welcome)

        //A command instead of an address cancels the pending input
        client.sendChat("/cancel");
        final S2CSystemChatPacket cancelled = client.await(S2CSystemChatPacket.class, 4);
        assertTrue(cancelled.message.asUnformattedString().contains("Cancelled input"),
                "The cancellation notice must be sent, got: " + cancelled.message.asUnformattedString());
        client.await(S2COpenScreenPacket.class, 2);
    }

    @Test
    @Timeout(15)
    void outOfRangeClickIgnored() throws Exception {
        startServer();
        final LobbyTestClient client = this.join("ClickyPlayer");
        client.await(S2COpenScreenPacket.class, 1);

        //A click far outside the 36-slot main window must not kill the connection
        client.clickContainer(999, 0);
        client.clickContainer(11, 0);
        final S2COpenScreenPacket selector = client.await(S2COpenScreenPacket.class, 2);
        assertEquals(5, selector.type, "The normal flow must still work after an out-of-range click");
        assertTrue(client.isActive());
    }

    @Test
    @Timeout(15)
    void guiDisconnectCleansSession() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("CleanPlayer".getBytes(StandardCharsets.UTF_8));
        final LobbyTestClient client = new LobbyTestClient();
        client.connect(this.serverAddress());
        client.login("CleanPlayer", uuid);
        client.await(S2CLoginGameProfilePacket.class);
        client.write(new C2SLoginAcknowledgedPacket());
        client.await(S2COpenScreenPacket.class, 1);

        assertEquals(1, this.server.sessionRegistry().size());
        assertNotNull(this.server.sessionRegistry().get(uuid));

        client.disconnect();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && this.server.sessionRegistry().size() > 0) {
            Thread.sleep(50);
        }
        assertEquals(0, this.server.sessionRegistry().size(), "The session must be removed on disconnect");
    }

    @Test
    @Timeout(15)
    void transferConfirmScreenOpensInsteadOfMainScreenAndFollows() throws Exception {
        //M7 F7.1: a pending transfer (posted by the engine under transferPolicy=confirm)
        //replaces the main screen with the confirmation GUI; following hands the
        //announced target to the switch initiator
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("TransferPlayer".getBytes(StandardCharsets.UTF_8));
        dev.connectplus.lobby.LobbyTransfers.post(uuid, "follow.example.net", 25565);

        final LobbyTestClient client = this.join("TransferPlayer", uuid);
        final S2COpenScreenPacket confirm = client.await(S2COpenScreenPacket.class, 1);
        assertEquals(2, confirm.type, "The transfer confirmation screen (3 rows) must replace the main screen");

        client.clickContainer(11, 0); //follow
        final RecordingSwitchInitiator.Request request = this.switchInitiator.awaitRequests(1).get(0);
        assertEquals("follow.example.net:25565", request.target().address(),
                "The follow click hands the transfer target to the engine");
        assertEquals(uuid, request.target().playerId());
        assertTrue(client.isActive());

        //The started switch reopens the main screen (the fallback path lands there)
        final S2COpenScreenPacket main = client.await(S2COpenScreenPacket.class, 2);
        assertEquals(3, main.type, "After following, the main screen must be open again");
    }

    @Test
    @Timeout(15)
    void transferConfirmStayReturnsToMainScreenWithoutHandoff() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("StayPlayer".getBytes(StandardCharsets.UTF_8));
        dev.connectplus.lobby.LobbyTransfers.post(uuid, "evil.example.net", 25565);

        final LobbyTestClient client = this.join("StayPlayer", uuid);
        client.await(S2COpenScreenPacket.class, 1);

        client.clickContainer(15, 0); //stay
        final S2COpenScreenPacket main = client.await(S2COpenScreenPacket.class, 2);
        assertEquals(3, main.type, "Staying must return to the main screen");
        Thread.sleep(300);
        assertEquals(0, this.switchInitiator.requests().size(), "Staying must not hand anything off");
        assertEquals(0, dev.connectplus.lobby.LobbyTransfers.size(), "The pending transfer was consumed by this join");
    }

    @Test
    @Timeout(15)
    void transferConfirmRejectsLocalAddress() throws Exception {
        //The GUI must not grant reachability a hand-typed /connect would not have:
        //a transfer pointing at this machine (the proxy itself) is rejected
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("LocalPlayer".getBytes(StandardCharsets.UTF_8));
        dev.connectplus.lobby.LobbyTransfers.post(uuid, "127.0.0.1", 25565);

        final LobbyTestClient client = this.join("LocalPlayer", uuid);
        client.await(S2COpenScreenPacket.class, 1);

        client.clickContainer(11, 0); //follow
        final S2CSystemChatPacket notice = client.await(S2CSystemChatPacket.class, 3);
        assertTrue(notice.message.asUnformattedString().contains("Invalid server address"),
                "The local transfer target must be rejected, got: " + notice.message.asUnformattedString());
        Thread.sleep(200);
        assertEquals(0, this.switchInitiator.requests().size(), "A rejected target must never reach the engine");
        assertTrue(client.isActive());

        //The rejection returns to the main screen (the player keeps control)
        final S2COpenScreenPacket main = client.await(S2COpenScreenPacket.class, 2);
        assertEquals(3, main.type, "After a rejected follow, the main screen must be open");
    }

    @Test
    @Timeout(15)
    void closingTheMainScreenKeepsThePlayerInLobby() throws Exception {
        startServer();
        final LobbyTestClient client = this.join("CloseStaysPlayer");
        client.await(S2COpenScreenPacket.class, 1);

        //Pre-1.19 clients cannot chat while a container is open, so closing the
        //main screen must NOT disconnect (V7 finding) — it just drops the GUI
        client.closeContainer();
        Thread.sleep(300);
        assertTrue(client.isActive(), "Closing the main screen must keep the connection alive");
        //No further OpenScreen: the lobby is now in the no-GUI state
        assertEquals(0, client.receivedSnapshot().stream()
                        .filter(p -> p instanceof S2COpenScreenPacket).count() - 1,
                "No new screen may open on close");
    }

    @Test
    @Timeout(15)
    void plainChatWithNoGuiLeavesTheMainScreenClosed() throws Exception {
        startServer();
        final LobbyTestClient client = this.join("ReopenPlayer");
        client.await(S2COpenScreenPacket.class, 1);

        client.closeContainer();
        client.sendChat("hi");
        //The usage reply of a malformed exit command proves the preceding plain
        //chat line was processed; /cphelp no longer exists as a probe
        client.sendChatCommand("disconnect extra");
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (client.receivedSnapshot().stream().filter(p -> p instanceof S2CSystemChatPacket chat
                && chat.message.asUnformattedString().contains("/disconnect")).count() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(client.receivedSnapshot().stream().anyMatch(p -> p instanceof S2CSystemChatPacket chat
                && chat.message.asUnformattedString().contains("/disconnect")), "A command reply proves the preceding chat was processed");
        assertEquals(1, client.receivedSnapshot().stream().filter(S2COpenScreenPacket.class::isInstance).count(),
                "A plain chat line with no GUI must leave the main screen closed");
        assertFalse(client.receivedSnapshot().stream().anyMatch(p -> p instanceof S2CSystemChatPacket chat
                && chat.message.asUnformattedString().contains("Type anything in chat")));
        assertTrue(client.isActive());
    }

    @Test
    @Timeout(15)
    void transferConfirmClosingCountsAsStaying() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("ClosePlayer".getBytes(StandardCharsets.UTF_8));
        dev.connectplus.lobby.LobbyTransfers.post(uuid, "evil.example.net", 25565);

        final LobbyTestClient client = this.join("ClosePlayer", uuid);
        client.await(S2COpenScreenPacket.class, 1);

        client.closeContainer();
        final S2COpenScreenPacket main = client.await(S2COpenScreenPacket.class, 2);
        assertEquals(3, main.type, "Closing the confirmation must return to the main screen");
        Thread.sleep(200);
        assertEquals(0, this.switchInitiator.requests().size(), "Closing must not hand anything off");
        assertTrue(client.isActive());
    }

}
