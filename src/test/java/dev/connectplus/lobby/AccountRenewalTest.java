package dev.connectplus.lobby;

import dev.connectplus.accounts.CPAccount;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.session.AccountSessionCoordinator;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import dev.connectplus.testutil.TestClientIdentity;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viaproxy.ViaProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(dev.connectplus.testutil.AccountPolicyExtension.class)
class AccountRenewalTest {
    @TempDir File directory;

    @Test
    void expiredLiveAccountRefreshesOffTheEventLoopAndPersists() throws Exception {
        try (var rig = new Rig(true)) {
            rig.account.expired = true;
            rig.start();
            rig.settle();
            assertEquals(1, rig.account.refreshes);
            assertNotSame(Thread.currentThread(), rig.account.refreshThread);
            assertFalse(rig.account.isExpired());
            assertSame(rig.account, rig.handler.getSession().account);
            assertEquals(rig.account.toJson(), rig.persistedJson());
        }
    }

    @Test
    void saveLoginOffStillRefreshesWithoutPersistingCredentials() throws Exception {
        try (var rig = new Rig(false)) {
            rig.account.expired = true;
            rig.start();
            rig.settle();
            assertEquals(1, rig.account.refreshes);
            assertFalse(rig.account.isExpired());
            assertNull(rig.store.load(rig.handler.getSession().playerData.key).accountBlob);
        }
    }

    @Test
    void temporaryRefreshFailureKeepsTheStoredCredentials() throws Exception {
        try (var rig = new Rig(true)) {
            final String previous = rig.handler.getSession().playerData.accountBlob;
            rig.account.expired = true;
            rig.account.failure = new IOException("synthetic timeout");
            rig.start();
            rig.settle();
            assertEquals(1, rig.account.refreshes);
            assertSame(rig.account, rig.handler.getSession().account);
            assertEquals(previous, rig.store.load(rig.handler.getSession().playerData.key).accountBlob);
        }
    }

    @Test
    void connectWaitsForBackgroundRenewalBeforeHandingOffCredentials() throws Exception {
        try (var rig = new Rig(true)) {
            rig.account.expired = true;
            rig.handler.getSession().serverAddress = "example.net";
            final var switches = new RecordingSwitchInitiator();
            final var state = new dev.connectplus.lobby.states.StateHandler(rig.handler, rig.channel) { };
            assertEquals(dev.connectplus.switching.SwitchInitiator.StartResult.REJECTED_BUSY,
                    ConnectFlow.start(rig.handler.getSession(), state, switches));
            assertTrue(switches.requests().isEmpty());
            rig.settle();
            assertFalse(rig.account.isExpired());
            assertEquals(dev.connectplus.switching.SwitchInitiator.StartResult.STARTED,
                    ConnectFlow.start(rig.handler.getSession(), state, switches));
            assertSame(rig.account, switches.requests().get(0).account());
        }
    }

    @Test
    void accountButtonRenewsExistingLoginWithoutStartingDeviceCodeAuthorization() throws Exception {
        final var previousAllowlist = CPConfig.accountLoginAllowlist;
        try (var rig = new Rig(true)) {
            //Block NEW device-code logins so the old implementation cannot contact Microsoft.
            CPConfig.accountLoginAllowlist = java.util.List.of("OtherPlayer");
            rig.account.expired = true;
            final var state = new dev.connectplus.lobby.states.StateHandler(rig.handler, rig.channel) { };
            final var screens = new dev.connectplus.lobby.screen.ScreenHandler(state);
            final var items = new dev.connectplus.lobby.screen.ItemList(36);
            new dev.connectplus.lobby.screen.impl.MainScreen(dev.connectplus.lobby.screen.Lang.EN).init(screens, items);
            items.getListeners()[13].onClick();
            rig.settle();
            assertEquals(1, rig.account.refreshes);
            assertFalse(rig.handler.getSession().loginInProgress);
            assertSame(rig.account, rig.handler.getSession().account);
        } finally {
            CPConfig.accountLoginAllowlist = previousAllowlist;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "save-off", "displaced", "disabled", "closed", "replaced"})
    void lateRefreshCannotOverwriteNewSessionState(final String change) throws Exception {
        try (var rig = new Rig(true)) {
            rig.account.expired = true;
            rig.account.duringRefresh = () -> rig.change(change);
            rig.start();
            rig.settle();
            assertEquals(1, rig.account.refreshes);
            assertEquals(rig.expectedBlob, rig.store.load(rig.dataKey).accountBlob);
        }
    }

