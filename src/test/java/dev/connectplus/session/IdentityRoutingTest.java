package dev.connectplus.session;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.states.LoginStateHandler;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 3: the lobby profile flow routes through the verified ClientIdentity
 * (resolve -> claim -> load) while the outgoing protocol identity stays the
 * entry connection's original wire identity (plan §2), the session is keyed by
 * the c2p connection id across switches and returns (§5), and the lease stub
 * gates every profile access.
 */
class IdentityRoutingTest {

    private static final UUID WIRE_UUID = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000aa");
    private static final UUID JAVA_UUID = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000bb");
    private static final String WIRE_NAME = "BedrockA";
    private static final String XUID = "4242424242424";

    @TempDir
    File dataDir;

    // ---- plan §2: profile owner follows the link, wire identity never ----

    @Test
    void linkedBedrockUsesTheLinkedJavaProfileWhileEveryProtocolUuidStaysTheWireIdentity() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig(this.dataDir, verifiedBedrockIdentity())) {
            rig.links.replace(0, Map.of(XUID, JAVA_UUID));
            rig.seedBedrockProfile("A's own bedrock bookmark");
            rig.seedJavaProfile("B's java bookmark");

            rig.login();
            final S2CLoginGameProfilePacket response = rig.lobby.readOutbound();
            assertEquals(WIRE_UUID, response.uuid, "The login response must carry A's original wire uuid");
            assertEquals(WIRE_NAME, response.name, "The login response must carry A's original wire name");

            rig.pump();

            final PlayerSession session = rig.handler.getSession();
            assertEquals(WIRE_UUID, session.uuid, "The session's protocol uuid stays the wire identity");
            assertEquals(WIRE_NAME, session.name);
            assertEquals(ProfileKey.javaProfile(JAVA_UUID), session.profileKey,
                    "A linked bedrock must resolve to the JAVA profile of the linked account");
            assertEquals("B's java bookmark", session.playerData.bookmarks.get(0).name,
                    "The linked Java profile (B) must be loaded, not A's own bedrock profile");
            assertEquals(ProfileKey.javaProfile(JAVA_UUID), session.playerData.key);
            assertNotNull(session.lease, "A protected profile must be held under a granted lease");
            assertEquals(session.connectionId, session.lease.connectionId());
            assertEquals(session.lease.generation(), session.generation);
            assertEquals(rig.connectionId, session.connectionId, "The session must use the c2p connection id");
            assertSame(session, rig.registry.get(session.connectionId));
        }
    }

    @Test
    void unlinkedBedrockUsesItsOwnBedrockProfileAndKeepsItsWireIdentity() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig(this.dataDir, verifiedBedrockIdentity())) {
            rig.seedBedrockProfile("A's own bedrock bookmark");
            rig.seedJavaProfile("B's java bookmark");

            rig.login();
            final S2CLoginGameProfilePacket response = rig.lobby.readOutbound();
            assertEquals(WIRE_UUID, response.uuid);
            rig.pump();

            final PlayerSession session = rig.handler.getSession();
            assertEquals(ProfileKey.bedrockProfile(XUID), session.profileKey,
                    "An unlinked bedrock must use its own BEDROCK profile");
            assertEquals("A's own bedrock bookmark", session.playerData.bookmarks.get(0).name);
            assertNotNull(session.lease);
            assertEquals(WIRE_UUID, session.uuid);
        }
    }

    @Test
    void unverifiedIdentityGetsNoProtectedPathAtAll() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig(this.dataDir, unverifiedIdentity())) {
            //A profile keyed by the wire uuid exists: the pre-task-3 code loaded exactly
            //this file for every connection. An unverified identity must not touch it.
            final PlayerData wireKeyed = new PlayerData(ProfileKey.javaProfile(WIRE_UUID));
            wireKeyed.bookmarks.add(new Bookmark("Unverified bait", "bait.example", null, 1, 2));
            wireKeyed.accountBlob = "spoof-blob";
            rig.store.save(wireKeyed);

            rig.login();
            final S2CLoginGameProfilePacket response = rig.lobby.readOutbound();
            assertEquals(WIRE_UUID, response.uuid, "Even unverified connections keep the wire identity");
            rig.pump();

            final PlayerSession session = rig.handler.getSession();
            assertNull(session.playerData, "An unverified identity must not read any profile file");
            assertNull(session.profileKey);
            assertNull(session.lease, "An unverified identity must not hold a lease");
            assertEquals(0, session.generation);
            final PlayerData onDisk = rig.store.load(ProfileKey.javaProfile(WIRE_UUID));
            assertEquals("spoof-blob", onDisk.accountBlob, "The bait file must stay untouched on disk");
            assertEquals(1, onDisk.bookmarks.size());
        }
    }

    @Test
    void corruptProtectedProfileFailsLoudlyInsteadOfLoadingEmptyData() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig(this.dataDir, verifiedBedrockIdentity())) {
            //A corrupt profile must never degrade into empty data that a later save
            //could turn into a destroyed profile: the connection is dropped loudly.
            Files.createDirectories(new File(this.dataDir, "players/bedrock").toPath());
            Files.writeString(new File(this.dataDir, "players/bedrock/" + XUID + ".json").toPath(),
                    "{ this is not json", StandardCharsets.UTF_8);

            rig.login();
            rig.pump();

            final PlayerSession session = rig.handler.getSession();
            assertNull(session.playerData, "No empty data may appear for a corrupt profile");
            assertFalse(rig.lobby.isActive(), "The connection must be dropped loudly");
            assertEquals(1, rig.uncaught.get(), "The failure must be observable as an unexpected error");
            assertTrue(Files.readString(new File(this.dataDir, "players/bedrock/" + XUID + ".json").toPath(),
                    StandardCharsets.UTF_8).contains("not json"), "The corrupt file must stay untouched");
        }
    }

    @Test
    void corruptLinkIndexBlocksLinkDependentBedrockAccessLoudly() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig(this.dataDir, verifiedBedrockIdentity())) {
            //A corrupt index is never "no links": link-dependent access stays blocked
            //and the failure is reported instead of silently loading the bedrock profile.
            Files.createDirectories(new File(this.dataDir, "players").toPath());
            Files.writeString(new File(this.dataDir, "players/identity-links.json").toPath(),
                    "{ corrupt", StandardCharsets.UTF_8);

            rig.login();
            rig.pump();

            assertNull(rig.handler.getSession().playerData);
            assertFalse(rig.lobby.isActive(), "The connection must be dropped loudly");
            assertEquals(1, rig.uncaught.get());
        }
    }

    @Test
    void verifiedJavaJoinsNormallyEvenWhenTheLinkIndexIsCorrupt() throws Exception {
        ViaProxyTestConfig.init();
        //Healthy index: the verified Java identity resolves through the resolver
        //and loads the JAVA profile of the account that was actually verified.
        try (Rig rig = new Rig(this.dataDir, verifiedJavaIdentity())) {
            rig.seedJavaProfile("B's java bookmark");
            rig.login();
            final S2CLoginGameProfilePacket response = rig.lobby.readOutbound();
            assertEquals(WIRE_UUID, response.uuid, "Even verified Java connections keep the wire identity");
            rig.pump();
            final PlayerSession session = rig.handler.getSession();
            assertEquals(ProfileKey.javaProfile(JAVA_UUID), session.profileKey);
            assertEquals("B's java bookmark", session.playerData.bookmarks.get(0).name);
            assertTrue(rig.lobby.isActive());
            assertEquals(0, rig.uncaught.get());
        }

        //Corrupt index: the join must still succeed — a verified Java identity needs
        //no link data at all (plan §2.3); only link-dependent paths stay blocked.
        try (Rig rig = new Rig(this.dataDir, verifiedJavaIdentity())) {
            rig.seedJavaProfile("B's java bookmark");
            Files.createDirectories(new File(this.dataDir, "players").toPath());
            Files.writeString(new File(this.dataDir, "players/identity-links.json").toPath(),
                    "{ corrupt", StandardCharsets.UTF_8);

            rig.login();
            rig.pump();

            final PlayerSession session = rig.handler.getSession();
            assertEquals(ProfileKey.javaProfile(JAVA_UUID), session.profileKey);
            assertEquals("B's java bookmark", session.playerData.bookmarks.get(0).name);
            assertTrue(rig.lobby.isActive(), "A verified Java join must not be blocked by a corrupt link index");
            assertEquals(0, rig.uncaught.get(), "The join itself is not an error");
            assertNotNull(session.lease, "A protected profile is still held under a granted lease");
        }
    }

    // ---- plan §5: connection id survives switches, lease survives returns ----

    @Test
    void switchAndReturnReusesTheConnectionIdAndResumesTheSameSession() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig(this.dataDir, verifiedBedrockIdentity())) {
            rig.links.replace(0, Map.of(XUID, JAVA_UUID));
            rig.seedJavaProfile("B's java bookmark");
            rig.loginOn(rig.lobby);
            rig.lobby.readOutbound();
            rig.pump(rig.lobby);

            final PlayerSession first = rig.handler.getSession();
            assertNotNull(first.lease);
            final SessionLease lease = first.lease;
            rig.lobby.close(); // the backend visit: the lobby channel goes away, the c2p stays

            final EmbeddedChannel returnLobby = rig.newLobbyChannel();
            final LobbyServerHandler returnHandler = rig.newHandler();
            new LoginStateHandler(returnHandler, returnLobby).handle(
                    new C2SLoginHelloPacket(WIRE_NAME, null, null, null, WIRE_UUID));
            returnLobby.readOutbound();
            rig.pump(returnLobby);

            final PlayerSession returned = returnHandler.getSession();
            assertSame(first, returned, "The lobby return must resume the c2p-bound session");
            assertEquals(rig.connectionId, returned.connectionId, "The connection id must survive the switch");
            assertSame(lease, returned.lease, "The lease binds to the c2p: a return must not re-grant it");
            assertEquals(lease.generation(), returned.generation);
            assertSame(first, rig.registry.get(returned.connectionId));
            assertEquals("B's java bookmark", returned.playerData.bookmarks.get(0).name);
            returnLobby.close();
        }
    }

    // ---- plan §5/§7: the claim gates every profile access ----

    @Test
    void noProfileIsLoadedBeforeTheLeaseIsGranted() throws Exception {
        ViaProxyTestConfig.init();
        final List<String> events = new CopyOnWriteArrayList<>();
        final CompletableFuture<SessionLease> gate = new CompletableFuture<>();
        final SessionLeaseGranter blockingGranter = new SessionLeaseGranter() {
            @Override
            public CompletionStage<SessionLease> claim(final PlayerSession session, final ProfileKey profile,
                                                       final Set<UUID> accountUuids) {
                events.add("claim:" + profile);
                return gate;
            }

            @Override
            public boolean isCurrent(final SessionLease lease) {
                return true;
            }

            @Override
            public CompletionStage<Void> release(final SessionLease lease) {
                return CompletableFuture.completedFuture(null);
            }
        };
        final ProfileKey expectedKey = ProfileKey.javaProfile(JAVA_UUID);
        try (Rig rig = new Rig(this.dataDir, verifiedBedrockIdentity(), blockingGranter, new PlayerStore(new File(this.dataDir, "players")) {
            @Override
            public PlayerData load(final ProfileKey key) throws IOException {
                events.add("load:" + key);
                return super.load(key);
            }
        })) {
            rig.links.replace(0, Map.of(XUID, JAVA_UUID));
            rig.seedJavaProfile("B's java bookmark");

            rig.login();
            rig.lobby.readOutbound();
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (events.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(List.of("claim:" + expectedKey), events,
                    "The claim must happen before any profile read");
            assertNull(rig.handler.getSession().playerData, "No profile data may exist before the grant");

            gate.complete(new SessionLease() {
                @Override
                public UUID connectionId() {
                    return rig.handler.getSession().connectionId;
                }

                @Override
                public long generation() {
                    return 1;
                }
            });
            rig.pump();

            assertEquals(List.of("claim:" + expectedKey, "load:" + expectedKey), events,
                    "The profile load must follow the granted claim");
            assertEquals(expectedKey, rig.handler.getSession().profileKey);
            assertEquals("B's java bookmark", rig.handler.getSession().playerData.bookmarks.get(0).name);
        }
    }

    // ---- SessionRegistry release semantics ----

    @Test
    void staleOrForeignReleasesNeverRemoveTheCurrentHolder() {
        final SessionRegistry registry = new SessionRegistry();
        final AccountSessionCoordinator granter = new AccountSessionCoordinator(
                new PlayerStore(new File(this.dataDir, "players")), () -> null);
        final PlayerSession session = new PlayerSession(UUID.randomUUID(), "Holder");
        session.connectionId = UUID.randomUUID();
        registry.register(session);

        final SessionLease first = granter.claim(session, ProfileKey.javaProfile(UUID.randomUUID()), Set.of())
                .toCompletableFuture().join();
        session.generation = first.generation();
        assertTrue(registry.release(session, first.generation()), "The current holder+generation release removes the slot");
        assertNull(registry.get(session.connectionId));

        //A newer generation supersedes the old one: releasing the old generation must be a no-op.
        registry.register(session);
        final SessionLease second = granter.claim(session, ProfileKey.javaProfile(UUID.randomUUID()), Set.of())
                .toCompletableFuture().join();
        assertTrue(second.generation() > first.generation(), "Re-claiming the same connection must grow the generation");
        session.generation = second.generation();
        assertFalse(registry.release(session, first.generation()), "A stale generation must not release the slot");
        assertSame(session, registry.get(session.connectionId));

        final PlayerSession foreign = new PlayerSession(UUID.randomUUID(), "Foreign");
        foreign.connectionId = session.connectionId;
        assertFalse(registry.release(foreign, second.generation()), "A foreign session must not release the slot");
        assertSame(session, registry.get(session.connectionId));

        assertTrue(registry.release(session, second.generation()));
        assertNull(registry.get(session.connectionId));
    }

    // ---- rig ----------------------------------------------------------------

    private static ClientIdentity verifiedBedrockIdentity() {
        return ClientIdentity.verifiedBedrock(WIRE_UUID, WIRE_NAME, XUID, UUID.randomUUID(), UUID.randomUUID().toString());
    }

    private static ClientIdentity verifiedJavaIdentity() {
        return ClientIdentity.verifiedJava(WIRE_UUID, WIRE_NAME, JAVA_UUID);
    }

    private static ClientIdentity unverifiedIdentity() {
        return ClientIdentity.unverified(WIRE_UUID, WIRE_NAME);
    }

    /** The lobby-side test rig: c2p client channel + link + one LobbyServerHandler. */
    private final class Rig implements AutoCloseable {

        final UUID connectionId = UUID.randomUUID();
        final EmbeddedChannel client = new EmbeddedChannel();
        final EmbeddedChannel lobby = new EmbeddedChannel();
        final UUID link = UUID.randomUUID();
        final SessionRegistry registry = new SessionRegistry();
        final ExecutorService storage = Executors.newSingleThreadExecutor();
        final AtomicInteger uncaught = new AtomicInteger();
        final PlayerStore store;
        final IdentityLinkStore links;
        final SessionLeaseGranter granter;
        volatile LobbyServerHandler handler;
        /** Set when the rig built its own coordinator, so close() can stop its executors. */
        private final AccountSessionCoordinator ownedCoordinator;

        Rig(final File dataDir, final ClientIdentity identity) {
            this(dataDir, identity, null, new PlayerStore(new File(dataDir, "players")));
        }

        Rig(final File dataDir, final ClientIdentity identity, final SessionLeaseGranter granter) {
            this(dataDir, identity, granter, new PlayerStore(new File(dataDir, "players")));
        }

        Rig(final File dataDir, final ClientIdentity identity, final SessionLeaseGranter granter, final PlayerStore store) {
            this.store = store;
            this.links = new IdentityLinkStore(store.playersDir());
            if (granter != null) {
                this.granter = granter;
                this.ownedCoordinator = null;
            } else {
                // The task 4 production granter: the real serial coordinator.
                final AccountSessionCoordinator coordinator = new AccountSessionCoordinator(store, () -> null);
                this.granter = coordinator;
                this.ownedCoordinator = coordinator;
            }
            if (identity != null) {
                this.client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(identity);
                this.client.attr(CPAttributeKeys.PLAYER_IDENTITY).set(
                        new PlayerIdentity(identity.wireUuid(), identity.wireName()));
            }
            this.client.attr(CPAttributeKeys.CONNECTION_ID).set(this.connectionId);
            if (identity != null && identity.kind() == ClientIdentity.Kind.VERIFIED_BEDROCK) {
                dev.connectplus.testutil.TestClientIdentity.bedrock(this.client, identity);
            }
            final var proxy = new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), this.client);
            LobbyLink.register(this.link, proxy);
            this.lobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(this.link);
            this.handler = this.newHandler();
        }

        LobbyServerHandler newHandler() {
            final LobbyServerHandler handler = new LobbyServerHandler(Set.of(), this.registry,
                    new TokenStore(dataDir), this.store, this.links, this.granter,
                    new RecordingSwitchInitiator(), this.storage, this.uncaught);
            this.handler = handler;
            return handler;
        }

        void login() {
            this.loginOn(this.lobby);
        }

        /** A fresh lobby channel carrying this rig's link id. */
        EmbeddedChannel newLobbyChannel() {
            final EmbeddedChannel lobbyChannel = new EmbeddedChannel();
            lobbyChannel.attr(CPAttributeKeys.LOBBY_LINK_ID).set(this.link);
            return lobbyChannel;
        }

        void loginOn(final EmbeddedChannel lobbyChannel) {
            new LoginStateHandler(this.handler, lobbyChannel).handle(
                    new C2SLoginHelloPacket(WIRE_NAME, null, null, null, WIRE_UUID));
        }

        void seedBedrockProfile(final String bookmarkName) throws Exception {
            final PlayerData data = new PlayerData(ProfileKey.bedrockProfile(XUID));
            data.bookmarks.add(new Bookmark(bookmarkName, "a.example", null, 1, 2));
            this.store.save(data);
        }

        void seedJavaProfile(final String bookmarkName) throws Exception {
            final PlayerData data = new PlayerData(ProfileKey.javaProfile(JAVA_UUID));
            data.bookmarks.add(new Bookmark(bookmarkName, "b.example", null, 3, 4));
            this.store.save(data);
        }

        /**
         * Drains the storage executor and applies its event-loop follow-ups.
         * The task 4 claim → load chain is asynchronous (claim on the coordinator's
         * serial domain, load re-dispatched to storage, land via the event loop), so
         * a single drain is not enough; quiescence needs several rounds.
         */
        void pump(final EmbeddedChannel... channels) throws Exception {
            for (int round = 0; round < 16; round++) {
                if (this.ownedCoordinator != null) {
                    this.ownedCoordinator.awaitIdle().toCompletableFuture().get(3, TimeUnit.SECONDS);
                }
                this.storage.submit(() -> {
                }).get(3, TimeUnit.SECONDS);
                if (channels.length == 0) {
                    this.lobby.runPendingTasks();
                } else {
                    for (final EmbeddedChannel channel : channels) {
                        channel.runPendingTasks();
                    }
                }
            }
        }

        @Override
        public void close() {
            LobbyLink.unregister(this.link);
            this.storage.shutdownNow();
            if (this.ownedCoordinator != null) this.ownedCoordinator.shutdown();
            this.client.finishAndReleaseAll();
            this.lobby.finishAndReleaseAll();
        }

    }

}
