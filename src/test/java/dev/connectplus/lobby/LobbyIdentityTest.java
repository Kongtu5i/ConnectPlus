package dev.connectplus.lobby;

import com.mojang.authlib.GameProfile;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.CpAccounts;
import dev.connectplus.compat.LobbyHaProxy;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.config.CPConfig;
import dev.connectplus.lobby.states.LoginStateHandler;
import dev.connectplus.session.Bookmark;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.events.ClientLoggedInEvent;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LobbyIdentityTest {
    static {
        if (com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.CUSTOM_NAME == null) {
            throw new IllegalStateException("Structured data keys not initialized");
        }
    }
    private static final UUID OWNER = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    @TempDir File dataDir;

    @org.junit.jupiter.api.Test
    void returningFromOfflineBackendKeepsSessionOnlyLoginUntilClientDisconnects() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldOnline = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final var client = new EmbeddedChannel();
        final var firstLobby = new EmbeddedChannel();
        final var returnLobby = new EmbeddedChannel();
        final var executor = Executors.newSingleThreadExecutor();
        final UUID link = UUID.randomUUID();
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            final var proxy = new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
            client.attr(CPAttributeKeys.PLAYER_IDENTITY).set(new dev.connectplus.session.PlayerIdentity(OWNER, "AccountPlayer"));
            client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(dev.connectplus.identity.ClientIdentity.verifiedJava(OWNER, "AccountPlayer", OWNER));
            LobbyLink.register(link, proxy);
            firstLobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(link);
            returnLobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(link);
            final TokenStore tokens = new TokenStore(this.dataDir);
            final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
            final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                    new IdentityLinkStore(store.playersDir()), new dev.connectplus.session.AccountSessionCoordinator(store, () -> null),
                    new RecordingSwitchInitiator(), executor, new AtomicInteger());
            handler.loadSession(firstLobby, OWNER, "AccountPlayer");
            dev.connectplus.testutil.TestClientIdentity.settle(handler, executor, firstLobby);
            final var original = handler.getSession();
            original.account = new dev.connectplus.testutil.StubAccount();
            original.playerData.saveLoginInfo = false;
            original.playerData.offlineMode = true;
            store.save(original.playerData);
            firstLobby.close(); // the backend changes but c2p is still alive
            proxy.setGameProfile(new GameProfile(UUID.randomUUID(), "OfflineBackendProfile"));

            final var next = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                    new IdentityLinkStore(store.playersDir()), new dev.connectplus.session.AccountSessionCoordinator(store, () -> null),
                    new RecordingSwitchInitiator(), executor, new AtomicInteger());
            next.loadSession(returnLobby, OWNER, "AccountPlayer");
            assertSame(original.account, next.getSession().account,
                    "An offline backend switch must preserve even a login that is not saved on disk");
            assertNotNull(next.getSession().account);
            assertTrue(next.getSession().offlineMode());
            assertNull(store.load(ProfileKey.javaProfile(OWNER)).accountBlob, "Keeping the live login must not persist account tokens");
            client.close();
            assertNull(next.getSession().account, "A session-only login must be cleared on real client disconnect");
        } finally {
            LobbyLink.unregister(link);
            executor.shutdownNow();
            firstLobby.finishAndReleaseAll();
            returnLobby.finishAndReleaseAll();
            client.finishAndReleaseAll();
            ViaProxy.getConfig().setProxyOnlineMode(oldOnline);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "bookmarks", "closed"})
    void lateAccountRestoreUpdatesOnlyAnOpenMainScreen(final String screen) throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldOnline = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final var lobby = new EmbeddedChannel();
        final var executor = Executors.newSingleThreadExecutor();
        final var restoreStarted = new CountDownLatch(1);
        final var releaseRestore = new CountDownLatch(1);
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            final TokenStore tokens = new TokenStore(this.dataDir) {
                @Override public String decrypt(final String blob) {
                    restoreStarted.countDown();
                    try {
                        if (!releaseRestore.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Restore was not released");
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return super.decrypt(blob);
                }
            };
            final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
            final PlayerData saved = new PlayerData(OWNER);
            saved.accountBlob = tokens.encrypt(syntheticAccountJson(OWNER, "AccountPlayer"));
            store.save(saved);
            final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                    new IdentityLinkStore(store.playersDir()), new dev.connectplus.session.AccountSessionCoordinator(store, () -> null),
                    new RecordingSwitchInitiator(), executor, new AtomicInteger());
            dev.connectplus.testutil.TestClientIdentity.linkJava(lobby, OWNER, "AccountPlayer");
            handler.loadSession(lobby, OWNER, "AccountPlayer");
            dev.connectplus.testutil.TestClientIdentity.awaitProfile(handler, executor, lobby);
            assertTrue(restoreStarted.await(3, TimeUnit.SECONDS));
            handler.update(lobby, net.raphimc.netminecraft.constants.ConnectionState.PLAY);
            assertNull(handler.getSession().account, "Initial GUI opens while restore is still pending");
            if (screen.equals("bookmarks")) {
                final var click = new dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket();
                click.containerId = 1;
                click.slot = 31;
                handler.getStateHandler().handle(click);
            } else if (screen.equals("closed")) {
                handler.getStateHandler().handle(new dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket(1));
            }
            while (lobby.readOutbound() != null) { }
            releaseRestore.countDown();
            executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
            lobby.runPendingTasks();
            assertNotNull(handler.getSession().account);
            if (screen.equals("main")) {
                dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket updated = null;
                Object packet;
                while ((packet = lobby.readOutbound()) != null) {
                    assertFalse(packet instanceof dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket,
                            "Refreshing account details must not reopen the inventory");
                    if (packet instanceof dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket content) updated = content;
                }
                assertNotNull(updated, "The main screen must show a completed account restore without manual reopening");
                // ItemBuilder intentionally omits lore without a running Via platform.
                // Observe the real contents update plus the restored account state.
                assertNotNull(updated.items[13]);
            } else {
                assertNull(lobby.readOutbound(), "Account restore must not replace another screen or reopen a closed one");
            }
        } finally {
            releaseRestore.countDown();
            executor.shutdownNow();
            lobby.finishAndReleaseAll();
            ViaProxy.getConfig().setProxyOnlineMode(oldOnline);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    @ParameterizedTest
    @CsvSource({"47,false", "578,false", "758,false", "774,false", "47,true", "578,true", "758,true", "774,true"})
    void verifiedIdentityRestoresSameSavedAccountAcrossVersions(final int clientVersion, final boolean backendRewritesIdentity) throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldOnline = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final var client = new EmbeddedChannel();
        final var lobby = new EmbeddedChannel();
        final var executor = Executors.newSingleThreadExecutor();
        final UUID link = UUID.randomUUID();
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            final var proxy = new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
            proxy.setClientVersion(ProtocolVersion.getProtocol(clientVersion));
            proxy.setGameProfile(new GameProfile(OWNER, "AccountPlayer"));
            client.attr(CPAttributeKeys.ENABLE_HAPROXY).set(true);
            client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(dev.connectplus.identity.ClientIdentity.verifiedJava(OWNER, "AccountPlayer", OWNER));
            final var forwarding = new LobbyHaProxy(() -> null);
            final var events = net.lenni0451.lambdaevents.LambdaManager.basic(
                    new net.lenni0451.lambdaevents.generator.LambdaMetaFactoryGenerator(java.lang.invoke.MethodHandles.lookup()));
            events.register(forwarding);
            events.call(new ClientLoggedInEvent(proxy));
            LobbyLink.register(link, proxy);
            lobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(link);

            final TokenStore tokens = new TokenStore(this.dataDir);
            final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
            final PlayerData saved = new PlayerData(OWNER);
            saved.accountBlob = tokens.encrypt(syntheticAccountJson(OWNER, "AccountPlayer"));
            saved.bookmarks.add(new Bookmark("Existing bookmark", "example.net", null, 1, 2));
            store.save(saved);
            // Another UUID's data must not be used merely because its name matches.
            final UUID otherId = UUID.fromString("87654321-4321-4321-8321-cba987654321");
            final PlayerData other = new PlayerData(otherId);
            other.bookmarks.add(new Bookmark("Other player's bookmark", "other.example", null, 1, 2));
            store.save(other);

            final C2SLoginHelloPacket original = new C2SLoginHelloPacket("AccountPlayer", null, null, null, OWNER);
            final var wire = Unpooled.buffer();
            final var decoded = new C2SLoginHelloPacket();
            try {
                original.write(wire, clientVersion);
                decoded.read(wire, clientVersion);
            } finally {
                wire.release();
            }
            if (clientVersion < 759) assertNull(decoded.uuid, "Old login packets carry no online UUID");
            if (backendRewritesIdentity) {
                proxy.setGameProfile(new GameProfile(otherId, "BackendAccount"));
                decoded.uuid = otherId;
                decoded.name = "BackendAccount";
                // Repeated events and a fresh lobby-return link cannot replace
                // the original authenticated owner of the c2p connection.
                events.call(new ClientLoggedInEvent(proxy));
                final UUID returnLink = UUID.randomUUID();
                LobbyLink.register(returnLink, proxy);
                lobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(returnLink);
                LobbyLink.unregister(link);
                // The link is resolved during login, then can be removed normally.
                lobby.closeFuture().addListener(future -> LobbyLink.unregister(returnLink));
            } else if (decoded.uuid == null) {
                // Via's old-to-modern login conversion can synthesize an offline UUID.
                decoded.uuid = UUID.nameUUIDFromBytes("OfflinePlayer:AccountPlayer".getBytes(StandardCharsets.UTF_8));
            }
            final var handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store,
                    new IdentityLinkStore(store.playersDir()), new dev.connectplus.session.AccountSessionCoordinator(store, () -> null),
                    new RecordingSwitchInitiator(), executor, new AtomicInteger());
            new LoginStateHandler(handler, lobby).handle(decoded);
            final S2CLoginGameProfilePacket response = lobby.readOutbound();
            assertEquals(OWNER, response.uuid);
            assertEquals("AccountPlayer", response.name);
            dev.connectplus.testutil.TestClientIdentity.settle(handler, executor, lobby);
            final var session = handler.getSession();
            assertEquals(OWNER, session.uuid);
            assertEquals("AccountPlayer", session.name);
            assertNotNull(session.account, "Saved Microsoft account should restore for every client version");
            assertEquals(OWNER, session.account.uuid());
            assertFalse(session.account.isExpired());
            assertEquals(saved.accountBlob, session.playerData.accountBlob);
            assertEquals("Existing bookmark", session.playerData.bookmarks.get(0).name);
            assertTrue(session.account.displayName().contains("AccountPlayer"));
            assertEquals(saved.accountBlob, store.load(ProfileKey.javaProfile(OWNER)).accountBlob);
        } finally {
            LobbyLink.unregister(link);
            executor.shutdownNow();
            lobby.finishAndReleaseAll();
            client.finishAndReleaseAll();
            ViaProxy.getConfig().setProxyOnlineMode(oldOnline);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    /** Cached synthetic profile/token make real deserialization independent of Microsoft services. */
    private static String syntheticAccountJson(final UUID uuid, final String name) {
        final var manager = JavaAuthManager.create(MinecraftAuth.createHttpClient())
                .login(new MsaToken(Long.MAX_VALUE, "test-only-access", "test-only-refresh"));
        manager.getMinecraftToken().set(new MinecraftToken(Long.MAX_VALUE, "Bearer", "test-only-minecraft-token"));
        manager.getMinecraftProfile().set(new MinecraftProfile(uuid, name));
        final String json = JavaAuthManager.toJson(manager).toString();
        assertEquals(dev.connectplus.compat.CpAccounts.RestoreResult.Status.RESTORED, CpAccounts.restore(json).status(),
                "The test account fixture must deserialize without network refresh");
        return json;
    }

    @Test
    void bedrockIdentityTravelsOnlyThroughTheInProcessLobbyLink() {
        ViaProxyTestConfig.init();
        final var client = new EmbeddedChannel();
        final var lobby = new EmbeddedChannel();
        final UUID link = UUID.randomUUID();
        try {
            // A verified bedrock identity bound to the c2p channel, reachable through
            // the link — never through client-supplied TLV content.
            final UUID wireUuid = UUID.nameUUIDFromBytes("OfflinePlayer:BedrockPlayer".getBytes(StandardCharsets.UTF_8));
            client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(dev.connectplus.identity.ClientIdentity.verifiedBedrock(
                    wireUuid, "BedrockPlayer", "4242424242424", UUID.randomUUID(), UUID.randomUUID().toString()));
            final var proxy = new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
            LobbyLink.register(link, proxy);
            lobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(link);

            final var identity = LobbyLink.clientIdentityOf(lobby);
            assertNotNull(identity);
            assertEquals(dev.connectplus.identity.ClientIdentity.Kind.VERIFIED_BEDROCK, identity.kind());
            assertEquals("4242424242424", identity.xuid());

            // An unverified identity stays unverified through the link — a lobby visit
            // never upgrades it.
            final var bareLobby = new EmbeddedChannel();
            final UUID bareLink = UUID.randomUUID();
            LobbyLink.register(bareLink, proxy);
            bareLobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(bareLink);
            client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(
                    dev.connectplus.identity.ClientIdentity.unverified(wireUuid, "BedrockPlayer"));
            assertEquals(dev.connectplus.identity.ClientIdentity.Kind.UNVERIFIED,
                    LobbyLink.clientIdentityOf(bareLobby).kind());
            assertNull(LobbyLink.clientIdentityOf(new EmbeddedChannel()),
                    "no link id, no identity");
        } finally {
            LobbyLink.unregister(link);
            client.finishAndReleaseAll();
            lobby.finishAndReleaseAll();
        }
    }
}