    @Test
    void hostTokenChangesPersistEvenAfterLeavingTheLobby() throws Exception {
        try (var rig = new Rig(true)) {
            rig.start();
            rig.settle();
            rig.handler.getSession().lobbyChannel = new EmbeddedChannel();
            try {
                rig.account.json = "{\"token\":\"rotated-by-host\"}";
                rig.account.changed();
                rig.settle();
                assertEquals(rig.account.toJson(), rig.persistedJson());
            } finally {
                rig.handler.getSession().lobbyChannel.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "save-off", "displaced", "disabled", "closed", "replaced"})
    void lateHostTokenChangeCannotResurrectCredentials(final String change) throws Exception {
        try (var rig = new Rig(true)) {
            rig.start();
            rig.settle();
            rig.change(change);
            rig.account.json = "{\"token\":\"late-update\"}";
            rig.account.changed();
            rig.settle();
            assertEquals(rig.expectedBlob, rig.store.load(rig.dataKey).accountBlob);
        }
    }

    @Test
    void hostTokenObserverRebindsWhenSameAccountAcquiresANewLease() throws Exception {
        try (var rig = new Rig(true)) {
            rig.start();
            rig.settle();
            final var session = rig.handler.getSession();
            session.lease = rig.coordinator.claim(session, session.profileKey, Set.of())
                    .toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);
            session.generation = session.lease.generation();
            rig.start();
            rig.account.json = "{\"token\":\"after-lease-change\"}";
            rig.account.changed();
            rig.settle();
            assertEquals(rig.account.toJson(), rig.persistedJson());
        }
    }

