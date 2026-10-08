package dev.connectplus.lobby;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.config.CPConfig;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viaproxy.ViaProxy;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(dev.connectplus.testutil.AccountPolicyExtension.class)
class AccountRestorePolicyTest {
    @TempDir File dataDir;

    @ParameterizedTest
    @CsvSource({"false,true,false", "true,false,false", "true,true,true"})
    void disabledOrCancelledRestorePreservesStoredData(final boolean proxyOnline, final boolean pluginLogin,
                                                       final boolean disableDuringDecrypt) throws Exception {
        ViaProxyTestConfig.init();
        final boolean originalProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean originalPlugin = CPConfig.allowAccountLogin;
        final AtomicInteger decrypts = new AtomicInteger();
        final TokenStore tokens = new TokenStore(this.dataDir) {
            @Override public String decrypt(final String blob) {
                decrypts.incrementAndGet();
                if (disableDuringDecrypt) {
                    ViaProxy.getConfig().setProxyOnlineMode(false);
                    return "{}";
                }
                return null;
            }
        };
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final PlayerData data = new PlayerData(UUID.randomUUID());
        data.accountBlob = "existing-encrypted-token";
        data.bookmarks.add(new dev.connectplus.session.Bookmark("kept", "example.net", null, 1, 2));
        store.save(data);
        final var executor = Executors.newSingleThreadExecutor();
        final var channel = new EmbeddedChannel();
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(store, () -> null);
        try {
            ViaProxy.getConfig().setProxyOnlineMode(proxyOnline);
            CPConfig.allowAccountLogin = pluginLogin;
            final SessionRegistry registryRef = new SessionRegistry();
        final LobbyServerHandler handler = new LobbyServerHandler(Set.of(), registryRef, tokens,
                    store, new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                    coordinator,
                    new RecordingSwitchInitiator(),
                    executor, new AtomicInteger());
            dev.connectplus.testutil.TestClientIdentity.linkJava(channel, data.uuid(), "AccountPlayer");
            handler.loadSession(channel, data.uuid(), "AccountPlayer");
            dev.connectplus.testutil.TestClientIdentity.settle(handler, executor, channel);
            // A cancellation must remain inert even if mode is enabled again
            // before the worker's result would be applied on the event loop.
            if (disableDuringDecrypt) ViaProxy.getConfig().setProxyOnlineMode(true);
            channel.runPendingTasks();
            assertEquals(disableDuringDecrypt ? 1 : 0, decrypts.get(), "Initially disabled restoration must not touch encrypted tokens");
            assertNull(handler.getSession().account);
            assertEquals(data.accountBlob, handler.getSession().playerData.accountBlob);
            assertEquals(data.accountBlob, store.load(ProfileKey.javaProfile(data.uuid())).accountBlob);
            assertEquals(1, handler.getSession().playerData.bookmarks.size());
        } finally {
            channel.finishAndReleaseAll();
            executor.shutdownNow();
            coordinator.shutdown();
            ViaProxy.getConfig().setProxyOnlineMode(originalProxy);
            CPConfig.allowAccountLogin = originalPlugin;
        }
    }

    /**
     * Task 6 §6 (Review Focus R3): an UNRESTORABLE blob must keep the encrypted
     * accountBlob — a failed restore is never treated as a logout, so the
     * credentials stay on disk. The old "failure → null → clear the blob"
     * bypass is forbidden. This test drives the restore end-to-end through the
     * handler with a blob that fails at the DECRYPT step (the secret.key rotated
     * = the CORRUPT path, "undecryptable_blob"); the temporary-refresh (RETRYABLE)
     * path is classified and pinned at the {@code CpAccounts.restoreThrowing}
     * unit seam, and this test's blob-keeping assertions apply to both paths
     * identically (the landing guard never touches the blob for any failure).
     */
    @org.junit.jupiter.api.Test
    void corruptRestoreKeepsTheEncryptedBlobAndInstallsNothing() throws Exception {
        ViaProxyTestConfig.init();
        final boolean originalProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean originalPlugin = CPConfig.allowAccountLogin;
        final TokenStore tokens = new TokenStore(this.dataDir);
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final PlayerData data = new PlayerData(UUID.randomUUID());
        data.accountBlob = "existing-encrypted-token";
        data.bookmarks.add(new dev.connectplus.session.Bookmark("kept", "example.net", null, 1, 2));
        store.save(data);
        final var executor = Executors.newSingleThreadExecutor();
        final var channel = new EmbeddedChannel();
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(store, () -> null);
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            final LobbyServerHandler handler = new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens,
                    store, new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                    coordinator,
                    new RecordingSwitchInitiator(),
                    executor, new AtomicInteger());
            handler.loadSession(channel, data.uuid(), "RetryPlayer");
            dev.connectplus.testutil.TestClientIdentity.settle(handler, executor, channel);
            channel.runPendingTasks();

