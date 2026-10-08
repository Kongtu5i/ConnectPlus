package dev.connectplus.session;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.config.CPConfig;
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
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4: the serial account-session coordinator (plan §5/§7) — exclusive
 * leases per resource set, displacement (顶号) of the current holder by a later
 * verified connection, and the bridge duplicate-candidate handshake. The
 * Review Focus scenarios this task owns are pinned here: late close/refresh
 * callbacks of a displaced connection must not delete the new lease or
 * overwrite the new profile (R1), only trusted paths can complete a
 * displacement (R4), and the lease invalidation API must support the task 7
 * unlink interleavings (R5).
 */
class AccountSessionCoordinatorTest {

    /** §5: the displacement message must reach the old client verbatim. */
    private static final String DISPLACEMENT_MESSAGE =
            "检测到异地登录，你的账号已在其他客户端登录，当前连接已断开。";

    private static final String XUID_A = "4242424242424";
    private static final UUID WIRE_A = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000aa");
    private static final String NAME_A = "BedrockA";
    private static final UUID WIRE_B = UUID.fromString("cccccccc-0000-4000-8000-0000000000cc");
    private static final UUID WIRE_B2 = UUID.fromString("cccccccc-0000-4000-8000-0000000000cd");
    private static final String NAME_B = "JavaB";
    private static final UUID JAVA_B = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000bb");

    @TempDir
    File dataDir;

