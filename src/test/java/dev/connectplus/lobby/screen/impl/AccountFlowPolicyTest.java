package dev.connectplus.lobby.screen.impl;

import dev.connectplus.accounts.CPAccount;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.config.CPConfig;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import dev.connectplus.testutil.StubAccount;
import dev.connectplus.testutil.ViaProxyTestConfig;
import net.raphimc.viaproxy.ViaProxy;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccountFlowPolicyTest {
    private final java.util.List<io.netty.channel.embedded.EmbeddedChannel> authenticatedClients = new java.util.ArrayList<>();

    private void markJava(PlayerSession session) {
        var client = new io.netty.channel.embedded.EmbeddedChannel();
        authenticatedClients.add(client);
        dev.connectplus.testutil.TestClientIdentity.javaIdentity(session, client);
    }

    @org.junit.jupiter.api.AfterEach
    void closeAuthenticatedClients() {
        authenticatedClients.forEach(io.netty.channel.embedded.EmbeddedChannel::finishAndReleaseAll);
    }

    @org.junit.jupiter.api.Test
    void oldLobbyCompletionCannotChangeSessionAfterActiveChannelOwnershipMoves() throws IOException {
        ViaProxyTestConfig.init();
        final var client = new io.netty.channel.embedded.EmbeddedChannel();
        final var oldLobby = new io.netty.channel.embedded.EmbeddedChannel();
        final var currentLobby = new io.netty.channel.embedded.EmbeddedChannel();
        final var uuid = UUID.randomUUID();
        final var link = UUID.randomUUID();
        try {
            final var proxy = new net.raphimc.viaproxy.proxy.session.ProxyConnection(
                    new net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer(
                            net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler::new), client);
            client.attr(dev.connectplus.compat.CPAttributeKeys.PLAYER_IDENTITY).set(
                    new dev.connectplus.session.PlayerIdentity(uuid, "AccountPlayer"));
            dev.connectplus.compat.LobbyLink.register(link, proxy);
            oldLobby.attr(dev.connectplus.compat.CPAttributeKeys.LOBBY_LINK_ID).set(link);
            currentLobby.attr(dev.connectplus.compat.CPAttributeKeys.LOBBY_LINK_ID).set(link);
            final var tokens = new TokenStore(this.dataDir);
            final var store = new PlayerStore(new File(this.dataDir, "players"));
            final var first = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                    new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                    new dev.connectplus.session.AccountSessionCoordinator(store, () -> null),
                    new RecordingSwitchInitiator(), null, new AtomicInteger());
            first.loadSession(oldLobby, uuid, "AccountPlayer");
            final var session = first.getSession();
            final var next = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                    new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                    new dev.connectplus.session.AccountSessionCoordinator(store, () -> null),
                    new RecordingSwitchInitiator(), null, new AtomicInteger());
            next.loadSession(currentLobby, uuid, "AccountPlayer");
            assertSame(session, next.getSession());
            assertTrue(oldLobby.isActive(), "Remote FIN may not have reached the old lobby's event loop yet");
            session.loginInProgress = true;
            assertFalse(AccountFlow.finishLogin(session, oldLobby));
            assertTrue(session.loginInProgress);
            assertFalse(AccountFlow.installAccountForOwner(session, new StubAccount(), first, oldLobby),
                    "Stale completion must not install or persist an account on the resumed session");
            assertNull(session.account);
            assertNull(store.load(ProfileKey.javaProfile(uuid)).accountBlob);
            assertTrue(AccountFlow.finishLogin(session, currentLobby));
        } finally {
            dev.connectplus.compat.LobbyLink.unregister(link);
            client.finishAndReleaseAll();
            oldLobby.finishAndReleaseAll();
            currentLobby.finishAndReleaseAll();
        }
    }
    @org.junit.jupiter.api.Test
    void closedLobbyLoginCompletionCannotClearAReplacementLoginsGuard() {
        final var oldLobby = new io.netty.channel.embedded.EmbeddedChannel();
        final var currentLobby = new io.netty.channel.embedded.EmbeddedChannel();
        final var session = new PlayerSession(UUID.randomUUID(), "AccountPlayer");
        session.lobbyChannel = currentLobby;
        try {
            oldLobby.close();
            session.loginInProgress = true; // a new attempt after returning to the lobby
            assertFalse(AccountFlow.finishLogin(session, oldLobby));
            assertTrue(session.loginInProgress, "The old attempt must not unlock a replacement attempt");
            assertTrue(AccountFlow.finishLogin(session, currentLobby));
            assertFalse(session.loginInProgress);
        } finally {
            oldLobby.finishAndReleaseAll();
            currentLobby.finishAndReleaseAll();
        }
    }

    /**
     * Task 6 R1 residual: the install must check lease CURRENCY (isCurrent +
     * !displaced), not only the lobby channel ownership. A session whose lease
     * was invalidated (displacement / supersede) but whose lobby channel still
     * looks owned must not persist credentials.
     */
    @org.junit.jupiter.api.Test
    void installRefusedWithoutACurrentLease() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final var tokens = new TokenStore(this.dataDir);
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final var session = new PlayerSession(UUID.randomUUID(), "AccountPlayer");
        session.playerData = new PlayerData(session.uuid);
        session.playerData.saveLoginInfo = true;
        session.playerData.accountBlob = "previous-encrypted-account";
        store.save(session.playerData);
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(store, () -> null);
        final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                coordinator,
                new RecordingSwitchInitiator(), null, new AtomicInteger());
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            //A protected profile whose lease is dead (the displacement ran): the
            //install must refuse even though the channel ownership looks intact.
            final dev.connectplus.session.SessionLease deadLease = new dev.connectplus.session.SessionLease() {
                @Override public java.util.UUID connectionId() { return session.connectionId; }
                @Override public long generation() { return 1; }
            };
            session.connectionId = UUID.randomUUID();
            session.lease = deadLease;
            session.profileKey = ProfileKey.javaProfile(session.uuid);
            session.displaced = true; // the freeze step ran; the lease is dead

            final boolean installed = AccountFlow.installAccount(session, new StubAccount(), handler);
            assertFalse(installed, "A displaced session must not install credentials");
            assertNull(session.account);
            assertEquals("previous-encrypted-account", store.load(ProfileKey.javaProfile(session.uuid)).accountBlob,
                    "The stored blob must stay untouched by the refused install");
        } finally {
            coordinator.shutdown();
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    @org.junit.jupiter.api.Test
    void accountInstallRecordsTheKnownJavaNameWithoutMarkingAVisit() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final var tokens = new TokenStore(this.dataDir);
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final dev.connectplus.session.PlayerVisitStore visits =
                new dev.connectplus.session.PlayerVisitStore(new File(this.dataDir, "visits-index.json"));
        final var session = new PlayerSession(UUID.randomUUID(), "AccountPlayer");
        session.playerData = new PlayerData(session.uuid);
        session.playerData.saveLoginInfo = false;
        //A verified Java connection: the install policy requires a trusted identity
        final var c2p = new io.netty.channel.embedded.EmbeddedChannel();
        c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).set(
                dev.connectplus.identity.ClientIdentity.verifiedJava(session.uuid, "AccountPlayer", session.uuid));
        session.c2pChannel = c2p;
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(store, () -> null);
        final java.util.concurrent.ExecutorService storage = java.util.concurrent.Executors.newSingleThreadExecutor();
        final UUID profileUuid = new UUID(4, 8);
        final var named = new StubAccount() {
            @Override public String profileName() { return "RealProfileName"; }
            @Override public UUID uuid() { return profileUuid; }
        };
        final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                new dev.connectplus.identity.IdentityLinkStore(store.playersDir()), coordinator,
                new RecordingSwitchInitiator(), storage, new AtomicInteger(), null, visits);
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            assertTrue(AccountFlow.installAccount(session, named, handler), "The install must succeed");
            storage.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, visits.snapshot().uniqueVisitorCount(java.util.Map.of()),
                    "A Microsoft login must never count as a lobby visit");
            final var matches = visits.snapshot().matchByName("realprofilename");
            assertEquals(1, matches.size(), "The account's real profile name is recorded, not the display string");
            assertEquals(profileUuid, matches.get(0).javaUuid());
            assertFalse(matches.get(0).fromVisit(), "A known name is not a visit record");
        } finally {
            storage.shutdownNow();
            coordinator.shutdown();
            c2p.finishAndReleaseAll();
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    /**
     * Task 6 §6: a session-only login (saveLoginInfo=false) stays valid for the
     * session — but once the session's lease is not current any more (a
     * displacement invalidated it), the connect target selection must not hand
     * the credentials to the backend: no revival of a dead session's login.
     */
    @org.junit.jupiter.api.Test
    void connectTargetSelectionDropsTheAccountWhenTheLeaseIsStale() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), new TokenStore(this.dataDir),
                store, new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                new dev.connectplus.session.AccountSessionCoordinator(store, () -> null),
                new RecordingSwitchInitiator(), null, new AtomicInteger());
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(store, () -> null);
        final var lobby = new io.netty.channel.embedded.EmbeddedChannel();
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            final PlayerSession session = new PlayerSession(UUID.randomUUID(), "AccountPlayer");
            markJava(session);
            session.lobbyChannel = lobby;
            session.playerData = new PlayerData(session.uuid);
            session.playerData.saveLoginInfo = false;
            session.account = new StubAccount();
            session.connectionId = UUID.randomUUID();
            session.lease = new dev.connectplus.session.SessionLease() {
                @Override public java.util.UUID connectionId() { return session.connectionId; }
                @Override public long generation() { return 1; }
            };
            session.displaced = true; // the lease was invalidated by a displacement
            session.serverAddress = "backend.example.net:25565";

            final var initiator = new RecordingSwitchInitiator();
            dev.connectplus.lobby.ConnectFlow.start(session, new dev.connectplus.lobby.states.StateHandler(handler, lobby) {
            }, initiator);
            assertNull(initiator.requests().get(0).account(),
                    "A stale lease must not hand credentials to the backend");
        } finally {
            coordinator.shutdown();
            lobby.finishAndReleaseAll();
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    @TempDir File dataDir;

    @ParameterizedTest
    @CsvSource({"false,true,false", "true,false,false", "true,true,false", "true,true,true"})
    void loginCompletionCannotPersistCredentialsAfterPolicyTurnsOff(
            final boolean proxyOnline, final boolean pluginLogin, final boolean turnOffDuringEncryption) throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final var encrypts = new AtomicInteger();
        final TokenStore tokens = new TokenStore(this.dataDir) {
            @Override public String encrypt(final String json) {
                encrypts.incrementAndGet();
                if (turnOffDuringEncryption) ViaProxy.getConfig().setProxyOnlineMode(false);
                return super.encrypt(json);
            }
        };
        final PlayerSession session = new PlayerSession(UUID.randomUUID(), "AccountPlayer");
        markJava(session);
        session.playerData = new PlayerData(session.uuid);
        session.playerData.accountBlob = "previous-encrypted-account";
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        store.save(session.playerData);
        final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                new dev.connectplus.session.AccountSessionCoordinator(store, () -> null),
                new RecordingSwitchInitiator(), null, new AtomicInteger());
        try {
            ViaProxy.getConfig().setProxyOnlineMode(proxyOnline);
            CPConfig.allowAccountLogin = pluginLogin;
            final CPAccount account = new StubAccount();
            final boolean installed = AccountFlow.installAccount(session, account, handler);
            final boolean expected = proxyOnline && pluginLogin && !turnOffDuringEncryption;
            assertEquals(expected, installed);
            if (expected) {
                assertSame(account, session.account);
                assertEquals(account.toJson(), tokens.decrypt(store.load(ProfileKey.javaProfile(session.uuid)).accountBlob));
            } else {
                assertNull(session.account);
                assertEquals("previous-encrypted-account", session.playerData.accountBlob);
                assertEquals("previous-encrypted-account", store.load(ProfileKey.javaProfile(session.uuid)).accountBlob);
                assertEquals(turnOffDuringEncryption ? 1 : 0, encrypts.get());
            }
        } finally {
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    @org.junit.jupiter.api.Test
    void loginWithSaveLoginOffStaysSessionOnly() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final var encrypts = new AtomicInteger();
        final TokenStore tokens = new TokenStore(this.dataDir) {
            @Override public String encrypt(final String json) {
                encrypts.incrementAndGet();
                return super.encrypt(json);
            }
        };
        final PlayerSession session = new PlayerSession(UUID.randomUUID(), "AccountPlayer");
        markJava(session);
        session.playerData = new PlayerData(session.uuid);
        session.playerData.saveLoginInfo = false;
        session.playerData.accountBlob = "previous-encrypted-account";
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        store.save(session.playerData);
        final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                new dev.connectplus.session.AccountSessionCoordinator(store, () -> null),
                new RecordingSwitchInitiator(), null, new AtomicInteger());
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            final CPAccount account = new StubAccount();
            assertTrue(AccountFlow.installAccount(session, account, handler), "the login itself succeeds");
            assertSame(account, session.account, "the login stays valid for the current session");
            assertNull(session.playerData.accountBlob, "no blob may be kept on the session");
            assertNull(store.load(ProfileKey.javaProfile(session.uuid)).accountBlob, "nothing may be persisted with save-login off");
            assertFalse(store.load(ProfileKey.javaProfile(session.uuid)).saveLoginInfo, "the toggle state persists untouched");
            assertEquals(0, encrypts.get(), "no encryption work is done when nothing is stored");
        } finally {
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    /** A ScreenHandler wired to the real handler + an embedded lobby channel. */
    private dev.connectplus.lobby.screen.ScreenHandler newDeleteScreenHandler(final LobbyServerHandler handler, final io.netty.channel.embedded.EmbeddedChannel lobby) {
        return new ScreenHandler(new dev.connectplus.lobby.states.StateHandler(handler, lobby) {
        });
    }

    /**
     * Review round 1, IMPORTANT 2: bookmark writes (add via command/GUI, edit,
     * delete, connect timestamp) and the offline-mode toggle are profile writes
     * like the settings save — a displaced session's GUI can still be alive during
     * the bounded exit window, and its clicks must not rewrite the profile the new
     * holder now owns. Pinned with a real coordinator: while the lease is current
     * the writes work; after the displacement they are refused (no profile write).
     */
    @org.junit.jupiter.api.Test
    void bookmarkAndOfflineModeWritesAreLeaseGated() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(
                new PlayerStore(new File(this.dataDir, "players")), () -> null);
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
            final var session = new PlayerSession(UUID.randomUUID(), "BookmarkPlayer");
            session.connectionId = UUID.randomUUID();
            session.playerData = new PlayerData(session.uuid);
            store.save(session.playerData);
            final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), new TokenStore(this.dataDir),
                    store, new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                    coordinator, new RecordingSwitchInitiator(), null, new AtomicInteger());

            //A real claim: the session holds a current lease.
            final var lease = coordinator.claim(session, ProfileKey.javaProfile(session.uuid), java.util.Set.of())
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            session.lease = lease;
            session.generation = lease.generation();

            //Current lease: the bookmark add works and persists.
            session.serverAddress = "bm.example.net:25565";
            assertEquals(BookmarksScreen.SaveResult.SAVED,
                    BookmarksScreen.saveNamed(session, handler, "current"),
                    "A current session may add a bookmark");
            assertEquals(1, store.load(ProfileKey.javaProfile(session.uuid)).bookmarks.size());
            assertEquals("bm.example.net:25565",
                    store.load(ProfileKey.javaProfile(session.uuid)).bookmarks.get(0).address);

            //Current lease: the offline-mode toggle works and persists.
            session.playerData.offlineMode = false;
            assertTrue(AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter()));

            //The displacement: freeze + invalidate exactly like §5 step 2/3.
            session.displaced = true;
            coordinator.release(lease).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);

            //Refused: bookmark add.
            assertEquals(BookmarksScreen.SaveResult.REFUSED_STALE_SESSION,
                    BookmarksScreen.saveNamed(session, handler, "after-displacement"),
                    "A displaced session must not add a bookmark");
            //Refused: bookmark edit through the real /bm-style path (the in-memory
            //object may change, the FILE must not).
            final var bm = session.playerData.bookmarks.get(0);
            final String addressBefore = bm.address;
            bm.address = "evil.example.net:25565";
            assertEquals(addressBefore, store.load(ProfileKey.javaProfile(session.uuid)).bookmarks.get(0).address,
                    "The displaced session's edit must not reach the profile file");
            final var lobby = new io.netty.channel.embedded.EmbeddedChannel();
            //Refused: bookmark delete through the real screen path. The guard runs
            //inside delete() BEFORE its post-delete screen reopen (whose GUI init
            //needs a fully wired handler, irrelevant to the persistence assertion).
            try {
                final var deleteScreen = new BookmarkDeleteConfirmScreen(dev.connectplus.lobby.screen.Lang.EN, "current", 0);
                deleteScreen.delete(session, newDeleteScreenHandler(handler, lobby));
            } catch (final RuntimeException reopenSideEffect) {
                //the post-delete reopen may fail on the unwired GUI rig; the
                //persistence assertions below are what the guard governs
            }
            assertEquals(1, store.load(ProfileKey.javaProfile(session.uuid)).bookmarks.size(),
                    "The displaced session's delete must not reach the profile file");
            //Refused: offline-mode toggle — the persisted flag stays untouched.
            final PlayerData onDisk = store.load(ProfileKey.javaProfile(session.uuid));
            assertFalse(onDisk.offlineMode, "The offline flag was never enabled, so no write may have happened");
            assertEquals(1, onDisk.bookmarks.size(),
                    "No profile write may result from the displaced session's clicks");
            assertFalse(AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter()),
                    "The shared coordinator must see the displaced session as stale");
        } finally {
            coordinator.shutdown();
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    /**
     * Final fix, IMPORTANT 1: logout is the one path that clears stored
     * credentials, so it must be lease-gated like the install — a displaced-but-
     * not-yet-evicted session (frozen, GUI still live during the bounded exit
     * window) must not wipe the stored blob onto the profile file the arriving
     * same-identity holder is about to restore. A current session's logout still
     * works and persists.
     */
    @org.junit.jupiter.api.Test
    void logoutIsLeaseGated() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(store, () -> null);
        final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), new TokenStore(this.dataDir),
                store, new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                coordinator, new RecordingSwitchInitiator(), null, new AtomicInteger());
        final var lobby = new io.netty.channel.embedded.EmbeddedChannel();
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            final PlayerSession session = new PlayerSession(UUID.randomUUID(), "LogoutPlayer");
            markJava(session);
            session.connectionId = UUID.randomUUID();
            session.lobbyChannel = lobby;
            session.playerData = new PlayerData(session.uuid);
            session.playerData.saveLoginInfo = true;
            session.playerData.accountBlob = "stored-encrypted-account";
            store.save(session.playerData);
            final var account = new StubAccount();
            session.account = account;

            //A real claim: the session holds a current lease.
            final var lease = coordinator.claim(session, ProfileKey.javaProfile(session.uuid), java.util.Set.of())
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            session.lease = lease;
            session.generation = lease.generation();

            //Current lease: logout works and wipes the blob on disk.
            final var screenHandler = newDeleteScreenHandler(handler, lobby);
            AccountFlow.logout(session, screenHandler, handler);
            assertNull(session.account, "A current session's logout removes the live login");
            assertNull(session.playerData.accountBlob, "A current session's logout wipes the session blob");
            assertNull(store.load(ProfileKey.javaProfile(session.uuid)).accountBlob,
                    "A current session's logout persists the wipe to the profile file");

            //Rebuild the stored state, then displace exactly like §5 step 2/3
            //(freeze + lease invalidation) while the GUI is still alive.
            session.account = account;
            session.playerData.accountBlob = "stored-encrypted-account";
            store.save(session.playerData);
            session.displaced = true;
            coordinator.release(lease).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);

            //Refused: the displaced session's logout touches nothing.
            AccountFlow.logout(session, screenHandler, handler);
            assertEquals("stored-encrypted-account", session.playerData.accountBlob,
                    "A displaced session's logout must not null the session blob");
            assertEquals("stored-encrypted-account", store.load(ProfileKey.javaProfile(session.uuid)).accountBlob,
                    "A displaced session's logout must not wipe the arriving holder's stored credentials");
            assertSame(account, session.account, "A displaced session's logout must not drop the live login");
        } finally {
            coordinator.shutdown();
            lobby.finishAndReleaseAll();
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    /**
     * Final fix, IMPORTANT 1 (delete-all): both entry points — the confirmation
     * click and the main screen's TNT item — are lease-gated. A displaced
     * session must not delete the profile file the arriving holder owns, and its
     * session state must stay untouched. The entry item is pinned with a real
     * container click through the ScreenHandler.
     */
    @org.junit.jupiter.api.Test
    void deleteAllIsLeaseGatedAtTheConfirmClickAndTheEntryItem() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(store, () -> null);
        final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), new TokenStore(this.dataDir),
                store, new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                coordinator, new RecordingSwitchInitiator(), null, new AtomicInteger());
        final var lobby = new io.netty.channel.embedded.EmbeddedChannel();
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            //The real loadSession rig (bare diagnostic join): the handler owns a session.
            handler.loadSession(lobby, UUID.randomUUID(), "WipePlayer");
            final PlayerSession session = handler.getSession();
            session.playerData.accountBlob = "stored-encrypted-account";
            session.playerData.bookmarks.add(new dev.connectplus.session.Bookmark("bm", "bm.example.net", null, 1L, 2L));
            store.save(session.playerData);
            session.account = new StubAccount();
            session.serverAddress = "bm.example.net:25565";

            final var lease = coordinator.claim(session, ProfileKey.javaProfile(session.uuid), java.util.Set.of())
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            session.lease = lease;
            session.generation = lease.generation();

            //Current lease: the confirm click wipes the profile file.
            final var screenHandler = newDeleteScreenHandler(handler, lobby);
            new DeleteAllConfirmScreen(dev.connectplus.lobby.screen.Lang.EN).deleteAll(session, screenHandler);
            assertFalse(store.exists(ProfileKey.javaProfile(session.uuid)),
                    "A current session's delete-all removes the profile file");
            assertNull(session.account);
            assertNull(session.serverAddress);

            //Rebuild, then displace exactly like §5 step 2/3 while the GUI is alive.
            session.playerData = new PlayerData(session.uuid);
            session.playerData.accountBlob = "stored-encrypted-account";
            store.save(session.playerData);
            session.account = new StubAccount();
            session.serverAddress = "bm.example.net:25565";
            session.displaced = true;
            coordinator.release(lease).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);

            //Refused at the confirm click: file and session state stay intact.
            new DeleteAllConfirmScreen(dev.connectplus.lobby.screen.Lang.EN).deleteAll(session, screenHandler);
            assertEquals("stored-encrypted-account", store.load(ProfileKey.javaProfile(session.uuid)).accountBlob,
                    "A displaced session's delete-all must not destroy the arriving holder's profile file");
            assertNotNull(session.account, "A displaced session's delete-all must not touch the session state");
            assertNotNull(session.serverAddress);
            assertTrue(store.exists(ProfileKey.javaProfile(session.uuid)), "The profile file must survive");

            //Refused at the entry item: the TNT click opens NO confirmation screen.
            screenHandler.openScreen(new MainScreen(dev.connectplus.lobby.screen.Lang.EN));
            while (lobby.readOutbound() != null) { } //drain the open-screen writes
            final var click = new dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket();
            click.containerId = 1;
            click.slot = 28;
            screenHandler.handle(click);
            Object packet;
            while ((packet = lobby.readOutbound()) != null) {
                assertFalse(packet instanceof dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket,
                        "A displaced session's delete-all entry item must not open the confirmation screen");
            }
        } finally {
            coordinator.shutdown();
            lobby.finishAndReleaseAll();
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    /**
     * Final fix, IMPORTANT 2: while a verified proxied session's protected load
     * is in flight (claim → load → land), a GUI action must not lazily create a
     * wire-uuid scratch PlayerData — the landing guard refuses the real profile
     * once the slot is occupied, which would strand the session on scratch data.
     * After the real profile lands, the same actions work normally.
     */
    @org.junit.jupiter.api.Test
    void loadInFlightBlocksScratchLazyInitAndALandedProfileReenablesActions() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(store, () -> null);
        final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), new TokenStore(this.dataDir),
                store, new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                coordinator, new RecordingSwitchInitiator(), null, new AtomicInteger());
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            final PlayerSession session = new PlayerSession(UUID.randomUUID(), "InFlightPlayer");
            session.connectionId = UUID.randomUUID();
            //A proxied session (real c2p) with a VERIFIED identity but neither a
            //landed lease nor profile data = the protected load is in flight.
            final var c2p = new io.netty.channel.embedded.EmbeddedChannel();
            c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).set(
                    dev.connectplus.identity.ClientIdentity.verifiedJava(
                            session.uuid, "InFlightPlayer", session.uuid));
            session.c2pChannel = c2p;

            assertTrue(AccountFlow.protectedLoadInFlight(session), "the verified proxied empty session is mid-load");
            assertFalse(AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter()),
                    "the in-flight window must not count as lease-current");

            //1. Plain-login path: refused, no scratch profile is created.
            final var lobby = new io.netty.channel.embedded.EmbeddedChannel();
            session.lobbyChannel = lobby;
            final var screenHandler = newDeleteScreenHandler(handler, lobby);
            AccountFlow.startLogin(session, screenHandler, handler);
            assertNull(session.playerData, "A GUI login click during the load window must not create a scratch profile");
            assertFalse(session.loginInProgress, "The login must not even start");

            //2. saveNamed: refused before its lazy-init.
            session.serverAddress = "bm.example.net:25565";
            assertEquals(BookmarksScreen.SaveResult.REFUSED_STALE_SESSION,
                    BookmarksScreen.saveNamed(session, handler, "in-flight"),
                    "A bookmark save during the load window must be refused");
            assertNull(session.playerData, "The refused save must not create a scratch profile");

            //3. Offline toggle: refused before its lazy-init.
            final var toggleLobby = new io.netty.channel.embedded.EmbeddedChannel();
            session.lobbyChannel = toggleLobby;
            new MainScreen(dev.connectplus.lobby.screen.Lang.EN).toggleSaveLogin(session,
                    newDeleteScreenHandler(handler, toggleLobby));
            assertNull(session.playerData, "The save-login toggle during the load window must not create a scratch profile");

            //The load lands: lease + real profile occupy the session.
            final var landed = new PlayerData(ProfileKey.javaProfile(session.uuid));
            landed.saveLoginInfo = false;
            store.save(landed);
            final var lease = coordinator.claim(session, ProfileKey.javaProfile(session.uuid), java.util.Set.of())
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            session.lease = lease;
            session.generation = lease.generation();
            session.playerData = landed;
            assertTrue(AccountFlow.isLeaseCurrent(session, handler.getLeaseGranter()),
                    "the landed session is lease-current again");

            //The same GUI actions work normally now.
            assertEquals(BookmarksScreen.SaveResult.SAVED, BookmarksScreen.saveNamed(session, handler, "landed"),
                    "After the landing, a bookmark save works");
            assertEquals(1, store.load(ProfileKey.javaProfile(session.uuid)).bookmarks.size());
            final var afterLanding = store.load(ProfileKey.javaProfile(session.uuid));
            assertFalse(afterLanding.saveLoginInfo, "The toggle writes the LANDED profile, not a wire-uuid scratch one");
        } finally {
            coordinator.shutdown();
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    /**
     * Final fix, IMPORTANT 2 (scope): the in-flight refusal applies only to a
     * proxied VERIFIED session. An UNVERIFIED proxied session (offline players,
     * bridge down — no protected load) and a bare diagnostic connection keep the
     * legacy lazy-init.
     */
    @org.junit.jupiter.api.Test
    void unverifiedAndBareSessionsKeepTheLegacyLazyInit() throws Exception {
        ViaProxyTestConfig.init();
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), new TokenStore(this.dataDir),
                store, new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                new dev.connectplus.session.AccountSessionCoordinator(store, () -> null),
                new RecordingSwitchInitiator(), null, new AtomicInteger());
        //Proxied but UNVERIFIED: no protected load, lazy-init stays.
        final var unverified = new PlayerSession(UUID.randomUUID(), "OfflinePlayer");
        final var c2p = new io.netty.channel.embedded.EmbeddedChannel();
        c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).set(
                dev.connectplus.identity.ClientIdentity.unverified(unverified.uuid, "OfflinePlayer"));
        unverified.c2pChannel = c2p;
        assertFalse(AccountFlow.protectedLoadInFlight(unverified), "an unverified session has no protected load");
        assertTrue(AccountFlow.isLeaseCurrent(unverified, handler.getLeaseGranter()),
                "the unverified session keeps the legacy lazy-init behavior");
        //Bare diagnostic connection (no c2p): lazy-init stays.
        final var bare = new PlayerSession(UUID.randomUUID(), "DiagnosticPlayer");
        assertFalse(AccountFlow.protectedLoadInFlight(bare), "a bare session has no protected load");
        assertTrue(AccountFlow.isLeaseCurrent(bare, handler.getLeaseGranter()),
                "the bare session keeps the legacy lazy-init behavior");
    }
}
