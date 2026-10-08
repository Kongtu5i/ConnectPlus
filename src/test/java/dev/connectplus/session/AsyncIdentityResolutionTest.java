package dev.connectplus.session;

import com.mojang.authlib.GameProfile;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.ClientIdentityCapture;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.DefaultIdentityLinkService;
import dev.connectplus.identity.IdentityLinkService;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClickPacket;
import dev.connectplus.lobby.protocol.packets.play.c2s.C2SContainerClosePacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2CContainerSetContentPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket;
import dev.connectplus.lobby.screen.impl.AccountFlow;
import dev.connectplus.lobby.states.LoginStateHandler;
import dev.connectplus.session.PlayerVisitStore;
import dev.connectplus.testutil.AccountPolicyExtension;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import dev.connectplus.testutil.StubAccount;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.netty.codec.PacketCryptor;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.events.Client2ProxyChannelInitializeEvent;
import net.raphimc.viaproxy.plugins.events.ClientLoggedInEvent;
import net.raphimc.viaproxy.plugins.events.types.ITyped;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real capture -> bridge -> lobby -> coordinator chain across the async boundary. */
@ExtendWith(AccountPolicyExtension.class)
class AsyncIdentityResolutionTest {
    static {
        if (com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.CUSTOM_NAME == null) {
            throw new IllegalStateException("Structured data keys not initialized");
        }
    }
    private static final String XUID = "4242424242424";
    private static final UUID JAVA_UUID = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000bb");
    @TempDir File dataDir;