            assertNull(handler.getSession().account, "A failed restore must not install an account");
            assertEquals("existing-encrypted-token", handler.getSession().playerData.accountBlob,
                    "A temporary refresh failure must keep the session's blob");
            assertEquals("existing-encrypted-token", store.load(ProfileKey.javaProfile(data.uuid())).accountBlob,
                    "A temporary refresh failure must keep the PERSISTED blob (never a side-effect logout)");
            assertEquals(1, handler.getSession().playerData.bookmarks.size());
        } finally {
            channel.finishAndReleaseAll();
            executor.shutdownNow();
            coordinator.shutdown();
            ViaProxy.getConfig().setProxyOnlineMode(originalProxy);
            CPConfig.allowAccountLogin = originalPlugin;
        }
    }

    /**
     * Task 6 §6: a saveLoginInfo=false session keeps no stored login. A stale
     * blob left from before the toggle existed must not be restored (and the
     * disconnect wipe, not a failed restore, is what removes it).
     */
    @org.junit.jupiter.api.Test
    void saveLoginOffRestoresNothingFromAnyBlob() throws Exception {
        ViaProxyTestConfig.init();
        final boolean originalProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean originalPlugin = CPConfig.allowAccountLogin;
        final TokenStore tokens = new TokenStore(this.dataDir);
        final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
        final PlayerData data = new PlayerData(UUID.randomUUID());
        data.saveLoginInfo = false;
        data.accountBlob = "stale-encrypted-token";
        store.save(data);
        final var executor = Executors.newSingleThreadExecutor();
        final var channel = new EmbeddedChannel();
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(store, () -> null);
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            final SessionRegistry registryRef = new SessionRegistry();
        final LobbyServerHandler handler = new LobbyServerHandler(Set.of(), registryRef, tokens,
                    store, new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                    coordinator,
                    new RecordingSwitchInitiator(),
                    executor, new AtomicInteger());
            dev.connectplus.testutil.TestClientIdentity.linkJava(channel, data.uuid(), "SessionOnlyPlayer");
            handler.loadSession(channel, data.uuid(), "SessionOnlyPlayer");
            dev.connectplus.testutil.TestClientIdentity.settle(handler, executor, channel);
            channel.runPendingTasks();

            assertNull(handler.getSession().account, "save-login off must not restore any stored login");
        } finally {
            channel.finishAndReleaseAll();
            executor.shutdownNow();
            coordinator.shutdown();
            ViaProxy.getConfig().setProxyOnlineMode(originalProxy);
            CPConfig.allowAccountLogin = originalPlugin;
        }
    }

    /**
     * Task 6 (requirement 3): linked A/B alternation — B's stored credentials
     * restore for the LINKED bedrock session too. The restore is per
     * ProfileKey (the B java profile), so the account lands regardless of the
     * entering identity being A's bedrock wire identity.
     */
    @org.junit.jupiter.api.Test
    void linkedBedrockSessionRestoresTheLinkedJavaAccountsCredentials() throws Exception {
        ViaProxyTestConfig.init();
        final boolean originalProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean originalPlugin = CPConfig.allowAccountLogin;
        final var client = new EmbeddedChannel();
        final var lobby = new EmbeddedChannel();
        final UUID link = UUID.randomUUID();
        final UUID javaUuid = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000bb");
        final var executor = Executors.newSingleThreadExecutor();
        final AtomicInteger uncaughtCounter = new AtomicInteger();
        final var coordinator = new dev.connectplus.session.AccountSessionCoordinator(
                new PlayerStore(new File(this.dataDir, "players")), () -> null);
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.allowAccountLogin = true;
            // Seed B's profile with a restorable (far-future, network-free) token.
            final TokenStore tokens = new TokenStore(this.dataDir);
            final PlayerStore store = new PlayerStore(new File(this.dataDir, "players"));
            final PlayerData data = new PlayerData(ProfileKey.javaProfile(javaUuid));
            data.accountBlob = tokens.encrypt(StaleAccountFixture.json(javaUuid, "AccountPlayer"));
            data.bookmarks.add(new dev.connectplus.session.Bookmark("B", "b.example.net", null, 1, 2));
            store.save(data);

            final UUID wireUuid = UUID.nameUUIDFromBytes("OfflinePlayer:BedrockA".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            dev.connectplus.testutil.TestClientIdentity.bedrock(client,
                    dev.connectplus.identity.ClientIdentity.verifiedBedrock(
                            wireUuid, "BedrockA", "4242424242424", UUID.randomUUID(), UUID.randomUUID().toString()));
            client.attr(dev.connectplus.compat.CPAttributeKeys.PLAYER_IDENTITY).set(
                    new dev.connectplus.session.PlayerIdentity(wireUuid, "BedrockA"));
            final var proxy = new net.raphimc.viaproxy.proxy.session.ProxyConnection(
                    new net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer(
                            net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler::new), client);
            dev.connectplus.compat.LobbyLink.register(link, proxy);
            lobby.attr(dev.connectplus.compat.CPAttributeKeys.LOBBY_LINK_ID).set(link);
            new dev.connectplus.identity.IdentityLinkStore(store.playersDir()).replace(0, java.util.Map.of("4242424242424", javaUuid));

            final SessionRegistry registryRef = new SessionRegistry();
        final LobbyServerHandler handler = new LobbyServerHandler(Set.of(), registryRef, tokens,
                    store, new dev.connectplus.identity.IdentityLinkStore(store.playersDir()),
                    coordinator, new RecordingSwitchInitiator(), executor, uncaughtCounter);
            new dev.connectplus.lobby.states.LoginStateHandler(handler, lobby).handle(
                    new net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket("BedrockA", null, null, null, wireUuid));
            //The claim → load → land → restore chain crosses the coordinator's serial
            //domain, the storage executor and the event loop several times. Rather than
            //guessing a round count, wait (bounded, condition-based) until the restore
            //landed — or a clear timeout fails the test.
            final var settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < settleDeadline) {
                for (int round = 0; round < 8; round++) {
                    executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
                    executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
                    lobby.runPendingTasks();
                }
                final var probe = handler.getSession();
                if (probe != null && probe.account != null && probe.profileKey != null) {
                    break;
                }
                Thread.sleep(25);
            }

            final var session = handler.getSession();
            assertEquals(ProfileKey.javaProfile(javaUuid), session.profileKey, "The linked session uses B's java profile");
            assertNotNull(session.account, "B's stored credentials must restore for the linked bedrock session (A's entry identity)");
            assertEquals(javaUuid, session.account.uuid());
            assertEquals(data.accountBlob, store.load(ProfileKey.javaProfile(javaUuid)).accountBlob);
        } finally {
            dev.connectplus.compat.LobbyLink.unregister(link);
            client.finishAndReleaseAll();
            lobby.finishAndReleaseAll();
            executor.shutdownNow();
            coordinator.shutdown();
            ViaProxy.getConfig().setProxyOnlineMode(originalProxy);
            CPConfig.allowAccountLogin = originalPlugin;
        }
    }

    /** Far-future account JSON fixture shared with the stale-operation test. */
    static final class StaleAccountFixture {
        static String json(final UUID uuid, final String name) {
            final var manager = net.raphimc.minecraftauth.java.JavaAuthManager.create(
                            net.raphimc.minecraftauth.MinecraftAuth.createHttpClient())
                    .login(new net.raphimc.minecraftauth.msa.model.MsaToken(Long.MAX_VALUE, "test-only-access", "test-only-refresh"));
            manager.getMinecraftToken().set(new net.raphimc.minecraftauth.java.model.MinecraftToken(Long.MAX_VALUE, "Bearer", "t"));
            manager.getMinecraftProfile().set(new net.raphimc.minecraftauth.java.model.MinecraftProfile(uuid, name));
            return net.raphimc.minecraftauth.java.JavaAuthManager.toJson(manager).toString();
        }
    }
}