    @Test
    void guiBookmarkSaveAndHostTokenRenewalPreserveBothChanges() throws Exception {
        try (var rig = new Rig(true)) {
            rig.start();
            rig.settle();
            rig.handler.getSession().serverAddress = "bookmark.example.net";
            rig.pauseSave.set(true);
            final var bookmarkSave = java.util.concurrent.CompletableFuture.runAsync(() ->
                    dev.connectplus.lobby.screen.impl.BookmarksScreen.saveNamed(rig.handler.getSession(), rig.handler, "kept"));
            assertTrue(rig.saveEntered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            rig.account.json = "{\"token\":\"renewed-during-bookmark-save\"}";
            rig.account.changed();
            try {
                rig.storage.submit(() -> { }).get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (final java.util.concurrent.TimeoutException expectedWhileGuiOwnsSession) {
                //Credential persistence must wait until the earlier GUI save finishes.
            } finally {
                rig.saveContinue.countDown();
            }
            bookmarkSave.get(3, java.util.concurrent.TimeUnit.SECONDS);
            rig.settle();
            assertEquals(rig.account.toJson(), rig.persistedJson());
            final var saved = rig.store.load(rig.dataKey);
            assertEquals(1, saved.bookmarks.size());
            assertEquals("kept", saved.bookmarks.get(0).name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "save-off", "delete"})
    void userCredentialRemovalWinsOverAnAlreadyWritingTokenSave(final String action) throws Exception {
        try (var rig = new Rig(true)) {
            rig.start();
            rig.settle();
            rig.pauseSave.set(true);
            rig.account.json = "{\"token\":\"save-in-flight\"}";
            rig.account.changed();
            assertTrue(rig.saveEntered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            final var state = new dev.connectplus.lobby.states.StateHandler(rig.handler, rig.channel) { };
            final var screens = new dev.connectplus.lobby.screen.ScreenHandler(state);
            final var removal = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    if (action.equals("logout")) {
                        dev.connectplus.lobby.screen.impl.AccountFlow.logout(rig.handler.getSession(), screens, rig.handler);
                    } else {
                        final Class<?> type = action.equals("delete")
                                ? dev.connectplus.lobby.screen.impl.DeleteAllConfirmScreen.class
                                : dev.connectplus.lobby.screen.impl.MainScreen.class;
                        final Object screen = type.getConstructor(dev.connectplus.lobby.screen.Lang.class)
                                .newInstance(dev.connectplus.lobby.screen.Lang.EN);
                        final var method = type.getDeclaredMethod(action.equals("delete") ? "deleteAll" : "toggleSaveLogin",
                                dev.connectplus.session.PlayerSession.class, dev.connectplus.lobby.screen.ScreenHandler.class);
                        method.setAccessible(true);
                        method.invoke(screen, rig.handler.getSession(), screens);
                    }
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                removal.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (final java.util.concurrent.TimeoutException expectedWhileSaveOwnsSession) {
                //The removal must serialize after the already accepted disk write.
            } finally {
                rig.saveContinue.countDown();
            }
            removal.get(3, java.util.concurrent.TimeUnit.SECONDS);
            rig.settle();
            assertNull(rig.store.load(rig.dataKey).accountBlob, "An older token write must not undo " + action);
        }
    }

    private final class Rig implements AutoCloseable {
        final TokenStore tokens = new TokenStore(directory);
        final java.util.concurrent.atomic.AtomicBoolean pauseSave = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.CountDownLatch saveEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch saveContinue = new java.util.concurrent.CountDownLatch(1);
        final PlayerStore store = new PlayerStore(new File(directory, "players")) {
            @Override public void save(final dev.connectplus.identity.ProfileKey key,
                                       final dev.connectplus.session.PlayerData data) throws IOException {
                if (pauseSave.compareAndSet(true, false)) {
                    //Model a file whose old credential bytes were serialized before its atomic move.
                    final var snapshot = new dev.connectplus.session.PlayerData(key);
                    snapshot.accountBlob = data.accountBlob;
                    snapshot.saveLoginInfo = data.saveLoginInfo;
                    snapshot.offlineMode = data.offlineMode;
                    snapshot.bookmarks.addAll(data.bookmarks);
                    saveEntered.countDown();
                    try {
                        if (!saveContinue.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("save timeout");
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    super.save(key, snapshot);
                } else {
                    super.save(key, data);
                }
            }
        };
        final AccountSessionCoordinator coordinator = new AccountSessionCoordinator(store, () -> null);
        final java.util.concurrent.ExecutorService storage = Executors.newSingleThreadExecutor();
        final EmbeddedChannel channel = new EmbeddedChannel();
        final LobbyServerHandler handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens,
                store, new IdentityLinkStore(store.playersDir()), coordinator, new RecordingSwitchInitiator(),
                storage, new AtomicInteger());
        final MutableAccount account = new MutableAccount();
        final dev.connectplus.identity.ProfileKey dataKey;
        String expectedBlob;

        Rig(final boolean save) throws Exception {
            ViaProxyTestConfig.init();
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            TestClientIdentity.linkJava(channel, UUID.randomUUID(), "RenewPlayer");
            handler.loadSession(channel, channel.attr(dev.connectplus.compat.CPAttributeKeys.CONNECTION_ID).get(), "RenewPlayer");
            settle();
            final var session = handler.getSession();
            session.account = account;
            session.playerData.saveLoginInfo = save;
            session.playerData.accountBlob = save ? tokens.encrypt(account.toJson()) : null;
            dataKey = session.playerData.key;
            store.save(session.playerData);
            expectedBlob = session.playerData.accountBlob;
        }

        void start() throws Exception {
            final var method = LobbyServerHandler.class.getDeclaredMethod("setupAccountRestore", Channel.class,
                    dev.connectplus.session.PlayerSession.class, dev.connectplus.session.PlayerData.class);
            method.setAccessible(true);
            method.invoke(handler, channel, handler.getSession(), handler.getSession().playerData);
        }

        void settle() throws Exception { TestClientIdentity.settle(handler, storage, channel); }

        String persistedJson() throws Exception { return tokens.decrypt(store.load(dataKey).accountBlob); }

        void change(final String change) {
            final var session = handler.getSession();
            switch (change) {
                case "logout" -> { session.account = null; session.playerData.accountBlob = null; }
                case "save-off" -> { session.playerData.saveLoginInfo = false; session.playerData.accountBlob = null; }
                case "displaced" -> session.displaced = true;
                case "disabled" -> CPConfig.allowAccountLogin = false;
                case "closed" -> channel.close();
                case "replaced" -> { session.account = new MutableAccount(); session.playerData.accountBlob = "new-login"; }
                default -> throw new AssertionError(change);
            }
            store.save(session.playerData);
            expectedBlob = session.playerData.accountBlob;
        }

        @Override public void close() {
            saveContinue.countDown();
            channel.finishAndReleaseAll();
            storage.shutdownNow();
            coordinator.shutdown();
        }
    }

    private static final class MutableAccount implements CPAccount {
        boolean expired;
        int refreshes;
        Thread refreshThread;
        Exception failure;
        Runnable duringRefresh = () -> { };
        Runnable listener;
        String json = "{\"token\":\"old\"}";
        @Override public String displayName() { return "SyntheticPlayer"; }
        @Override public UUID uuid() { return new UUID(1, 2); }
        @Override public boolean isExpired() { return expired; }
        @Override public String toJson() { return json; }
        @Override public void refresh() throws Exception {
            refreshes++;
            refreshThread = Thread.currentThread();
            duringRefresh.run();
            if (failure != null) throw failure;
            expired = false;
            json = "{\"token\":\"renewed\"}";
            changed();
        }
        @Override public Runnable onChange(final Runnable listener) {
            this.listener = listener;
            return () -> this.listener = null;
        }
        void changed() { if (listener != null) listener.run(); }
    }
}