    @Test
    void protectedProfileWaitsForAdmissionAndResumesOnlyAfterAllowance() throws Exception {
        try (Rig rig = new Rig()) {
            final var access = new dev.connectplus.access.AccessService(
                    new dev.connectplus.access.AccessListStore(dataDir.toPath().resolve("whitelist.json")),
                    new dev.connectplus.access.AccessListStore(dataDir.toPath().resolve("blacklist.json")),
                    new dev.connectplus.access.AccessService.ConfiguredFlags(false, false), Runnable::run);
            access.initialize().toCompletableFuture().join();
            final var registry = new dev.connectplus.access.AccessConnectionRegistry();
            registry.register(rig.client.attr(CPAttributeKeys.CONNECTION_ID).get(), rig.client);
            final var gate = new dev.connectplus.access.AccessGate(access, registry,
                    new dev.connectplus.access.AccessIdentitySource(new dev.connectplus.compat.ClientPlatformInspector()),
                    (channel, task) -> task.run());
            gate.install();
            try {
                rig.login();
                rig.answer("VERIFIED");
                rig.pump();
                assertNull(rig.handler.getSession().lease, "verified identity alone cannot claim a profile");
                assertNull(rig.handler.getSession().playerData);
                assertFalse(new File(rig.store.playersDir(), "bedrock/4242424242424.json").exists());
                gate.admit(ProxyConnection.fromChannel(rig.client)).toCompletableFuture().join();
                rig.pump();
                assertNotNull(rig.handler.getSession().lease);
                assertEquals(ProfileKey.bedrockProfile(XUID), rig.handler.getSession().profileKey);
            } finally {
                dev.connectplus.access.AccessGate.uninstall();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void firstVerifiedBedrockJoinCreatesItsProfileBeforeAccountLogin(boolean early) throws Exception {
        try (Rig rig = new Rig()) {
            if (early) rig.answer("VERIFIED");
            rig.login();
            if (!early) rig.answer("VERIFIED");
            rig.pump();

            PlayerSession session = rig.handler.getSession();
            assertEquals(ProfileKey.bedrockProfile(XUID), session.profileKey);
            assertTrue(rig.coordinator.isCurrent(session.lease));
            assertNull(session.account);
            File profile = new File(rig.store.playersDir(), "bedrock/4242424242424.json");
            assertTrue(profile.isFile(), "Entering the lobby must persist the first Bedrock profile before account login");
            var json = com.google.gson.JsonParser.parseString(Files.readString(profile.toPath())).getAsJsonObject();
            assertTrue(json.getAsJsonArray("bookmarks").isEmpty());
            assertFalse(json.has("account"));
            assertFalse(new File(rig.store.playersDir(), "java/" + rig.wireUuid + ".json").exists());
            assertFalse(new File(rig.store.playersDir(), "identity-links.json").exists());
        }
    }

    @Test
    void firstBedrockProfileSaveFailureClosesTheLobbyAndReturnsItsLease() throws Exception {
        try (Rig rig = new Rig()) {
            rig.failProfileSave = true;
            rig.login();
            rig.answer("VERIFIED");
            rig.pump(1);
            assertFalse(rig.lobby.isActive());
            assertNull(rig.handler.getSession().playerData);
            assertNull(rig.handler.getSession().lease);
            assertFalse(new File(rig.store.playersDir(), "bedrock/4242424242424.json").exists());
            assertFalse(rig.coordinator.accountMayConnect(rig.handler.getSession().connectionId));
        }
    }

    @Test
    void providerStoppingDuringTheFirstProfileLoadCannotCreateABedrockFile() throws Exception {
        try (Rig rig = new Rig()) {
            rig.afterLoad = rig::stopProvider;
            rig.login();
            rig.answer("VERIFIED");
            rig.pump();
            assertNull(rig.handler.getSession().playerData);
            assertNull(rig.handler.getSession().lease);
            assertFalse(new File(rig.store.playersDir(), "bedrock/4242424242424.json").exists());
        }
    }

    @Test
    void joiningWithAnExistingBedrockProfilePreservesItsFile() throws Exception {
        try (Rig rig = new Rig()) {
            rig.seed(false);
            var profile = new File(rig.store.playersDir(), "bedrock/4242424242424.json").toPath();
            byte[] before = Files.readAllBytes(profile);
            rig.login();
            rig.answer("VERIFIED");
            rig.pump();
            assertEquals("saved bookmark", rig.handler.getSession().playerData.bookmarks.get(0).name);
            assertArrayEquals(before, Files.readAllBytes(profile), "Joining must not rewrite an existing profile");
        }
    }

    @Test
    void accountLinkAfterAFreshBedrockJoinPersistsTheIndexAndSwitchesToTheJavaProfile() throws Exception {
        try (Rig rig = new Rig()) {
            rig.login();
            rig.answer("VERIFIED");
            rig.pump();
            PlayerSession session = rig.handler.getSession();
            // Credential encryption is covered separately; this flow exercises a
            // verified account result without saving its credentials.
            session.playerData.saveLoginInfo = false;
            var account = new StubAccount() {
                @Override public UUID uuid() { return JAVA_UUID; }
            };
            var service = new DefaultIdentityLinkService(rig.store, rig.links, rig.coordinator, rig.tokens, Runnable::run);
            assertEquals(IdentityLinkService.Result.COMMITTED,
                    service.link(session, account).toCompletableFuture().get(3, TimeUnit.SECONDS));
            File index = new File(rig.store.playersDir(), "identity-links.json");
            assertTrue(index.isFile());
            // Reload through a new store, so an in-memory result cannot satisfy this check.
            assertEquals(JAVA_UUID, new IdentityLinkStore(rig.store.playersDir()).snapshot().javaUuidFor(XUID));
            assertTrue(new File(rig.store.playersDir(), "java/bbbbbbbb-0000-4000-8000-0000000000bb.json").isFile());
            assertFalse(new File(rig.store.playersDir(), "bedrock/4242424242424.json").exists());
            assertEquals(ProfileKey.javaProfile(JAVA_UUID), session.profileKey);
            assertTrue(rig.coordinator.isCurrent(session.lease));
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void verifiedReplyLoadsTheProfileWhetherItArrivesBeforeOrAfterLobbyLogin(boolean early, boolean linked) throws Exception {
        try (Rig rig = new Rig()) {
            rig.seed(linked);
            if (early) rig.answer("VERIFIED");
            rig.login();
            PlayerSession original = rig.handler.getSession();
            rig.pump();
            rig.handler.update(rig.lobby, ConnectionState.PLAY);
            rig.drain();
            if (!early) {
                assertNull(original.playerData);
                assertNull(original.lease);
                rig.answer("VERIFIED");
            }
            rig.pump();
            assertSame(original, rig.handler.getSession());
            assertNotNull(original.playerData, "A completed identity must trigger the protected profile load");
            assertEquals(linked ? ProfileKey.javaProfile(JAVA_UUID) : ProfileKey.bedrockProfile(XUID), original.profileKey);
            assertEquals("saved bookmark", original.playerData.bookmarks.get(0).name);
            assertTrue(rig.coordinator.isCurrent(original.lease));
            assertEquals(1, rig.loads.get(), "One verified connection must load its profile only once");
            if (linked) {
                assertNotNull(original.account, "The linked Java account must restore after the delayed identity");
                assertEquals(JAVA_UUID, original.account.uuid());
            }
            if (!early) {
                List<Object> packets = rig.drain();
                assertTrue(packets.stream().anyMatch(S2CContainerSetContentPacket.class::isInstance),
                        "The open main screen must refresh even without a saved account");
                assertFalse(packets.stream().anyMatch(S2COpenScreenPacket.class::isInstance));
            }
        }
    }

    @Test
    void lobbyJoiningBeforeTheInitialIdentityIsPublishedWaitsForItsResolution() throws Exception {
        try (Rig rig = new Rig()) {
            rig.seed(false);
            ClientIdentity placeholder = rig.client.attr(CPAttributeKeys.CLIENT_IDENTITY).getAndSet(null);
            rig.login();
            assertNull(rig.handler.getSession().playerData, "A real client must not fall back to a diagnostic profile");
            assertEquals(0, rig.loads.get());
            rig.client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(placeholder);
            rig.answer("VERIFIED");
            rig.pump();
            assertEquals(ProfileKey.bedrockProfile(XUID), rig.handler.getSession().profileKey);
            assertEquals("saved bookmark", rig.handler.getSession().playerData.bookmarks.get(0).name);
            assertEquals(1, rig.loads.get());
        }
    }

    @Test
    void settingsCannotCreateAScratchProfileWhileTheIdentityIsPending() throws Exception {
        try (Rig rig = new Rig()) {
            rig.seed(false);
            rig.login();
            PlayerSession session = rig.handler.getSession();
            assertFalse(AccountFlow.isLeaseCurrent(session, rig.coordinator), "Pending identity is a load window");
            rig.handler.update(rig.lobby, ConnectionState.PLAY);
            for (int slot : new int[]{12, 14}) rig.click(slot);
            assertNull(session.playerData);
            assertFalse(new File(rig.store.playersDir(), "java/" + rig.wireUuid + ".json").exists());
            rig.answer("VERIFIED");
            rig.pump();
            assertEquals("saved bookmark", session.playerData.bookmarks.get(0).name);
            assertTrue(AccountFlow.isLeaseCurrent(session, rig.coordinator));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bookmarks", "closed"})
    void delayedIdentityDoesNotReplaceAnotherScreenOrReopenAClosedScreen(String screen) throws Exception {
        try (Rig rig = new Rig()) {
            rig.seed(true);
            rig.login();
            rig.handler.update(rig.lobby, ConnectionState.PLAY);
            if (screen.equals("bookmarks")) rig.click(31);
            else rig.handler.getStateHandler().handle(new C2SContainerClosePacket(1));
            rig.drain();
            rig.answer("VERIFIED");
            rig.pump();
            assertNotNull(rig.handler.getSession().account);
            assertTrue(rig.drain().isEmpty(), "The identity callback must respect the player's current screen");
        }
    }

    @Test
    void aReplyAfterClientDisconnectCannotUpgradeTheIdentityOrAcquireUsage() throws Exception {
        try (Rig rig = new Rig()) {
            rig.login();
            PlayerSession session = rig.handler.getSession();
            rig.client.close();
            rig.answer("VERIFIED");
            rig.pump();
            assertEquals(ClientIdentity.Kind.UNVERIFIED, rig.client.attr(CPAttributeKeys.CLIENT_IDENTITY).get().kind());
            assertNull(session.lease);
            assertNull(session.playerData);
            assertEquals(0, rig.loads.get());
        }
    }

    @Test
    void aLeaseDeliveredAfterTheLobbyClaimTimesOutIsReleased() throws Exception {
        try (Rig rig = new Rig()) {
            CompletableFuture<SessionLease> allocated = new CompletableFuture<>(), delivered = new CompletableFuture<>();
            SessionLeaseGranter delayed = new SessionLeaseGranter() {
                @Override public java.util.concurrent.CompletionStage<SessionLease> claim(PlayerSession session, ProfileKey key, Set<UUID> accounts) {
                    rig.coordinator.claim(session, key, accounts).whenComplete((lease, error) -> {
                        if (error != null) delivered.completeExceptionally(error);
                        else allocated.complete(lease);
                    });
                    return delivered;
                }
                @Override public boolean isCurrent(SessionLease lease) { return rig.coordinator.isCurrent(lease); }
                @Override public java.util.concurrent.CompletionStage<Void> release(SessionLease lease) { return rig.coordinator.release(lease); }
            };
            rig.handler = rig.newHandler(delayed);
            rig.login();
            rig.answer("VERIFIED");
            rig.pump();
            SessionLease lease = allocated.get(2, TimeUnit.SECONDS);
            assertTrue(rig.coordinator.isCurrent(lease));
            rig.stopProvider();
            // Exercise the production 30-second deadline; the original producer
            // must still be able to deliver its lease for cancellation cleanup.
            Thread.sleep(TimeUnit.SECONDS.toMillis(31));
            assertFalse(delivered.isDone(), "The lobby deadline must not complete the producer's future");
            delivered.complete(lease);
            rig.pump();
            assertFalse(rig.coordinator.isCurrent(lease), "A late grant must not leak exclusive profile usage");
            assertFalse(rig.coordinator.accountMayConnect(rig.handler.getSession().connectionId));
            assertNull(rig.handler.getSession().playerData);
            assertEquals(0, rig.loads.get());
        }
    }

    @Test
    void timedOutIdentityKeepsTheLobbyOpenAndRejectsALateVerifiedReply() throws Exception {
        try (Rig rig = new Rig(Duration.ofMillis(60))) {
            rig.seed(true);
            rig.login();
            ClientIdentity resolved = rig.client.attr(CPAttributeKeys.CLIENT_IDENTITY_RESOLUTION).get().get(2, TimeUnit.SECONDS);
            assertEquals(ClientIdentity.Kind.UNVERIFIED, resolved.kind());
            rig.answer("VERIFIED");
            rig.pump();
            assertTrue(rig.lobby.isActive());
            assertEquals(ClientIdentity.Kind.UNVERIFIED, rig.client.attr(CPAttributeKeys.CLIENT_IDENTITY).get().kind());
            assertNull(rig.handler.getSession().account);
            assertNull(rig.handler.getSession().lease);
            assertNull(rig.handler.getSession().playerData);
            assertEquals(0, rig.loads.get());
            assertTrue(AccountFlow.isLeaseCurrent(rig.handler.getSession(), rig.coordinator));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"client", "provider"})
    void invalidatingTheConnectionBeforeQueuedStorageWorkPreventsAnyReadOrClaim(String ended) throws Exception {
        try (Rig rig = new Rig()) {
            CountDownLatch started = new CountDownLatch(1), unblock = new CountDownLatch(1);
            rig.storage.execute(() -> {
                started.countDown();
                try { assertTrue(unblock.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                rig.login();
                rig.answer("VERIFIED");
                rig.lobby.runPendingTasks();
                if (ended.equals("client")) rig.client.close();
                else rig.stopProvider();
            } finally { unblock.countDown(); }
            rig.pump();
            assertNull(rig.handler.getSession().lease);
            assertNull(rig.handler.getSession().playerData);
            assertFalse(rig.coordinator.accountMayConnect(rig.handler.getSession().connectionId));
            assertEquals(0, rig.loads.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onlyTheCurrentLobbyMayUseTheIdentityReplyAfterASwitch(boolean replyBeforeReturn) throws Exception {
        try (Rig rig = new Rig()) {
            rig.seed(false);
            rig.login();
            PlayerSession original = rig.handler.getSession();
            EmbeddedChannel oldLobby = rig.lobby;
            if (replyBeforeReturn) rig.answer("VERIFIED"); // its callback is queued on the old lobby
            rig.returnToLobby(); // move session ownership while the old channel is still alive
            if (!replyBeforeReturn) rig.answer("VERIFIED");
            rig.pump();
            assertSame(original, rig.handler.getSession());
            assertSame(rig.lobby, original.lobbyChannel);
            assertNotNull(original.lease);
            assertEquals("saved bookmark", original.playerData.bookmarks.get(0).name);
            assertEquals(1, rig.loads.get());
            assertTrue(oldLobby.outboundMessages().stream().noneMatch(S2CContainerSetContentPacket.class::isInstance));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_MATCH", "REJECTED"})
    void aNegativeReplyKeepsTheLobbyOpenAndLoadsNoProtectedData(String status) throws Exception {
        try (Rig rig = new Rig()) {
            rig.login();
            rig.answer(status);
            rig.pump();
            assertTrue(rig.lobby.isActive());
            assertNull(rig.handler.getSession().profileKey);
            assertNull(rig.handler.getSession().lease);
            assertNull(rig.handler.getSession().playerData);
            assertEquals(0, rig.loads.get());
            assertTrue(AccountFlow.isLeaseCurrent(rig.handler.getSession(), rig.coordinator),
                    "A negative result must end the identity-pending window");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void providerStoppingBeforeTheLobbyProcessesAReplyPreventsProtectedAccess(boolean beforeReply) throws Exception {
        try (Rig rig = new Rig()) {
            rig.login();
            if (beforeReply) rig.stopProvider();
            rig.answer("VERIFIED");
            if (!beforeReply) rig.stopProvider();
            rig.pump();
            if (beforeReply) assertEquals(ClientIdentity.Kind.UNVERIFIED, rig.client.attr(CPAttributeKeys.CLIENT_IDENTITY).get().kind());
            assertNull(rig.handler.getSession().lease);
            assertNull(rig.handler.getSession().playerData);
            assertEquals(0, rig.loads.get());
        }
    }

    @Test
    void lateVerificationMergesTheTemporaryVisitIntoTheConfirmedIdentity() throws Exception {
        final PlayerVisitStore visits = new PlayerVisitStore(new File(dataDir, "visits-index.json"));
        try (Rig rig = new Rig()) {
            rig.handler = rig.newHandler(rig.coordinator, visits);
            rig.login();
            //The lobby join is recorded before the RESOLVE answer arrives: a
            //temporary protocol-identity visit
            rig.handler.update(rig.lobby, ConnectionState.PLAY);
            rig.pump();
            assertEquals(1, visits.snapshot().uniqueVisitorCount(Map.of()));
            assertTrue(visits.snapshot().visits().get(0).temporary(), "The pre-verification visit is temporary");

            //The late trusted identity folds the temporary record into the
            //confirmed XUID identity instead of adding a second visitor
            rig.answer("VERIFIED");
            rig.pump();
            final PlayerVisitStore.Snapshot snapshot = visits.snapshot();
            assertEquals(1, snapshot.uniqueVisitorCount(Map.of()),
                    "The confirmed visit must not double count the temporary one");
            assertEquals(1, snapshot.visits().size());
            assertFalse(snapshot.visits().get(0).temporary());
            assertEquals(XUID, snapshot.visits().get(0).xuid());
        }
    }

    private final class Rig implements AutoCloseable {
        final UUID wireUuid = UUID.randomUUID(), epoch = UUID.randomUUID(), link = UUID.randomUUID();
        final EmbeddedChannel client = new EmbeddedChannel(new PacketCryptor());
        final List<EmbeddedChannel> lobbies = new ArrayList<>();
        EmbeddedChannel lobby;
        final ExecutorService storage = Executors.newSingleThreadExecutor();
        final AtomicInteger loads = new AtomicInteger(), uncaught = new AtomicInteger();
        boolean failProfileSave;
        Runnable afterLoad;
        final TokenStore tokens = new TokenStore(dataDir);
        final PlayerStore store = new PlayerStore(new File(dataDir, "players")) {
            @Override public PlayerData load(ProfileKey key) throws IOException {
                loads.incrementAndGet();
                PlayerData data = super.load(key);
                if (afterLoad != null) afterLoad.run();
                return data;
            }
            @Override public void save(ProfileKey key, PlayerData data) throws IOException {
                if (failProfileSave) throw new IOException("Injected initial profile save failure");
                super.save(key, data);
            }
        };
        final IdentityLinkStore links = new IdentityLinkStore(store.playersDir());
        final BedrockBridgeEndpoint endpoint;
        final AccountSessionCoordinator coordinator;
        final CompletableFuture<Map<String, Object>> reply = new CompletableFuture<>();
        Map<String, Object> request;
        final String registrationId;
        LobbyServerHandler handler;

        Rig() { this(Duration.ofSeconds(3)); }

        Rig(Duration resolveTimeout) {
            endpoint = new BedrockBridgeEndpoint(resolveTimeout, Duration.ofSeconds(1), Duration.ofSeconds(1));
            coordinator = new AccountSessionCoordinator(store, () -> endpoint);
            ViaProxy.getConfig().setProxyOnlineMode(false);
            registrationId = (String) endpoint.register(Map.of("protocolVersion", 1,
                    "providerId", BedrockBridgeEndpoint.PROVIDER_ID, "providerEpoch", epoch.toString(),
                    "bridgeVersion", "test", "geyserVersion", "test", "viaproxyVersion", "3.4.13",
                    "capabilities", List.copyOf(BedrockBridgeEndpoint.REQUIRED_CAPABILITIES)), incoming -> {
                request = incoming;
                return reply;
            }).get("registrationId");
            assertNotNull(registrationId);
            ProxyConnection proxy = new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
            client.attr(ProxyConnection.PROXY_CONNECTION_ATTRIBUTE_KEY).set(proxy);
            proxy.setGameProfile(new GameProfile(wireUuid, "BedrockPlayer"));
            client.attr(CPAttributeKeys.PLAYER_IDENTITY).set(new PlayerIdentity(wireUuid, "BedrockPlayer"));
            ClientIdentityCapture capture = new ClientIdentityCapture(() -> endpoint);
            capture.onClient2ProxyChannelInitialize(new Client2ProxyChannelInitializeEvent(ITyped.Type.PRE, client, false));
            capture.onClientLoggedIn(new ClientLoggedInEvent(proxy));
            LobbyLink.register(link, proxy);
            newLobby();
        }

        void newLobby() {
            lobby = new EmbeddedChannel();
            lobbies.add(lobby);
            lobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(link);
            handler = newHandler(coordinator);
        }

        LobbyServerHandler newHandler(SessionLeaseGranter granter) {
            return newHandler(granter, null);
        }

        LobbyServerHandler newHandler(SessionLeaseGranter granter, PlayerVisitStore visits) {
            return new LobbyServerHandler(Set.of(), new SessionRegistry(), tokens, store, links,
                    granter, new RecordingSwitchInitiator(), storage, uncaught, null, visits);
        }

        void login() {
            new LoginStateHandler(handler, lobby).handle(new C2SLoginHelloPacket("BedrockPlayer", null, null, null, wireUuid));
            drain();
        }

        void returnToLobby() { newLobby(); login(); }

        void stopProvider() {
            endpoint.handleEvent(registrationId, Map.of("protocolVersion", 1,
                    "providerEpoch", epoch.toString(), "op", "PROVIDER_STOPPING", "reasonCode", "TEST"))
                    .toCompletableFuture().join();
        }

        void answer(String status) {
            if (status.equals("VERIFIED")) reply.complete(Map.of("protocolVersion", 1,
                    "providerEpoch", epoch.toString(), "connectionId", request.get("connectionId"),
                    "status", status, "xuid", XUID, "bedrockUsername", "BedrockPlayer",
                    "bridgeSessionId", UUID.randomUUID().toString()));
            else reply.complete(Map.of("protocolVersion", 1, "providerEpoch", epoch.toString(),
                    "connectionId", request.get("connectionId"), "status", status));
            client.runPendingTasks();
        }

        void seed(boolean linked) throws Exception {
            ProfileKey key = linked ? ProfileKey.javaProfile(JAVA_UUID) : ProfileKey.bedrockProfile(XUID);
            PlayerData data = new PlayerData(key);
            data.bookmarks.add(new Bookmark("saved bookmark", "backend.example.net", null, 1, 2));
            if (linked) {
                links.replace(0, Map.of(XUID, JAVA_UUID));
                var manager = net.raphimc.minecraftauth.java.JavaAuthManager.create(net.raphimc.minecraftauth.MinecraftAuth.createHttpClient())
                        .login(new net.raphimc.minecraftauth.msa.model.MsaToken(Long.MAX_VALUE, "test", "test"));
                manager.getMinecraftToken().set(new net.raphimc.minecraftauth.java.model.MinecraftToken(Long.MAX_VALUE, "Bearer", "test"));
                manager.getMinecraftProfile().set(new net.raphimc.minecraftauth.java.model.MinecraftProfile(JAVA_UUID, "JavaPlayer"));
                data.accountBlob = tokens.encrypt(net.raphimc.minecraftauth.java.JavaAuthManager.toJson(manager).toString());
            }
            store.save(data);
        }

        void click(int slot) {
            C2SContainerClickPacket packet = new C2SContainerClickPacket();
            packet.containerId = 1;
            packet.slot = slot;
            handler.getStateHandler().handle(packet);
        }

        List<Object> drain() {
            List<Object> packets = new ArrayList<>();
            Object packet;
            while ((packet = lobby.readOutbound()) != null) packets.add(packet);
            return packets;
        }

        void pump() throws Exception {
            pump(0);
        }

        void pump(int expectedFailures) throws Exception {
            for (int round = 0; round < 16; round++) {
                coordinator.awaitIdle().get(3, TimeUnit.SECONDS);
                storage.submit(() -> { }).get(3, TimeUnit.SECONDS);
                client.runPendingTasks();
                lobbies.forEach(EmbeddedChannel::runPendingTasks);
            }
            assertEquals(expectedFailures, uncaught.get());
        }

        @Override public void close() {
            client.finishAndReleaseAll();
            lobbies.forEach(EmbeddedChannel::finishAndReleaseAll);
            LobbyLink.unregister(link);
            storage.shutdownNow();
            coordinator.shutdown();
        }
    }
}
