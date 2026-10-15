package dev.connectplus.lobby.screen.impl;

import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import dev.connectplus.accounts.CPAccount;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.lobby.states.StateHandler;
import dev.connectplus.session.*;
import dev.connectplus.testutil.AccountPolicyExtension;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import dev.connectplus.testutil.StubAccount;
import dev.connectplus.testutil.TestClientIdentity;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(AccountPolicyExtension.class)
class AccountLoginConfirmTest {
    @TempDir File directory;

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock"})
    void clickingAnExistingLoginOpensConfirmationWithoutRemovingCredentials(String edition) throws Exception {
        try (var rig = new Rig(edition)) {
            rig.click(13);
            assertSame(rig.account, rig.session.account);
            assertEquals(rig.savedBlob, rig.persistedBlob());
            assertEquals("AccountLoginConfirmScreen", rig.screens.getCurrentScreen().getClass().getSimpleName());
            assertFalse(rig.session.loginInProgress);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock"})
    void backReturnsToMainAndPreservesTheExistingLogin(String edition) throws Exception {
        try (var rig = new Rig(edition)) {
            rig.click(13);
            rig.click(15);
            assertInstanceOf(MainScreen.class, rig.screens.getCurrentScreen());
            assertSame(rig.account, rig.session.account);
            assertEquals(rig.savedBlob, rig.persistedBlob());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock"})
    void closingConfirmationDoesNotLogOut(String edition) throws Exception {
        try (var rig = new Rig(edition)) {
            rig.click(13);
            rig.screens.handle(new C2SContainerClosePacket(rig.screens.getCurrentWindowId()));
            assertSame(rig.account, rig.session.account);
            assertEquals(rig.savedBlob, rig.persistedBlob());
            if (edition.equals("java")) assertInstanceOf(MainScreen.class, rig.screens.getCurrentScreen());
            else assertNull(rig.screens.getCurrentScreen(), "Bedrock close exits the entire GUI");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock"})
    void duplicateOldMainClickCannotConfirmAccountRemoval(String edition) throws Exception {
        try (var rig = new Rig(edition)) {
            int oldWindow = rig.screens.getCurrentWindowId();
            rig.click(13);
            rig.click(oldWindow, 11);
            rig.click(oldWindow, 13);
            assertSame(rig.account, rig.session.account);
            assertEquals(rig.savedBlob, rig.persistedBlob());
            assertEquals("AccountLoginConfirmScreen", rig.screens.getCurrentScreen().getClass().getSimpleName());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock"})
    void confirmClearsOnlyTheAccountThenStartsExactlyOneNewLogin(String edition) throws Exception {
        try (var rig = new Rig(edition)) {
            rig.session.playerData.bookmarks.add(new Bookmark("kept", "example.net", null, 1, 0));
            rig.store.save(rig.session.playerData);
            final var starts = new AtomicInteger();
            rig.screens.openScreen(new AccountLoginConfirmScreen(Lang.ZH, rig.session, screens -> {
                assertNull(rig.session.account);
                assertNull(rig.session.playerData.accountBlob);
                try { assertNull(rig.persistedBlob(), "Clear the previous saved login before new authorization"); }
                catch (Exception e) { throw new AssertionError(e); }
                rig.session.loginInProgress = true;
                starts.incrementAndGet();
                screens.closeScreen();
            }));
            int confirmationWindow = rig.screens.getCurrentWindowId();
            rig.click(11);
            rig.click(confirmationWindow, 11);
            assertEquals(1, starts.get());
            assertNull(rig.session.account);
            assertNull(rig.persistedBlob());
            assertEquals(1, rig.store.load(rig.session.profileKey).bookmarks.size());
            assertTrue(rig.session.playerData.saveLoginInfo);
            assertNull(rig.screens.getCurrentScreen());
        }
    }

    @ParameterizedTest
    @CsvSource({"java,replaced", "bedrock,replaced", "java,profile", "bedrock,lease", "java,displaced",
            "bedrock,disabled", "java,allowlist", "bedrock,busy", "java,owner", "java,closed"})
    void staleOrUnauthorizedConfirmationPreservesCredentials(String edition, String change) throws Exception {
        final var previousAllowlist = dev.connectplus.config.CPConfig.accountLoginAllowlist;
        try (var rig = new Rig(edition)) {
            final var starts = new AtomicInteger();
            final var confirmation = new AccountLoginConfirmScreen(Lang.EN, rig.session, screens -> starts.incrementAndGet());
            rig.screens.openScreen(confirmation);
            final var confirmationItems = new dev.connectplus.lobby.screen.ItemList(27);
            confirmation.init(rig.screens, confirmationItems);
            final var originalData = rig.session.playerData;
            switch (change) {
                case "replaced" -> rig.session.account = new StubAccount();
                case "profile" -> rig.session.playerData = new PlayerData(rig.session.profileKey);
                case "lease" -> rig.session.lease = rig.coordinator.claim(rig.session, rig.session.profileKey, Set.of())
                        .toCompletableFuture().get(3, TimeUnit.SECONDS);
                case "displaced" -> rig.session.displaced = true;
                case "disabled" -> dev.connectplus.config.CPConfig.allowAccountLogin = false;
                case "allowlist" -> dev.connectplus.config.CPConfig.accountLoginAllowlist = java.util.List.of("OtherPlayer");
                case "busy" -> rig.session.loginInProgress = true;
                case "owner" -> rig.session.lobbyChannel = rig.client;
                case "closed" -> rig.lobby.close();
                default -> throw new AssertionError(change);
            }
            final var expectedAccount = rig.session.account;
            if (change.equals("closed")) confirmationItems.getListeners()[11].onClick();
            else rig.click(11);
            assertSame(expectedAccount, rig.session.account);
            assertEquals(rig.savedBlob, originalData.accountBlob);
            assertEquals(rig.savedBlob, rig.persistedBlob());
            assertEquals(0, starts.get());
        } finally {
            dev.connectplus.config.CPConfig.accountLoginAllowlist = previousAllowlist;
        }
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock"})
    void signedOutPlayerStillUsesTheExistingLoginPath(String edition) throws Exception {
        final var previousAllowlist = dev.connectplus.config.CPConfig.accountLoginAllowlist;
        try (var rig = new Rig(edition)) {
            rig.session.account = null;
            rig.session.playerData.accountBlob = null;
            //Keep this network-free: an allowlist denial proves the normal login branch was reached.
            dev.connectplus.config.CPConfig.accountLoginAllowlist = java.util.List.of("OtherPlayer");
            rig.screens.openScreen(new MainScreen(Lang.ZH));
            rig.lobby.outboundMessages().clear();
            rig.click(13);
            assertInstanceOf(MainScreen.class, rig.screens.getCurrentScreen());
            assertNull(rig.session.account);
            assertFalse(rig.session.loginInProgress);
            assertTrue(rig.lobby.outboundMessages().stream()
                    .anyMatch(dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket.class::isInstance));
        } finally {
            dev.connectplus.config.CPConfig.accountLoginAllowlist = previousAllowlist;
        }
    }

    @ParameterizedTest @ValueSource(strings = {"java", "bedrock"})
    void failedCredentialRemovalPreservesLoginAndCanBeRetried(String edition) throws Exception {
        try (var rig = new Rig(edition)) {
            final var starts = new AtomicInteger();
            final var confirmation = new AccountLoginConfirmScreen(Lang.EN, rig.session, screens -> {
                starts.incrementAndGet();
                screens.closeScreen();
            });
            rig.screens.openScreen(confirmation);
            rig.failNextSave.set(true);
            rig.click(11);
            assertSame(rig.account, rig.session.account);
            assertEquals(rig.savedBlob, rig.session.playerData.accountBlob);
            assertEquals(rig.savedBlob, rig.persistedBlob());
            assertSame(confirmation, rig.screens.getCurrentScreen());
            assertEquals(0, starts.get());
            rig.click(11);
            assertEquals(1, starts.get());
            assertNull(rig.session.account);
            assertNull(rig.persistedBlob());
        }
    }

    private final class Rig implements AutoCloseable {
        final EmbeddedChannel lobby = new EmbeddedChannel();
        final EmbeddedChannel client = new EmbeddedChannel();
        final java.util.concurrent.atomic.AtomicBoolean failNextSave = new java.util.concurrent.atomic.AtomicBoolean();
        final PlayerStore store = new PlayerStore(new File(directory, "players")) {
            @Override public void save(ProfileKey key, PlayerData data) throws java.io.IOException {
                if (failNextSave.compareAndSet(true, false)) throw new java.io.IOException("synthetic disk failure");
                super.save(key, data);
            }
        };
        final TokenStore tokens = new TokenStore(directory);
        final AccountSessionCoordinator coordinator = new AccountSessionCoordinator(store, () -> null);
        final CPAccount account = new StubAccount();
        final PlayerSession session = new PlayerSession(UUID.randomUUID(), "ConfirmPlayer");
        final LobbyServerHandler handler;
        final ScreenHandler screens;
        final String savedBlob;

        Rig(String edition) throws Exception {
            session.c2pChannel = client;
            session.lobbyChannel = lobby;
            session.connectionId = UUID.randomUUID();
            if (edition.equals("bedrock")) {
                TestClientIdentity.bedrock(client, ClientIdentity.verifiedBedrock(session.uuid, session.name,
                        "4242424242424", UUID.randomUUID(), UUID.randomUUID().toString()));
            } else {
                TestClientIdentity.javaIdentity(session, client);
            }
            session.profileKey = ProfileKey.javaProfile(session.uuid);
            session.playerData = new PlayerData(session.profileKey);
            session.account = account;
            session.playerData.accountBlob = savedBlob = tokens.encrypt(account.toJson());
            store.save(session.playerData);
            session.lease = coordinator.claim(session, session.profileKey, Set.of())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                    new IdentityLinkStore(store.playersDir()), coordinator, new RecordingSwitchInitiator(),
                    null, new AtomicInteger()) {
                @Override public PlayerSession getSession() { return session; }
            };
            screens = new ScreenHandler(new StateHandler(handler, lobby));
            screens.openScreen(new MainScreen(Lang.ZH));
        }

        void click(int slot) { click(screens.getCurrentWindowId(), slot); }
        void click(int window, int slot) {
            screens.handle(new C2SContainerClickPacket(window, 0, slot, 0, 0, Map.of(), StructuredItem.empty()));
        }
        String persistedBlob() throws Exception { return store.load(session.profileKey).accountBlob; }
        @Override public void close() {
            lobby.finishAndReleaseAll();
            client.finishAndReleaseAll();
            coordinator.shutdown();
        }
    }
}
