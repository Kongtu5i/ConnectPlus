package dev.connectplus.lobby.screen.impl;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.ConnectFlow;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.lobby.states.StateHandler;
import dev.connectplus.session.AccountSessionCoordinator;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import dev.connectplus.testutil.StubAccount;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viaproxy.ViaProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BedrockAccountPolicyTest {
    static {
        if (com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.CUSTOM_NAME == null) {
            throw new IllegalStateException("Structured data keys not initialized");
        }
    }
    @TempDir File dataDir;
    private boolean oldProxyOnline, oldLogin, oldGeyser;

    @BeforeEach
    void savePolicy() {
        ViaProxyTestConfig.init();
        this.oldProxyOnline = ViaProxy.getConfig().isProxyOnlineMode();
        this.oldLogin = CPConfig.allowAccountLogin;
        this.oldGeyser = CPConfig.GeyserSupport.enabled;
    }

    @AfterEach
    void restorePolicy() {
        ViaProxy.getConfig().setProxyOnlineMode(this.oldProxyOnline);
        CPConfig.allowAccountLogin = this.oldLogin;
        CPConfig.GeyserSupport.enabled = this.oldGeyser;
    }

    @ParameterizedTest
    @CsvSource({
            "BEDROCK,false,true,true,true",
            "BEDROCK,true,true,true,true",
            "BEDROCK,false,false,true,false",
            "BEDROCK,false,true,false,false",
            "BEDROCK,true,true,false,false",
            "JAVA,true,true,false,true",
            "JAVA,false,true,true,false",
            "UNVERIFIED,true,true,true,false",
            "NONE,true,true,true,false"
    })
    void loginCompletionAndBackendHandoffUseTheAuthenticatedConnection(
            String kind, boolean online, boolean login, boolean geyser, boolean expected) throws Exception {
        ViaProxy.getConfig().setProxyOnlineMode(online);
        CPConfig.allowAccountLogin = login;
        CPConfig.GeyserSupport.enabled = geyser;
        try (Rig rig = new Rig(kind, false)) {
            StubAccount account = new StubAccount();
            assertEquals(expected, AccountFlow.installAccount(rig.session, account, rig.handler));
            if (expected) {
                assertSame(account, rig.session.account);
                assertEquals(account.toJson(), rig.tokens.decrypt(rig.store.load(rig.session.profileKey).accountBlob));
            } else {
                assertNull(rig.session.account);
                assertEquals("existing-encrypted-account", rig.store.load(rig.session.profileKey).accountBlob);
            }
            // An existing in-memory account must pass the same gate at handoff.
            rig.session.account = account;
            ConnectFlow.start(rig.session, rig.state, rig.initiator);
            assertEquals(expected ? account : null, rig.initiator.requests().get(0).account());
        }
    }

    @Test
    void bedrockLoginCompletionRechecksTheAdminDisableAfterEncryption() throws Exception {
        ViaProxy.getConfig().setProxyOnlineMode(false);
        CPConfig.allowAccountLogin = true;
        CPConfig.GeyserSupport.enabled = true;
        try (Rig rig = new Rig("BEDROCK", true)) {
            assertFalse(AccountFlow.installAccount(rig.session, new StubAccount(), rig.handler));
            assertEquals(1, rig.encrypts.get(), "The trusted login must reach encryption before the admin disables it");
            assertNull(rig.session.account);
            assertEquals("existing-encrypted-account", rig.store.load(rig.session.profileKey).accountBlob);
        }
    }

    @Test
    void offlineConnectionPreservesTheBedrockLoginButOmitsItsCredentials() throws Exception {
        ViaProxy.getConfig().setProxyOnlineMode(false);
        CPConfig.allowAccountLogin = true;
        CPConfig.GeyserSupport.enabled = true;
        try (Rig rig = new Rig("BEDROCK", false)) {
            StubAccount account = new StubAccount();
            assertTrue(AccountFlow.installAccount(rig.session, account, rig.handler));
            rig.session.playerData.offlineMode = true;
            ConnectFlow.start(rig.session, rig.state, rig.initiator);
            assertNull(rig.initiator.requests().get(0).account());
            assertSame(account, rig.session.account);
            rig.session.playerData.offlineMode = false;
            ConnectFlow.start(rig.session, rig.state, rig.initiator);
            assertSame(account, rig.initiator.requests().get(1).account());
            AccountFlow.logout(rig.session, new ScreenHandler(rig.state), rig.handler);
            assertNull(rig.session.account);
            assertNull(rig.store.load(rig.session.profileKey).accountBlob);
        }
    }

    @Test
    void stoppedProviderCannotReusePreviouslyVerifiedCredentials() throws Exception {
        ViaProxy.getConfig().setProxyOnlineMode(false);
        CPConfig.allowAccountLogin = true;
        CPConfig.GeyserSupport.enabled = true;
        try (Rig rig = new Rig("BEDROCK", false)) {
            var account = new StubAccount();
            assertTrue(AccountFlow.installAccount(rig.session, account, rig.handler));
            rig.provider.stop();
            assertFalse(AccountFlow.installAccount(rig.session, account, rig.handler));
            ConnectFlow.start(rig.session, rig.state, rig.initiator);
            assertNull(rig.initiator.requests().get(0).account());
        }
    }

    @Test
    void enablingSaveLoginRechecksPermissionAfterEncryption() throws Exception {
        ViaProxy.getConfig().setProxyOnlineMode(false);
        CPConfig.allowAccountLogin = true;
        CPConfig.GeyserSupport.enabled = true;
        try (Rig rig = new Rig("BEDROCK", true)) {
            rig.session.playerData.saveLoginInfo = false;
            rig.session.playerData.accountBlob = null;
            rig.session.account = new StubAccount();
            rig.store.save(rig.session.playerData);
            new MainScreen(dev.connectplus.lobby.screen.Lang.EN).toggleSaveLogin(rig.session, new ScreenHandler(rig.state));
            assertEquals(1, rig.encrypts.get());
            assertFalse(rig.session.playerData.saveLoginInfo);
            assertNull(rig.store.load(rig.session.profileKey).accountBlob);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SESSION_CLOSED", "COPIED_CHANNEL", "CHANGED_CONNECTION", "MISSING_PROVIDER"})
    void aBridgeProofMustStillBelongToThisLiveConnection(String invalidation) throws Exception {
        ViaProxy.getConfig().setProxyOnlineMode(false);
        CPConfig.allowAccountLogin = true;
        CPConfig.GeyserSupport.enabled = true;
        EmbeddedChannel other = new EmbeddedChannel();
        try (Rig rig = new Rig("BEDROCK", false)) {
            ClientIdentity identity = rig.client.attr(CPAttributeKeys.CLIENT_IDENTITY).get();
            assertTrue(AccountFlow.installAccount(rig.session, new StubAccount(), rig.handler));
            switch (invalidation) {
                case "SESSION_CLOSED" -> rig.provider.endpoint().handleEvent(rig.provider.registrationId(), java.util.Map.of(
                        "protocolVersion", 1, "providerEpoch", identity.providerEpoch().toString(), "op", "SESSION_CLOSED",
                        "connectionId", rig.client.attr(CPAttributeKeys.CONNECTION_ID).get().toString(),
                        "bridgeSessionId", identity.bridgeSessionId())).toCompletableFuture().join();
                case "COPIED_CHANNEL" -> {
                    other.attr(CPAttributeKeys.CLIENT_IDENTITY).set(identity);
                    other.attr(CPAttributeKeys.BEDROCK_BRIDGE_ENDPOINT).set(rig.provider.endpoint());
                    other.attr(CPAttributeKeys.CONNECTION_ID).set(rig.client.attr(CPAttributeKeys.CONNECTION_ID).get());
                    rig.session.c2pChannel = other;
                }
                case "CHANGED_CONNECTION" -> rig.client.attr(CPAttributeKeys.CONNECTION_ID).set(UUID.randomUUID());
                case "MISSING_PROVIDER" -> rig.client.attr(CPAttributeKeys.BEDROCK_BRIDGE_ENDPOINT).set(null);
            }
            assertFalse(AccountFlow.installAccount(rig.session, new StubAccount(), rig.handler));
            ConnectFlow.start(rig.session, rig.state, rig.initiator);
            assertNull(rig.initiator.requests().get(0).account());
        } finally {
            other.finishAndReleaseAll();
        }
    }

    @Test
    void disabledAccountPolicyCannotEraseStoredLoginByChangingTheSaveToggle() throws Exception {
        ViaProxy.getConfig().setProxyOnlineMode(false);
        CPConfig.allowAccountLogin = true;
        CPConfig.GeyserSupport.enabled = true;
        try (Rig rig = new Rig("BEDROCK", false)) {
            CPConfig.allowAccountLogin = false;
            new MainScreen(dev.connectplus.lobby.screen.Lang.EN).toggleSaveLogin(rig.session, new ScreenHandler(rig.state));
            assertTrue(rig.session.playerData.saveLoginInfo);
            assertEquals("existing-encrypted-account", rig.store.load(rig.session.profileKey).accountBlob);
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false,true,true", "false,false,true,false", "true,true,true,false", "true,false,false,false"})
    void transferConfirmationCarriesTheConnectionIdAndChecksAccountPermission(
            boolean loginAllowed, boolean offline, boolean currentLease, boolean expectedAccount) throws Exception {
        ViaProxy.getConfig().setProxyOnlineMode(false);
        CPConfig.allowAccountLogin = true;
        CPConfig.GeyserSupport.enabled = true;
        try (Rig rig = new Rig("BEDROCK", false)) {
            var account = new StubAccount();
            assertTrue(AccountFlow.installAccount(rig.session, account, rig.handler));
            CPConfig.allowAccountLogin = loginAllowed;
            rig.session.playerData.offlineMode = offline;
            if (!currentLease) rig.coordinator.release(rig.session.lease).toCompletableFuture().get(3, TimeUnit.SECONDS);
            var screenHandler = new ScreenHandler(rig.state);
            var items = new dev.connectplus.lobby.screen.ItemList(27);
            new TransferConfirmScreen(dev.connectplus.lobby.screen.Lang.EN, "backend.example.net", 25565)
                    .init(screenHandler, items);
            items.getListeners()[11].onClick();
            var request = rig.initiator.requests().get(0);
            assertEquals(rig.session.connectionId, request.target().connectionId());
            assertEquals(expectedAccount ? account : null, request.account());
        }
    }

    private final class Rig implements AutoCloseable {
        final EmbeddedChannel client = new EmbeddedChannel();
        final EmbeddedChannel lobby = new EmbeddedChannel();
        final AtomicInteger encrypts = new AtomicInteger();
        final PlayerStore store = new PlayerStore(new File(dataDir, "players"));
        final AccountSessionCoordinator coordinator = new AccountSessionCoordinator(this.store, () -> null);
        final RecordingSwitchInitiator initiator = new RecordingSwitchInitiator();
        final PlayerSession session = new PlayerSession(UUID.randomUUID(), "PolicyPlayer");
        final TokenStore tokens;
        final LobbyServerHandler handler;
        final StateHandler state;
        final dev.connectplus.testutil.TestClientIdentity.BedrockProvider provider;

        Rig(String kind, boolean disableDuringEncryption) throws Exception {
            ClientIdentity identity = switch (kind) {
                case "JAVA" -> ClientIdentity.verifiedJava(this.session.uuid, this.session.name, this.session.uuid);
                case "BEDROCK" -> ClientIdentity.verifiedBedrock(this.session.uuid, this.session.name,
                        "4242424242424", UUID.randomUUID(), UUID.randomUUID().toString());
                case "UNVERIFIED" -> ClientIdentity.unverified(this.session.uuid, this.session.name);
                default -> null;
            };
            this.client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(identity);
            this.provider = "BEDROCK".equals(kind)
                    ? dev.connectplus.testutil.TestClientIdentity.bedrock(this.client, identity) : null;
            this.session.c2pChannel = this.client;
            this.session.lobbyChannel = this.lobby;
            this.session.connectionId = UUID.randomUUID();
            this.session.profileKey = "BEDROCK".equals(kind)
                    ? ProfileKey.bedrockProfile("4242424242424") : ProfileKey.javaProfile(this.session.uuid);
            this.session.playerData = new PlayerData(this.session.profileKey);
            this.session.playerData.accountBlob = "existing-encrypted-account";
            this.session.serverAddress = "backend.example.net:25565";
            this.store.save(this.session.playerData);
            this.session.lease = this.coordinator.claim(this.session, this.session.profileKey, Set.of())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            this.tokens = new TokenStore(dataDir) {
                @Override public String encrypt(String json) {
                    encrypts.incrementAndGet();
                    if (disableDuringEncryption) CPConfig.allowAccountLogin = false;
                    return super.encrypt(json);
                }
            };
            this.handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), this.tokens, this.store,
                    new IdentityLinkStore(this.store.playersDir()), this.coordinator, this.initiator,
                    null, new AtomicInteger()) {
                @Override public PlayerSession getSession() { return session; }
            };
            this.state = new StateHandler(this.handler, this.lobby) { };
        }

        @Override public void close() {
            this.client.finishAndReleaseAll();
            this.lobby.finishAndReleaseAll();
            this.coordinator.shutdown();
        }
    }
}