    @Test
    void terminallyDeniedButStillActiveClaimantCannotDisplaceTheProfileOwner() throws Exception {
        final PlayerStore store = new PlayerStore(new File(dataDir, "players"));
        final AccountSessionCoordinator coordinator = new AccountSessionCoordinator(store, () -> null);
        final EmbeddedChannel oldChannel = new EmbeddedChannel();
        final EmbeddedChannel deniedChannel = new EmbeddedChannel();
        try {
            final PlayerSession owner = new PlayerSession(WIRE_B, "Owner");
            owner.connectionId = UUID.randomUUID();
            owner.c2pChannel = oldChannel;
            owner.profileKey = ProfileKey.javaProfile(JAVA_B);
            owner.playerData = new PlayerData(owner.profileKey);
            owner.lease = coordinator.claim(owner, owner.profileKey, Set.of(JAVA_B))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            final PlayerSession denied = new PlayerSession(WIRE_A, "Denied");
            denied.connectionId = UUID.randomUUID();
            denied.c2pChannel = deniedChannel;
            // Models the interval between terminal rejection and queued c2p closure.
            deniedChannel.attr(CPAttributeKeys.ACCESS_DENIED).set(true);
            assertTrue(deniedChannel.isActive());
            assertThrows(java.util.concurrent.ExecutionException.class, () ->
                    coordinator.claim(denied, owner.profileKey, Set.of(JAVA_B))
                            .toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertTrue(coordinator.isCurrent(owner.lease));
            assertTrue(oldChannel.isActive());
            assertFalse(owner.displaced);
        } finally {
            coordinator.shutdown();
            oldChannel.finishAndReleaseAll();
            deniedChannel.finishAndReleaseAll();
        }
    }

    // ---- plan §5: displacement order and single-holder invariant -----------

    @Test
    void javaBDisplacesTheLinkedBedrockHolderAndOnlyTheNewLeaseRemains() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig()) {
            rig.links.replace(0, Map.of(XUID_A, JAVA_B));
            rig.seedJavaProfile("B's bookmark");

            final Conn a = rig.join(verifiedBedrockA(null));
            assertNotNull(a.session().lease, "The linked bedrock holder starts with a lease");
            assertTrue(rig.coordinator.isCurrent(a.session().lease));

            final Conn b = rig.join(verifiedJavaB());

            final SessionLease newLease = b.session().lease;
            assertNotNull(newLease, "The later verified connection must be granted the lease");
            assertTrue(rig.coordinator.isCurrent(newLease));
            assertFalse(rig.coordinator.isCurrent(a.session().lease), "The displaced lease must be invalid");
            assertEquals(List.of(b.connectionId), rig.currentHolders(),
                    "Exactly one connection may hold the profile after the displacement");
            assertEquals(ProfileKey.javaProfile(JAVA_B), b.session().profileKey);
            assertEquals("B's bookmark", b.session().playerData.bookmarks.get(0).name,
                    "The new holder loads the latest profile after the grant");
            // §5 step 3: the exact displacement message + closed old c2p.
            assertEquals(List.of(a.connectionId), rig.displacement.evicted);
            assertEquals(List.of(DISPLACEMENT_MESSAGE), rig.displacement.messages,
                    "The displacement message must be delivered verbatim");
            assertFalse(a.client.isActive(), "The displaced client's c2p must be closed");
            // The registry keeps the new slot; the old slot goes with the old channel.
            a.lobby.close();
            rig.pump();
            assertNull(rig.registry.get(a.connectionId));
            assertSame(b.session(), rig.registry.get(b.connectionId));
        }
    }

    @Test
    void theLinkedBedrockDisplacesAJavaHolderToo() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig()) {
            rig.seedJavaProfile("B's bookmark");
            final Conn b = rig.join(verifiedJavaB());
            assertTrue(rig.coordinator.isCurrent(b.session().lease));

            rig.links.replace(0, Map.of(XUID_A, JAVA_B));
            final Conn a = rig.join(verifiedBedrockA(null));

            assertTrue(rig.coordinator.isCurrent(a.session().lease), "The incoming verified bedrock takes over");
            assertFalse(rig.coordinator.isCurrent(b.session().lease));
            assertEquals(List.of(a.connectionId), rig.currentHolders());
            assertEquals(List.of(b.connectionId), rig.displacement.evicted);
            assertEquals(List.of(DISPLACEMENT_MESSAGE), rig.displacement.messages);
            assertFalse(b.client.isActive());
            assertEquals("B's bookmark", a.session().playerData.bookmarks.get(0).name);
        }
    }

    @Test
    void aSecondJavaClientForTheSameAccountDisplacesTheFirst() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig()) {
            rig.seedJavaProfile("B's bookmark");
            final Conn first = rig.join(verifiedJavaB());
            final Conn second = rig.join(verifiedJavaB2());

            assertTrue(rig.coordinator.isCurrent(second.session().lease));
            assertFalse(rig.coordinator.isCurrent(first.session().lease));
            assertEquals(List.of(second.connectionId), rig.currentHolders());
            assertEquals(List.of(first.connectionId), rig.displacement.evicted);
            assertEquals(List.of(DISPLACEMENT_MESSAGE), rig.displacement.messages);
        }
    }

    // ---- Review Focus 1: late callbacks of the displaced connection --------

    @Test
    void theDisplacedConnectionsLateCallbacksCannotTouchTheNewLeaseOrProfile() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig()) {
            rig.seedJavaProfile("B's bookmark");
            final Conn a = rig.join(verifiedJavaB());
            final Conn b = rig.join(verifiedJavaB2());
            assertTrue(rig.coordinator.isCurrent(b.session().lease));
            assertFalse(rig.coordinator.isCurrent(a.session().lease));

            // Late close callbacks of the displaced connection, arriving after the
            // grant: the c2p close hook's lease release and the lobby channel's
            // handlerRemoved (registry release + wipe-save paths).
            final SessionLease oldLease = a.session().lease;
            await(rig.coordinator.release(oldLease));
            a.lobby.close();
            rig.pump();

            assertTrue(rig.coordinator.isCurrent(b.session().lease),
                    "The old close callbacks must not delete the new lease");
            assertSame(b.session(), rig.registry.get(b.connectionId),
                    "The old close callbacks must not remove the new connection's slot");
            assertNull(rig.registry.get(a.connectionId));
            assertEquals("B's bookmark",
                    rig.store.load(ProfileKey.javaProfile(JAVA_B)).bookmarks.get(0).name,
                    "The old late callbacks must not overwrite the profile the new holder owns");
            assertEquals(1, rig.currentHolders().size());
        }
    }

    // ---- plan §5: same IP, unauthenticated, trust boundary -----------------

    @Test
    void anOldLobbyClaimCompletingAfterAReturnCannotInvalidateTheReturnedLease() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig()) {
            rig.seedJavaProfile("B's bookmark");
            Conn previous = rig.join(verifiedJavaB());
            rig.links.replace(0, Map.of(XUID_A, JAVA_B));
            CompletableFuture<Void> exit = new CompletableFuture<>();
            rig.displacement.gates.put(previous.connectionId, exit);
            Conn incoming = rig.joinWithoutPump(verifiedBedrockA(null));
            rig.pump();
            assertTrue(rig.displacement.evicted.contains(previous.connectionId));
            assertNull(incoming.session().lease, "The first lobby is waiting for displacement to finish");

            LobbyServerHandler returned = incoming.newReturnHandler();
            new LoginStateHandler(returned, incoming.returnLobby).handle(
                    new C2SLoginHelloPacket(NAME_A, null, null, null, WIRE_A));
            rig.pump();
            SessionLease current = incoming.session().lease;
            assertNotNull(current);
            assertTrue(rig.coordinator.isCurrent(current));
            exit.complete(null);
            rig.pump();
            assertSame(current, incoming.session().lease);
            assertTrue(rig.coordinator.isCurrent(current), "The late claim from the old lobby must not supersede the new lease");
            assertEquals("B's bookmark", incoming.session().playerData.bookmarks.get(0).name);
            assertEquals(0, rig.uncaught.get());
        }
    }

    @Test
    void theSameAddressIsNeverExemptFromDisplacement() throws Exception {
        ViaProxyTestConfig.init();
        // Both embedded connections present the identical (default embedded) address:
        // displacement arbitrates by verified identity, never by IP exemption (§1.2).
        try (Rig rig = new Rig()) {
            rig.seedJavaProfile("B's bookmark");
            final Conn first = rig.join(verifiedJavaB());
            final Object firstAddress = first.client.localAddress();
            final Conn second = rig.join(verifiedJavaB2());
            final Object secondAddress = second.client.localAddress();
            // The displaced first client is already closed here, so capture the
            // addresses before the assertion: both embedded channels report the
            // same "embedded" address — displacement arbitrates by verified
            // identity, never by IP exemption (§1.2).
            assertEquals(firstAddress, secondAddress,
                    "the test premise: both connections come from the same address");
            assertTrue(rig.coordinator.isCurrent(second.session().lease), "The same-address join displaces too");
            assertFalse(rig.coordinator.isCurrent(first.session().lease));
            assertEquals(List.of(DISPLACEMENT_MESSAGE), rig.displacement.messages);
        }
    }

    @Test
    void anUnauthenticatedConnectionCannotDisplaceTheCurrentHolder() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig()) {
            rig.seedJavaProfile("B's bookmark");
            final Conn holder = rig.join(verifiedJavaB());
            assertTrue(rig.coordinator.isCurrent(holder.session().lease));

            // Claims B's wire uuid and name but was never authenticated (plan §2:
            // a plain login event is not verification) — it must not displace.
            final Conn impostor = rig.join(ClientIdentity.unverified(JAVA_B, NAME_B));

            assertTrue(rig.coordinator.isCurrent(holder.session().lease),
                    "An unverified join must never displace the current holder");
            assertTrue(rig.displacement.evicted.isEmpty(), "No displacement may be triggered");
            assertNull(impostor.session().playerData, "The unverified connection gets no protected path");
            assertNull(impostor.session().lease);
            assertEquals(0, impostor.session().generation);
        }
    }

    // ---- plan §5: save failure and exit confirmation -----------------------

    @Test
    void aSaveFailureDeniesTheNewConnectionWithoutEverCreatingTwoWritableSessions() throws Exception {
        ViaProxyTestConfig.init();
        final AtomicBoolean failSaves = new AtomicBoolean();
        final PlayerStore failingStore = new PlayerStore(new File(this.dataDir, "players")) {
            @Override
            public void save(final ProfileKey key, final PlayerData data) throws IOException {
                if (failSaves.get() && key.equals(ProfileKey.javaProfile(JAVA_B))) {
                    throw new IOException("disk full (test)");
                }
                super.save(key, data);
            }
        };
        try (Rig rig = new Rig(failingStore)) {
            rig.seedJavaProfile("B's bookmark");
            final Conn a = rig.join(verifiedJavaB());
            assertTrue(rig.coordinator.isCurrent(a.session().lease));
            failSaves.set(true);

            final Conn b = rig.join(verifiedJavaB2());

            assertNull(b.session().playerData, "The new connection must get no profile access");
            assertFalse(b.lobby.isActive(), "The new connection must be dropped loudly (clear failure)");
            assertTrue(rig.uncaught.get() >= 1, "The failure must be observable");
            assertFalse(rig.coordinator.isCurrent(a.session().lease),
                    "The frozen old holder keeps no write permission either");
            assertFalse(rig.coordinator.isCurrent(b.session().lease));
            assertTrue(rig.currentHolders().isEmpty(), "Never two writable sessions");
            assertFalse(rig.displacement.messages.contains(DISPLACEMENT_MESSAGE),
                    "Nobody took over — the displacement message must not be sent");
            assertFalse(a.client.isActive(), "The frozen old holder is closed instead of left as a zombie");
        }
    }

    @Test
    void aHungSavePhaseDeniesTheNewConnectionBoundedAndTheCoordinatorRecovers() throws Exception {
        ViaProxyTestConfig.init();
        // Review-fix pin: the save phase is bounded like the exit confirmation. A
        // save that never completes must (1) fail the claim within a bounded window
        // instead of hanging it forever, (2) leave no granted lease and no holder,
        // (3) rotate the stuck IO thread away so the NEXT displacement still works —
        // no permanent degradation of the mechanism.
        final CountDownLatch hangFirstSave = new CountDownLatch(1);
        final AtomicBoolean hangArmed = new AtomicBoolean();
        final PlayerStore hangingStore = new PlayerStore(new File(this.dataDir, "players")) {
            @Override
            public void save(final ProfileKey key, final PlayerData data) throws IOException {
                // Only the DISPLACEMENT save of the first join hangs (the gate is
                // armed after the holder is in place); the rig's profile seeding
                // must go through untouched.
                if (hangArmed.get()) {
                    hangArmed.set(false); // exactly one hung save
                    try {
                        // Simulate a hung disk: block far beyond the save budget.
                        hangFirstSave.await(60, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                super.save(key, data);
            }
        };
        try (Rig rig = new Rig(hangingStore)) {
            rig.seedJavaProfile("B's bookmark");
            final Conn a = rig.join(verifiedJavaB());
            assertTrue(rig.coordinator.isCurrent(a.session().lease));
            hangArmed.set(true);

            final long start = System.nanoTime();
            final Conn b = rig.joinWithoutPump(verifiedJavaB2());
            // The claim must settle long before the 60s hang ends: the save phase
            // budget (2s) + eviction budget (5s) bound the whole machine. Wait for
            // the stalled claim to be dropped (the hang is armed before the join,
            // so the displacement save hangs immediately).
            awaitUntil(() -> !b.lobby.isActive(),
                    "the stalled claim must be terminated within the bounded budget", 12, TimeUnit.SECONDS);
            final long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMillis < 15_000,
                    "the claim failed bounded, not forever (took " + elapsedMillis + "ms)");

            assertNull(b.session().playerData, "The new connection must get no profile access");
            assertFalse(b.lobby.isActive(), "The stalled claim is dropped loudly");
            assertTrue(rig.uncaught.get() >= 1, "The failure must be observable");
            assertFalse(rig.coordinator.isCurrent(a.session().lease),
                    "The old holder keeps no write permission after the timed-out save either");
            assertTrue(rig.currentHolders().isEmpty(), "No lease granted out of a stalled chain; holders clean");
            assertFalse(rig.displacement.messages.contains(DISPLACEMENT_MESSAGE),
                    "Nobody took over — the displacement message must not be sent");
            assertFalse(a.client.isActive(), "The old holder is closed even on a hung save");

            // Recovery: unblock the hung save (its thread finishes into a dead
            // future) and run a third connection — the coordinator must still
            // displace and grant normally despite the rotated IO executor.
            hangFirstSave.countDown();
            final Conn c = rig.join(verifiedJavaB3());
            assertTrue(rig.coordinator.isCurrent(c.session().lease),
                    "A subsequent claim works: no permanent degradation");
            assertEquals("B's bookmark", c.session().playerData.bookmarks.get(0).name);
            assertFalse(a.client.isActive(), "the displaced holder stays out");
        }
    }

    @Test
    void theGrantWaitsForTheConfirmedExitAndTheOldLeaseIsInvalidatedBeforeTheClose() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig()) {
            rig.seedJavaProfile("B's bookmark");
            final Conn a = rig.join(verifiedJavaB());
            final CompletableFuture<Void> exitGate = new CompletableFuture<>();
            rig.displacement.gates.put(a.connectionId, exitGate);

            final Conn b = rig.joinWithoutPump(verifiedJavaB2());

            // Mid-displacement: the old lease is already invalid (§5 step 3 happens
            // before the close is confirmed), the new connection has nothing yet.
            awaitUntil(() -> !rig.coordinator.isCurrent(a.session().lease),
                    "the old lease must be invalidated before the exit is confirmed");
            assertNull(b.session().lease, "No grant before the old client's exit is confirmed");
            assertNull(b.session().playerData, "No profile access before the grant");

            exitGate.complete(null); // the old client's exit completes
            rig.pump();

            assertTrue(rig.coordinator.isCurrent(b.session().lease), "The grant follows the confirmed exit");
            assertEquals("B's bookmark", b.session().playerData.bookmarks.get(0).name);
        }
    }

    // ---- plan §5: repeated displacement ------------------------------------

    @Test
    void threeConsecutiveNewConnectionsLeaveExactlyTheLastLease() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig()) {
            rig.seedJavaProfile("B's bookmark");
            final Conn first = rig.join(verifiedJavaB());
            final Conn second = rig.join(verifiedJavaB2());
            final Conn third = rig.join(verifiedJavaB3());

            assertTrue(rig.coordinator.isCurrent(third.session().lease));
            assertFalse(rig.coordinator.isCurrent(first.session().lease));
            assertFalse(rig.coordinator.isCurrent(second.session().lease));
            assertEquals(List.of(third.connectionId), rig.currentHolders());
            assertEquals(List.of(first.connectionId, second.connectionId), rig.displacement.evicted);
            assertEquals(List.of(DISPLACEMENT_MESSAGE, DISPLACEMENT_MESSAGE), rig.displacement.messages);
            assertFalse(first.client.isActive());
            assertFalse(second.client.isActive());
            assertTrue(third.client.isActive());
        }
    }

    // ---- bridge duplicate-candidate coordination (§4.3) ---------------------

    @Test
    void theSameXuidCandidateReplacesAManagedOldSessionAndAnswersReadyOnlyAfterTheSafeExit()
            throws Exception {
        ViaProxyTestConfig.init();
        final boolean geyserEnabled = CPConfig.GeyserSupport.enabled;
        CPConfig.GeyserSupport.enabled = true;
        try (Rig rig = new Rig()) {
            final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
            final RecordingProvider provider = new RecordingProvider();
            final String epoch = UUID.randomUUID().toString();
            final Map<String, Object> registered = endpoint.register(descriptor(epoch), provider);
            assertEquals("REGISTERED", registered.get("status"));
            final String registrationId = (String) registered.get("registrationId");
            endpoint.setDuplicateCandidateCoordinator(rig.coordinator);
            rig.attachEndpoint(endpoint);

            final String oldSessionId = UUID.randomUUID().toString();
            final String newSessionId = UUID.randomUUID().toString();

            // An old session ConnectPlus never managed must keep Geyser's own rejection
            // behaviour, answered as DENIED/OLD_SESSION_NOT_MANAGED (spec §4.3).
            final Map<String, Object> unmanagedAnswer = await(endpoint.handleEvent(registrationId,
                    duplicateCandidateEvent(epoch, XUID_A, UUID.randomUUID().toString(), newSessionId)));
            assertEquals("DENIED", unmanagedAnswer.get("status"));
            assertEquals("OLD_SESSION_NOT_MANAGED", unmanagedAnswer.get("reasonCode"));
            assertTrue(rig.displacement.evicted.isEmpty());

            // The managed old session: a verified bedrock holder of the XUID profile.
            rig.seedBedrockProfile("A's own bookmark");
            final Conn a = rig.join(verifiedBedrockA(oldSessionId));
            assertTrue(rig.coordinator.isCurrent(a.session().lease));
            rig.registerManagedBridgeSession(endpoint, provider, XUID_A, oldSessionId, a.connectionId);

            final Map<String, Object> answer = await(endpoint.handleEvent(registrationId,
                    duplicateCandidateEvent(epoch, XUID_A, oldSessionId, newSessionId)));

            assertEquals("READY", answer.get("status"),
                    "READY only after the old session completed its safe exit");
            assertEquals(1, ((Number) answer.get("protocolVersion")).intValue());
            assertEquals(epoch, answer.get("providerEpoch"));
            assertEquals(newSessionId, answer.get("newBridgeSessionId"));
            // The old bedrock client is notified through the bridge DISCONNECT op with
            // the verbatim message, and the old c2p is closed.
            final RecordingProvider.Request disconnect = provider.awaitRequest("DISCONNECT");
            assertEquals(oldSessionId, disconnect.request().get("bridgeSessionId"));
            assertEquals("REPLACED", disconnect.request().get("reasonCode"));
            assertEquals(DISPLACEMENT_MESSAGE, disconnect.request().get("message"));
            assertEquals(List.of(DISPLACEMENT_MESSAGE), rig.displacement.messages);
            assertFalse(a.client.isActive(), "The old c2p must be closed");
            assertFalse(rig.coordinator.isCurrent(a.session().lease));

            // The new c2p still goes through the normal RESOLVE + claim path (§4.3):
            final Conn newBedrock = rig.join(verifiedBedrockA(newSessionId));
            assertTrue(rig.coordinator.isCurrent(newBedrock.session().lease),
                    "The arriving candidate claims the freed profile through the normal flow");
            assertEquals(ProfileKey.bedrockProfile(XUID_A), newBedrock.session().profileKey);
        } finally {
            CPConfig.GeyserSupport.enabled = geyserEnabled;
        }
    }

    private static Map<String, Object> descriptor(final String epoch) {
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("protocolVersion", 1);
        d.put("providerId", BedrockBridgeEndpoint.PROVIDER_ID);
        d.put("providerEpoch", epoch);
        d.put("bridgeVersion", "1.0.0");
        d.put("geyserVersion", "2.8.2");
        d.put("viaproxyVersion", "3.4.13");
        d.put("capabilities", List.of("verified-xuid", "exact-channel-binding",
                "targeted-disconnect", "authenticated-duplicate-admission"));
        return d;
    }

    private static Map<String, Object> duplicateCandidateEvent(final String epoch, final String xuid,
                                                               final String oldSessionId, final String newSessionId) {
        final Map<String, Object> event = new LinkedHashMap<>();
        event.put("op", "VERIFIED_DUPLICATE_CANDIDATE");
        event.put("protocolVersion", 1);
        event.put("providerEpoch", epoch);
        event.put("xuid", xuid);
        event.put("oldBridgeSessionId", oldSessionId);
        event.put("newBridgeSessionId", newSessionId);
        return event;
    }

    /** Minimal provider stub recording the requests ConnectPlus sends. */
    private static final class RecordingProvider
            implements Function<Map<String, Object>, CompletionStage<Map<String, Object>>> {

        record Request(String op, Map<String, Object> request) {
        }

        final List<Request> requests = new CopyOnWriteArrayList<>();
        volatile String verifiedXuid;
        volatile String verifiedSessionId;

        @Override
        public CompletionStage<Map<String, Object>> apply(final Map<String, Object> request) {
            final String op = (String) request.get("op");
            this.requests.add(new Request(op, request));
            final Map<String, Object> response = new LinkedHashMap<>();
            response.put("protocolVersion", 1);
            response.put("providerEpoch", request.get("providerEpoch"));
            if (request.containsKey("connectionId")) response.put("connectionId", request.get("connectionId"));
            if (request.containsKey("bridgeSessionId")) response.put("bridgeSessionId", request.get("bridgeSessionId"));
            switch (op == null ? "" : op) {
                case "RESOLVE" -> {
                    response.put("status", "VERIFIED");
                    response.put("xuid", this.verifiedXuid);
                    response.put("bedrockUsername", "BedrockPlayer");
                    response.put("bridgeSessionId", this.verifiedSessionId);
                }
                case "DISCONNECT" -> response.put("status", "CLOSED");
                default -> response.put("status", "UNAVAILABLE");
            }
            return CompletableFuture.completedFuture(response);
        }

        Request awaitRequest(final String op) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                for (final Request request : this.requests) {
                    if (request.op().equals(op)) return request;
                }
                Thread.sleep(10);
            }
            fail("no " + op + " request arrived");
            return null;
        }
    }

    // ---- resource-set arbitration (direct coordinator calls) ----------------

    @Test
    void aDifferentProfileUsingTheSameJavaAccountDisplacesTheProfileHolder() throws Exception {
        // §5: using C's credential under another profile still occupies C's single
        // account — the account resource displaces the holder that uses it.
        try (Rig rig = new Rig()) {
            final PlayerSession profileHolder = rig.bareSession("profile-holder");
            profileHolder.lease = await(rig.coordinator.claim(profileHolder,
                    ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B)));
            assertTrue(rig.coordinator.isCurrent(profileHolder.lease));

            final PlayerSession crossProfile = rig.bareSession("cross-profile");
            crossProfile.lease = await(rig.coordinator.claim(crossProfile,
                    ProfileKey.bedrockProfile("7777"), Set.of(JAVA_B)));

            assertTrue(rig.coordinator.isCurrent(crossProfile.lease),
                    "The second profile's claim over the shared account must win");
            assertFalse(rig.coordinator.isCurrent(profileHolder.lease), "The first holder must be displaced");
            assertEquals(List.of(crossProfile.connectionId), rig.currentHolders());
            assertEquals(List.of(profileHolder.connectionId), rig.displacement.evicted);
            assertEquals(List.of(DISPLACEMENT_MESSAGE), rig.displacement.messages);
        }
    }

    @Test
    void multiResourceCompetitionEndsWithSingleWritersAndNoDeadlock() throws Exception {
        final ProfileKey profile1 = ProfileKey.javaProfile(JAVA_B);
        final ProfileKey profile2 = ProfileKey.javaProfile(UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000b2"));
        final UUID sharedAccount = UUID.fromString("dddddddd-0000-4000-8000-0000000000dd");
        final String profile1Resource = "profile:" + profile1;
        final String profile2Resource = "profile:" + profile2;
        final String accountResource = "account:" + sharedAccount;
        try (Rig rig = new Rig()) {
            final List<PlayerSession> claimants = List.of(
                    rig.bareSession("c1"), rig.bareSession("c2"), rig.bareSession("c3"), rig.bareSession("c4"));
            final List<Set<String>> wanted = List.of(
                    Set.of(profile1Resource),
                    Set.of(profile2Resource),
                    Set.of(profile1Resource, accountResource),
                    Set.of(profile2Resource, accountResource));
            final List<CompletableFuture<SessionLease>> stages = new ArrayList<>();
            final CountDownLatch atClaim = new CountDownLatch(claimants.size());
            final ExecutorService pool = Executors.newFixedThreadPool(claimants.size());
            try {
                for (int i = 0; i < claimants.size(); i++) {
                    final PlayerSession claimant = claimants.get(i);
                    final boolean wantsProfile1 = wanted.get(i).contains(profile1Resource);
                    final Set<UUID> accounts = wanted.get(i).contains(accountResource) ? Set.of(sharedAccount) : Set.of();
                    final ProfileKey profile = wantsProfile1 ? profile1 : profile2;
                    stages.add(CompletableFuture.supplyAsync(() -> {
                        atClaim.countDown();
                        return rig.coordinator.claim(claimant, profile, accounts);
                    }, pool).thenCompose(stage -> stage.toCompletableFuture()));
                }
                assertTrue(atClaim.await(5, TimeUnit.SECONDS), "all claim threads reached their claim");
                // No deadlock: every claim must complete well within the budget.
                for (final CompletableFuture<SessionLease> stage : stages) {
                    assertDoesNotThrow(() -> stage.get(15, TimeUnit.SECONDS), "a claim deadlocked");
                }
            } finally {
                pool.shutdownNow();
            }
            final List<SessionLease> leases = new ArrayList<>();
            for (final CompletableFuture<SessionLease> stage : stages) leases.add(stage.join());

            // No double write access: no two CURRENT leases share any resource.
            for (int i = 0; i < leases.size(); i++) {
                for (int j = i + 1; j < leases.size(); j++) {
                    if (rig.coordinator.isCurrent(leases.get(i)) && rig.coordinator.isCurrent(leases.get(j))) {
                        for (final String resource : wanted.get(i)) {
                            assertFalse(wanted.get(j).contains(resource),
                                    "two concurrent leases both hold " + resource);
                        }
                    }
                }
            }
            assertTrue(leases.stream().anyMatch(rig.coordinator::isCurrent), "someone holds the final resources");
        }
    }

    // ---- carry-forward pins from the task 3 review --------------------------

    @Test
    void reclaimingByTheSameConnectionSupersedesTheOrphanedLeaseInsteadOfDoubleGranting() throws Exception {
        try (Rig rig = new Rig()) {
            final PlayerSession session = rig.bareSession("re-claimer");
            session.lease = await(rig.coordinator.claim(session,
                    ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B)));
            final SessionLease first = session.lease;
            assertTrue(rig.coordinator.isCurrent(first));

            session.lease = await(rig.coordinator.claim(session,
                    ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B)));
            final SessionLease second = session.lease;

            assertTrue(second.generation() > first.generation(), "A re-grant grows the generation");
            assertFalse(rig.coordinator.isCurrent(first),
                    "The orphaned first lease must be superseded, never double-granted");
            assertTrue(rig.coordinator.isCurrent(second));
            assertEquals(List.of(session.connectionId), rig.currentHolders());
        }
    }

    @Test
    void aLobbyChannelClosingAfterTheReturnMustNotRemoveTheFreshlyRegisteredSlot() throws Exception {
        ViaProxyTestConfig.init();
        try (Rig rig = new Rig()) {
            rig.links.replace(0, Map.of(XUID_A, JAVA_B));
            rig.seedJavaProfile("B's bookmark");
            final Conn a = rig.join(verifiedBedrockA(null));
            final SessionLease lease = a.session().lease;

            // The lobby return: a second lobby channel re-registers the same session.
            final LobbyServerHandler returnHandler = a.newReturnHandler();
            new LoginStateHandler(returnHandler, a.returnLobby).handle(
                    new C2SLoginHelloPacket(NAME_A, null, null, null, WIRE_A));
            a.returnLobby.readOutbound();
            rig.pump(a.returnLobby);
            assertSame(a.session(), returnHandler.getSession());

            // NOW the old lobby channel closes — its handlerRemoved runs late and
            // must not remove the freshly re-registered slot (task 3 carry-forward).
            a.lobby.close();
            rig.pump();

            assertSame(a.session(), rig.registry.get(a.connectionId),
                    "The late close of the superseded lobby channel must be a no-op");
            assertTrue(rig.coordinator.isCurrent(lease), "The lease binds the c2p and survives the return");
            assertTrue(a.returnLobby.isActive());
            a.returnLobby.close();
        }
    }

    @Test
    void staleReleasesThroughTheCoordinatorAreNoOps() throws Exception {
        try (Rig rig = new Rig()) {
            final PlayerSession session = rig.bareSession("stale-release");
            final SessionLease lease = await(rig.coordinator.claim(session,
                    ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B)));
            assertTrue(rig.coordinator.isCurrent(lease));

            await(rig.coordinator.release(lease));
            assertFalse(rig.coordinator.isCurrent(lease));
            assertTrue(rig.currentHolders().isEmpty());

            // A second release of the same (now stale) lease must change nothing.
            await(rig.coordinator.release(lease));
            assertTrue(rig.currentHolders().isEmpty());

            // The freed resource can be claimed again by another connection.
            final PlayerSession next = rig.bareSession("next-holder");
            final SessionLease nextLease = await(rig.coordinator.claim(next,
                    ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B)));
            assertTrue(rig.coordinator.isCurrent(nextLease));
        }
    }

    // ---- identities ---------------------------------------------------------

    private static ClientIdentity verifiedBedrockA(@Nullable final String bridgeSessionId) {
        return ClientIdentity.verifiedBedrock(WIRE_A, NAME_A, XUID_A, UUID.randomUUID(),
                bridgeSessionId != null ? bridgeSessionId : UUID.randomUUID().toString());
    }

    private static ClientIdentity verifiedJavaB() {
        return ClientIdentity.verifiedJava(WIRE_B, NAME_B, JAVA_B);
    }

    private static ClientIdentity verifiedJavaB2() {
        return ClientIdentity.verifiedJava(WIRE_B2, NAME_B + "2", JAVA_B);
    }

    private static ClientIdentity verifiedJavaB3() {
        return ClientIdentity.verifiedJava(UUID.fromString("cccccccc-0000-4000-8000-0000000000ce"),
                NAME_B + "3", JAVA_B);
    }

    private static <T> T await(final CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    private static void awaitUntil(final Supplier<Boolean> condition, final String message) throws InterruptedException {
        awaitUntil(condition, message, 5, TimeUnit.SECONDS);
    }

    private static void awaitUntil(final Supplier<Boolean> condition, final String message,
                                   final long timeout, final TimeUnit unit) throws InterruptedException {
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!Boolean.TRUE.equals(condition.get())) {
            if (System.nanoTime() > deadline) fail(message + " (not observed within " + timeout + "s)");
            Thread.sleep(10);
        }
    }

    // ---- rig ----------------------------------------------------------------

    private final class Rig implements AutoCloseable {

        final PlayerStore store;
        final IdentityLinkStore links = new IdentityLinkStore(new File(dataDir, "players"));
        final TokenStore tokens = new TokenStore(dataDir);
        final SessionRegistry registry = new SessionRegistry();
        final ExecutorService storage = Executors.newSingleThreadExecutor();
        final AtomicInteger uncaught = new AtomicInteger();
        final RecordingDisplacement displacement;
        final AccountSessionCoordinator coordinator;
        final Set<io.netty.channel.Channel> acceptedChannels = ConcurrentHashMap.newKeySet();
        final List<Conn> connections = new ArrayList<>();
        final List<PlayerSession> bareSessions = new CopyOnWriteArrayList<>();
        final List<EmbeddedChannel> bareChannels = new CopyOnWriteArrayList<>();
        volatile BedrockBridgeEndpoint rigEndpoint;

        Rig() {
            this(new PlayerStore(new File(dataDir, "players")));
        }

        Rig(final PlayerStore store) {
            this.store = store;
            this.displacement = new RecordingDisplacement();
            this.coordinator = new AccountSessionCoordinator(store, () -> this.rigEndpoint, this.displacement);
        }

        void attachEndpoint(final BedrockBridgeEndpoint endpoint) {
            this.rigEndpoint = endpoint;
        }

        /** Registers a bridge session as managed, exactly as a VERIFIED resolve would. */
        void registerManagedBridgeSession(final BedrockBridgeEndpoint endpoint, final RecordingProvider provider,
                                          final String xuid, final String bridgeSessionId,
                                          final UUID connectionId) throws Exception {
            provider.verifiedXuid = xuid;
            provider.verifiedSessionId = bridgeSessionId;
            final EmbeddedChannel resolveChannel = this.connections.stream()
                    .filter(conn -> conn.connectionId.equals(connectionId)).findFirst().orElseThrow().client;
            final BedrockBridgeEndpoint.ResolveResult result = endpoint
                    .resolve(resolveChannel, null, null, connectionId, UUID.randomUUID(), "Resolver")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(BedrockBridgeEndpoint.ResolveStatus.VERIFIED, result.status());
            assertTrue(endpoint.isSessionManaged(bridgeSessionId));
        }

        Conn join(final ClientIdentity identity) throws Exception {
            final Conn conn = new Conn(this, identity);
            this.connections.add(conn);
            conn.login();
            this.pump();
            return conn;
        }

        Conn joinWithoutPump(final ClientIdentity identity) {
            final Conn conn = new Conn(this, identity);
            this.connections.add(conn);
            conn.login();
            return conn;
        }

        /** A session for direct coordinator calls (no lobby flow). */
        PlayerSession bareSession(final String label) {
            final PlayerSession session = new PlayerSession(UUID.randomUUID(), label);
            session.connectionId = UUID.randomUUID();
            final EmbeddedChannel c2p = new EmbeddedChannel();
            c2p.attr(CPAttributeKeys.CONNECTION_ID).set(session.connectionId);
            session.c2pChannel = c2p;
            this.bareSessions.add(session);
            this.bareChannels.add(c2p);
            return session;
        }

        List<UUID> currentHolders() {
            final List<UUID> holders = new ArrayList<>();
            for (final Conn conn : this.connections) {
                final PlayerSession session = conn.session();
                if (session != null && session.lease != null && this.coordinator.isCurrent(session.lease)) {
                    holders.add(session.connectionId);
                }
            }
            for (final PlayerSession session : this.bareSessions) {
                if (session.lease != null && this.coordinator.isCurrent(session.lease)) {
                    holders.add(session.connectionId);
                }
            }
            return holders;
        }

        void seedJavaProfile(final String bookmarkName) throws Exception {
            final PlayerData data = new PlayerData(ProfileKey.javaProfile(JAVA_B));
            data.bookmarks.add(new Bookmark(bookmarkName, "b.example", null, 3, 4));
            this.store.save(data);
        }

        void seedBedrockProfile(final String bookmarkName) throws Exception {
            final PlayerData data = new PlayerData(ProfileKey.bedrockProfile(XUID_A));
            data.bookmarks.add(new Bookmark(bookmarkName, "a.example", null, 1, 2));
            this.store.save(data);
        }

        /**
         * Drains the storage executor and applies the event-loop follow-ups of every
         * connection's channel. Must be skipped while a displacement gate holds a
         * claim open (the storage thread parks inside the claim wait).
         */
        void pump(final EmbeddedChannel... extraChannels) throws Exception {
            // The load chain is fully asynchronous now (claim on the coordinator
            // domain, load re-dispatched to storage, land via the event loop), so a
            // fixed small number of drains is not enough: a grant completing on the
            // coordinator domain enqueues the load on storage after this round's
            // drain, and a displacement eviction closes channels from the IO domain.
            // A generous fixed round count reaches quiescence for these bounded
            // machines (every round is cheap and deterministic).
            for (int round = 0; round < 16; round++) {
                this.pumpRound(extraChannels);
            }
        }

        /** One drain of both coordinator domains plus every event loop. */
        private void pumpRound(final EmbeddedChannel... extraChannels) throws Exception {
            this.coordinator.awaitIdle().toCompletableFuture().get(15, TimeUnit.SECONDS);
            this.storage.submit(() -> {
            }).get(15, TimeUnit.SECONDS);
            for (final Conn conn : this.connections) {
                conn.lobby.runPendingTasks();
                if (conn.returnLobby != null) conn.returnLobby.runPendingTasks();
            }
            for (final EmbeddedChannel channel : extraChannels) channel.runPendingTasks();
        }

        @Override
        public void close() {
            for (final Conn conn : this.connections) conn.close();
            for (final EmbeddedChannel channel : this.bareChannels) channel.finishAndReleaseAll();
            this.storage.shutdownNow();
            this.coordinator.shutdown();
        }

        /** Records displacements while keeping the real exit behaviour of the default. */
        private final class RecordingDisplacement extends AccountSessionCoordinator.DefaultDisplacement {

            final List<UUID> evicted = new CopyOnWriteArrayList<>();
            final List<String> messages = new CopyOnWriteArrayList<>();
            final Map<UUID, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();

            RecordingDisplacement() {
                super(() -> Rig.this.rigEndpoint);
            }

            @Override
            public CompletionStage<Void> evict(final PlayerSession session, @Nullable final String message) {
                this.evicted.add(session.connectionId);
                this.messages.add(message);
                final CompletableFuture<Void> gate = this.gates.get(session.connectionId);
                if (gate != null) {
                    // The delayed close: everything (bridge DISCONNECT + c2p close) waits
                    // for the gate, so the grant cannot proceed before the exit completes.
                    return gate.thenCompose(ignored -> super.evict(session, message));
                }
                return super.evict(session, message);
            }
        }

    }

    /** One player connection: c2p client channel + lobby channel(s) + handler(s). */
    private final class Conn implements AutoCloseable {

        final Rig rig;
        final UUID connectionId = UUID.randomUUID();
        final EmbeddedChannel client = new EmbeddedChannel();
        final EmbeddedChannel lobby = new EmbeddedChannel();
        EmbeddedChannel returnLobby;
        final UUID link = UUID.randomUUID();
        final ClientIdentity identity;
        LobbyServerHandler handler;

        Conn(final Rig rig, final ClientIdentity identity) {
            this.rig = rig;
            this.identity = identity;
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
        }

        PlayerSession session() {
            return this.handler != null ? this.handler.getSession() : null;
        }

        void login() {
            this.handler = this.newHandler(this.lobby);
            new LoginStateHandler(this.handler, this.lobby).handle(
                    new C2SLoginHelloPacket(this.identity.wireName(), null, null, null, this.identity.wireUuid()));
            this.lobby.readOutbound();
        }

        LobbyServerHandler newHandler(final io.netty.channel.Channel channel) {
            final LobbyServerHandler handler = new LobbyServerHandler(this.rig.acceptedChannels, this.rig.registry,
                    this.rig.tokens, this.rig.store, this.rig.links, this.rig.coordinator,
                    new RecordingSwitchInitiator(), this.rig.storage, this.rig.uncaught);
            // Installed like LobbyChannelInitializer would, so closing the channel
            // really runs handlerRemoved (the late-close-callback scenarios).
            channel.pipeline().addLast(handler);
            this.handler = handler;
            return handler;
        }

        LobbyServerHandler newReturnHandler() {
            this.returnLobby = new EmbeddedChannel();
            this.returnLobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(this.link);
            return this.newHandler(this.returnLobby);
        }

        @Override
        public void close() {
            LobbyLink.unregister(this.link);
            this.client.finishAndReleaseAll();
            this.lobby.finishAndReleaseAll();
            if (this.returnLobby != null) this.returnLobby.finishAndReleaseAll();
        }

    }

}
