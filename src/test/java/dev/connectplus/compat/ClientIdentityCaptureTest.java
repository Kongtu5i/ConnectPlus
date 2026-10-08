package dev.connectplus.compat;

import com.mojang.authlib.GameProfile;
import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.testutil.ViaProxyTestConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.netminecraft.netty.codec.PacketCryptor;
import net.raphimc.netminecraft.netty.crypto.AESEncryption;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.events.Client2ProxyChannelInitializeEvent;
import net.raphimc.viaproxy.plugins.events.ClientLoggedInEvent;
import net.raphimc.viaproxy.plugins.events.types.ITyped;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The capture side of the identity pipeline: PRE-phase raw connection data and the
 * explicit Java-auth-completion branch, producing a ClientIdentity bound to the c2p
 * channel. ViaProxy 3.4.13 fires ClientLoggedInEvent in BOTH auth branches, and the
 * premium branch fires it even after a FAILED Mojang session check (encryption
 * handler installed before the check, kick scheduled ahead of the event, profile
 * still client-supplied). The capture therefore keys the verified classification on
 * the encryption handler AND a still-alive c2p at event time — never on the global
 * online-mode flag.
 */
class ClientIdentityCaptureTest {

    private static final String XUID = "4242424242424";
    private static final String BRIDGE_SESSION = UUID.randomUUID().toString();
    private static final UUID PROVIDER_EPOCH = UUID.randomUUID();

    private boolean geyserSupportWasEnabled;

    @BeforeEach
    void enableGeyserSupport() {
        this.geyserSupportWasEnabled = CPConfig.GeyserSupport.enabled;
        CPConfig.GeyserSupport.enabled = true;
    }

    @AfterEach
    void restoreGeyserSupport() {
        CPConfig.GeyserSupport.enabled = this.geyserSupportWasEnabled;
    }

    private static ProxyConnection proxyOf(final EmbeddedChannel client) {
        return new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
    }

    private static void markVerifiedBranch(final EmbeddedChannel client) {
        // What ViaProxy 3.4.13 does after the Mojang session check succeeded: the c2p
        // channel carries the netty encryption handler (LoginPacketHandler#handleC2P).
        try {
            final KeyGenerator aes = KeyGenerator.getInstance("AES");
            aes.init(128);
            client.attr(MCPipeline.ENCRYPTION_ATTRIBUTE_KEY).set(new AESEncryption(aes.generateKey()));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClientIdentity identityOf(final EmbeddedChannel client) {
        return client.attr(CPAttributeKeys.CLIENT_IDENTITY).get();
    }

    private static ClientIdentityCapture captureOver(final BedrockBridgeEndpoint endpoint) {
        return new ClientIdentityCapture(() -> endpoint);
    }

    /** A well-formed bridge descriptor for a stub provider with a fixed epoch. */
    private static Map<String, Object> descriptor() {
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("protocolVersion", 1);
        d.put("providerId", "connectplus-geyser-bridge");
        d.put("providerEpoch", PROVIDER_EPOCH.toString());
        d.put("bridgeVersion", "1.0.0");
        d.put("geyserVersion", "2.8.2");
        d.put("viaproxyVersion", "3.4.13");
        d.put("capabilities", List.of("verified-xuid", "exact-channel-binding",
                "targeted-disconnect", "authenticated-duplicate-admission"));
        return d;
    }

    /**
     * A registered endpoint whose provider hands every RESOLVE request to the test:
     * the test keeps the returned future and answers it explicitly, so the async
     * boundary between login and verification is under test control.
     */
    private static final class DeferredProvider {
        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<Map<String, Object>>> responses = new CopyOnWriteArrayList<>();
        final List<String> verifiedSessionIds = new CopyOnWriteArrayList<>();
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();

        DeferredProvider() {
            assertEquals("REGISTERED",
                    this.endpoint.register(descriptor(), request -> {
                        final CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
                        this.requests.add(request);
                        this.responses.add(response);
                        return response;
                    }).get("status"));
        }

        /** Completes the i-th request with a well-echoed VERIFIED response. */
        void verify(final int i, final String xuid, final String username) {
            final Map<String, Object> request = this.requests.get(i);
            final String bridgeSessionId = UUID.randomUUID().toString();
            final Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "VERIFIED");
            response.put("protocolVersion", 1);
            response.put("providerEpoch", request.get("providerEpoch"));
            response.put("connectionId", request.get("connectionId"));
            response.put("xuid", xuid);
            response.put("bedrockUsername", username);
            response.put("bridgeSessionId", bridgeSessionId);
            this.verifiedSessionIds.add(bridgeSessionId);
            this.responses.get(i).complete(response);
        }

        /** Completes the i-th request with a well-echoed NO_MATCH response. */
        void noMatch(final int i) {
            final Map<String, Object> request = this.requests.get(i);
            final Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "NO_MATCH");
            response.put("protocolVersion", 1);
            response.put("providerEpoch", request.get("providerEpoch"));
            response.put("connectionId", request.get("connectionId"));
            this.responses.get(i).complete(response);
        }
    }

    /** Polls until the channel's identity reaches the expected kind (or times out). */
    private static ClientIdentity awaitIdentity(final EmbeddedChannel client, final ClientIdentity.Kind kind) {
        final var attribute = client.attr(CPAttributeKeys.CLIENT_IDENTITY);
        final long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            client.runPendingTasks();
            final ClientIdentity identity = attribute.get();
            if (identity != null && identity.kind() == kind) {
                return identity;
            }
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return attribute.get();
    }

    @Test
    void prePhaseCapturesRawAddressesAndConnectionId() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ClientIdentityCapture capture = captureOver(null);
        capture.onClient2ProxyChannelInitialize(
                new Client2ProxyChannelInitializeEvent(ITyped.Type.PRE, channel, false));
        final UUID connectionId = channel.attr(CPAttributeKeys.CONNECTION_ID).get();
        assertNotNull(connectionId, "each c2p channel gets its own connectionId in the PRE phase");
        assertSame(channel.localAddress(), channel.attr(CPAttributeKeys.RAW_LOCAL_ADDRESS).get(),
                "the raw (pre-Geyser-rewrite) local address must be captured");
        assertSame(channel.remoteAddress(), channel.attr(CPAttributeKeys.RAW_REMOTE_ADDRESS).get(),
                "the raw (pre-Geyser-rewrite) remote address must be captured");

        // POST does not re-capture; PRE does not hand out a second connectionId.
        capture.onClient2ProxyChannelInitialize(
                new Client2ProxyChannelInitializeEvent(ITyped.Type.POST, channel, false));
        assertSame(connectionId, channel.attr(CPAttributeKeys.CONNECTION_ID).get());
        channel.finishAndReleaseAll();
    }

    @Test
    void anEmptyEncryptionAttributeFromTheRealPacketCryptorDoesNotVerifyJava() {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(true);
        final EmbeddedChannel client = new EmbeddedChannel(new PacketCryptor());
        try {
            assertEquals(true, client.hasAttr(MCPipeline.ENCRYPTION_ATTRIBUTE_KEY));
            assertNull(client.attr(MCPipeline.ENCRYPTION_ATTRIBUTE_KEY).get());
            final ProxyConnection proxy = proxyOf(client);
            proxy.setGameProfile(new GameProfile(UUID.randomUUID(), "UnverifiedPlayer"));
            captureOver(null).onClientLoggedIn(new ClientLoggedInEvent(proxy));
            assertEquals(ClientIdentity.Kind.UNVERIFIED, identityOf(client).kind(),
                    "An allocated encryption attribute with no cipher is not Java authentication");
            assertNull(identityOf(client).verifiedJavaUuid());
        } finally {
            client.finishAndReleaseAll();
        }
    }

    @Test
    void aBedrockConnectionWithTheRealPacketCryptorStillResolvesThroughTheBridge() throws Exception {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(true);
        final DeferredProvider provider = new DeferredProvider();
        final EmbeddedChannel client = new EmbeddedChannel(new PacketCryptor());
        try {
            final ClientIdentityCapture capture = captureOver(provider.endpoint);
            capture.onClient2ProxyChannelInitialize(new Client2ProxyChannelInitializeEvent(ITyped.Type.PRE, client, false));
            final ProxyConnection proxy = proxyOf(client);
            proxy.setGameProfile(new GameProfile(UUID.randomUUID(), "BedrockPlayer"));
            capture.onClientLoggedIn(new ClientLoggedInEvent(proxy));
            assertEquals(1, provider.requests.size(), "The Bedrock connection must reach RESOLVE despite having an empty encryption attribute");
            provider.verify(0, XUID, "BedrockPlayer");
            final ClientIdentity identity = awaitIdentity(client, ClientIdentity.Kind.VERIFIED_BEDROCK);
            assertEquals(ClientIdentity.Kind.VERIFIED_BEDROCK, identity.kind());
            assertEquals(XUID, identity.xuid());
            assertNull(identity.verifiedJavaUuid());
        } finally {
            client.finishAndReleaseAll();
        }
    }

    @Test
    void aReallyVerifiedJavaLoginBecomesVerifiedJava() {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(true);
        final EmbeddedChannel client = new EmbeddedChannel();
        final ProxyConnection proxy = proxyOf(client);
        final UUID verifiedUuid = UUID.randomUUID();
        proxy.setGameProfile(new GameProfile(verifiedUuid, "PremiumPlayer"));
        markVerifiedBranch(client);

        captureOver(null).onClientLoggedIn(new ClientLoggedInEvent(proxy));
        final ClientIdentity identity = identityOf(client);
        assertNotNull(identity);
        assertEquals(ClientIdentity.Kind.VERIFIED_JAVA, identity.kind());
        assertEquals(verifiedUuid, identity.verifiedJavaUuid());
        assertEquals(verifiedUuid, identity.wireUuid());
        assertEquals("PremiumPlayer", identity.wireName());
        client.finishAndReleaseAll();
    }

    /**
     * ViaProxy 3.4.13's premium branch fires ClientLoggedInEvent even when the Mojang
     * session check FAILED (hasJoinedServer null → "Invalid session" kick, or a
     * session-check Throwable): the encryption handler is already on the c2p and the
     * kick's disconnect write + CLOSE is queued on the event loop ahead of the
     * scheduled event, so the event fires on a dead/dying channel still carrying the
     * client-supplied profile. Such an event must never yield VERIFIED_JAVA and must
     * not ask the bridge for a bedrock identity either.
     */
    @Test
    void aKickedConnectionNeverGetsVerifiedJavaAndNeverResolves() {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(true);
        final DeferredProvider provider = new DeferredProvider();
        final EmbeddedChannel client = new EmbeddedChannel();
        final ClientIdentityCapture capture = captureOver(provider.endpoint);
        capture.onClient2ProxyChannelInitialize(
                new Client2ProxyChannelInitializeEvent(ITyped.Type.PRE, client, false));
        final ProxyConnection proxy = proxyOf(client);
        final UUID clientClaimedUuid = UUID.randomUUID();
        proxy.setGameProfile(new GameProfile(clientClaimedUuid, "ClaimedPremium"));
        markVerifiedBranch(client);

        // The kick from the failed session check closes the channel before the
        // scheduled ClientLoggedInEvent runs on the event loop.
        client.close();
        capture.onClientLoggedIn(new ClientLoggedInEvent(proxy));

        final ClientIdentity identity = identityOf(client);
        assertNotNull(identity);
        assertNotEquals(ClientIdentity.Kind.VERIFIED_JAVA, identity.kind(),
                "a kicked, dying connection must never be classified VERIFIED_JAVA "
                        + "even though the encryption handler is installed");
        assertEquals(ClientIdentity.Kind.UNVERIFIED, identity.kind());
        assertNull(identity.verifiedJavaUuid());
        assertEquals(clientClaimedUuid, identity.wireUuid(),
                "the client-supplied profile stays a wire identity only");
        assertEquals(0, provider.requests.size(),
                "a dying connection must not ask the bridge for an identity");
    }

    @Test
    void onlineModeWithCancelledVerificationStaysUnverified() {        ViaProxyTestConfig.init();
        // The global switch is on, but verification was skipped (cancelled auth event):
        // ViaProxy never installed the encryption handler and the profile is the
        // client-supplied one.
        ViaProxy.getConfig().setProxyOnlineMode(true);
        final EmbeddedChannel client = new EmbeddedChannel();
        final ProxyConnection proxy = proxyOf(client);
        final UUID clientClaimedUuid = UUID.randomUUID();
        proxy.setGameProfile(new GameProfile(clientClaimedUuid, "ClaimedPremium"));

        captureOver(null).onClientLoggedIn(new ClientLoggedInEvent(proxy));
        final ClientIdentity identity = identityOf(client);
        assertNotNull(identity);
        assertEquals(ClientIdentity.Kind.UNVERIFIED, identity.kind(),
                "a cancelled verification must not yield VERIFIED_JAVA even with global online-mode=true");
        assertNull(identity.verifiedJavaUuid());
        assertEquals(clientClaimedUuid, identity.wireUuid(),
                "the client-supplied UUID stays the wire identity, never a verified one");
        client.finishAndReleaseAll();
    }

    @Test
    void theGlobalOnlineModeFlagAloneNeverVerifies() {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(false);
        final EmbeddedChannel client = new EmbeddedChannel();
        final ProxyConnection proxy = proxyOf(client);
        proxy.setGameProfile(new GameProfile(
                UUID.nameUUIDFromBytes("OfflinePlayer:Cracked".getBytes(StandardCharsets.UTF_8)), "Cracked"));

        captureOver(null).onClientLoggedIn(new ClientLoggedInEvent(proxy));
        final ClientIdentity identity = identityOf(client);
        assertNotNull(identity);
        assertEquals(ClientIdentity.Kind.UNVERIFIED, identity.kind(),
                "a global flag is not authentication");
        client.finishAndReleaseAll();
    }

    @Test
    void noMatchNeverProducesAJavaIdentity() throws Exception {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(false);
        final DeferredProvider provider = new DeferredProvider();
        final EmbeddedChannel client = new EmbeddedChannel();
        final ClientIdentityCapture capture = captureOver(provider.endpoint);
        capture.onClient2ProxyChannelInitialize(
                new Client2ProxyChannelInitializeEvent(ITyped.Type.PRE, client, false));
        final ProxyConnection proxy = proxyOf(client);
        proxy.setGameProfile(new GameProfile(UUID.randomUUID(), "JavaPlayer"));

        capture.onClientLoggedIn(new ClientLoggedInEvent(proxy));
        assertEquals(1, provider.requests.size(), "the bridge is asked about this connection");
        provider.noMatch(0);
        final ClientIdentity identity = awaitIdentity(client, ClientIdentity.Kind.UNVERIFIED);
        assertNotNull(identity);
        assertEquals(ClientIdentity.Kind.UNVERIFIED, identity.kind(),
                "NO_MATCH is not a Java verification; the connection stays unverified");
        assertNull(identity.verifiedJavaUuid());
        assertNull(identity.xuid());
        client.finishAndReleaseAll();
    }

    @Test
    void aVerifiedBridgeResponseBindsTheBedrockIdentityToTheChannel() throws Exception {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(false);
        final DeferredProvider provider = new DeferredProvider();
        final EmbeddedChannel client = new EmbeddedChannel();
        final ClientIdentityCapture capture = captureOver(provider.endpoint);
        capture.onClient2ProxyChannelInitialize(
                new Client2ProxyChannelInitializeEvent(ITyped.Type.PRE, client, false));
        final ProxyConnection proxy = proxyOf(client);
        final UUID wireUuid = UUID.nameUUIDFromBytes("OfflinePlayer:BedrockPlayer".getBytes(StandardCharsets.UTF_8));
        proxy.setGameProfile(new GameProfile(wireUuid, "BedrockPlayer"));

        capture.onClientLoggedIn(new ClientLoggedInEvent(proxy));
        // The answer is asynchronous: the connection starts unverified and must not
        // touch protected data while the resolve is in flight.
        assertEquals(ClientIdentity.Kind.UNVERIFIED, identityOf(client).kind());

        provider.verify(0, XUID, "BedrockPlayer");
        final ClientIdentity identity = awaitIdentity(client, ClientIdentity.Kind.VERIFIED_BEDROCK);
        assertNotNull(identity, "the verified answer must upgrade the connection identity");
        assertEquals(XUID, identity.xuid());
        assertEquals(PROVIDER_EPOCH, identity.providerEpoch());
        assertEquals(provider.verifiedSessionIds.get(0), identity.bridgeSessionId());
        assertEquals(wireUuid, identity.wireUuid(), "linking never replaces the wire identity");
        assertEquals("BedrockPlayer", identity.wireName());
        client.finishAndReleaseAll();
    }

    @Test
    void twoSessionsFromTheSameIpStaySeparate() throws Exception {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(false);
        // Both connections come from the same NAT address and cannot be told apart by
        // IP; the association must be per-channel/connectionId.
        final InetSocketAddress sharedRemote = new InetSocketAddress("192.0.2.7", 53535);
        final DeferredProvider provider = new DeferredProvider();
        final ClientIdentityCapture capture = captureOver(provider.endpoint);

        final EmbeddedChannel clientA = new EmbeddedChannel();
        final EmbeddedChannel clientB = new EmbeddedChannel();
        for (final EmbeddedChannel client : List.of(clientA, clientB)) {
            capture.onClient2ProxyChannelInitialize(
                    new Client2ProxyChannelInitializeEvent(ITyped.Type.PRE, client, false));
            client.attr(CPAttributeKeys.RAW_REMOTE_ADDRESS).set(sharedRemote);
        }
        assertNotEquals(clientA.attr(CPAttributeKeys.CONNECTION_ID).get(),
                clientB.attr(CPAttributeKeys.CONNECTION_ID).get(),
                "same-IP sessions still get distinct connection ids");
        assertEquals(sharedRemote, clientA.attr(CPAttributeKeys.RAW_REMOTE_ADDRESS).get());
        assertEquals(sharedRemote, clientB.attr(CPAttributeKeys.RAW_REMOTE_ADDRESS).get(),
                "the two sessions share one IP");

        final ProxyConnection proxyA = proxyOf(clientA);
        final ProxyConnection proxyB = proxyOf(clientB);
        proxyA.setGameProfile(new GameProfile(UUID.randomUUID(), "NAT-1111111111"));
        proxyB.setGameProfile(new GameProfile(UUID.randomUUID(), "NAT-2222222222"));
        capture.onClientLoggedIn(new ClientLoggedInEvent(proxyA));
        capture.onClientLoggedIn(new ClientLoggedInEvent(proxyB));
        assertEquals(2, provider.requests.size(), "the bridge is asked about each connection separately");
        assertNotEquals(provider.requests.get(0).get("connectionId"),
                provider.requests.get(1).get("connectionId"));
        assertEquals(sharedRemote, provider.requests.get(0).get("rawRemoteAddress"));
        assertEquals(sharedRemote, provider.requests.get(1).get("rawRemoteAddress"));

        // Session A's answer must land on A only, session B's on B only.
        provider.verify(0, "1111111111", "NAT-1111111111");
        provider.verify(1, "2222222222", "NAT-2222222222");
        final ClientIdentity a = awaitIdentity(clientA, ClientIdentity.Kind.VERIFIED_BEDROCK);
        final ClientIdentity b = awaitIdentity(clientB, ClientIdentity.Kind.VERIFIED_BEDROCK);
        assertEquals("1111111111", a.xuid());
        assertEquals("2222222222", b.xuid(),
                "two sessions behind the same IP must never adopt each other's identity");
        assertNotEquals(a.bridgeSessionId(), b.bridgeSessionId());
        clientA.finishAndReleaseAll();
        clientB.finishAndReleaseAll();
    }

    @Test
    void aTimedOutResolveStaysUnverifiedAndLateAnswersGrantNothing() throws Exception {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(false);
        final BedrockBridgeEndpoint endpoint =
                new BedrockBridgeEndpoint(Duration.ofMillis(60), Duration.ofMillis(60), Duration.ofMillis(60));
        final List<CompletableFuture<Map<String, Object>>> pending = new ArrayList<>();
        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        assertEquals("REGISTERED", endpoint.register(descriptor(), request -> {
            final CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
            requests.add(request);
            pending.add(response);
            return response;
        }).get("status"));
        final ClientIdentityCapture capture = captureOver(endpoint);

        // Old connection: the bridge never answers in time.
        final EmbeddedChannel oldClient = new EmbeddedChannel();
        capture.onClient2ProxyChannelInitialize(
                new Client2ProxyChannelInitializeEvent(ITyped.Type.PRE, oldClient, false));
        final ProxyConnection oldProxy = proxyOf(oldClient);
        oldProxy.setGameProfile(new GameProfile(UUID.randomUUID(), "WaitingPlayer"));
        capture.onClientLoggedIn(new ClientLoggedInEvent(oldProxy));
        assertEquals(1, requests.size());
        final ClientIdentity timedOut = awaitIdentity(oldClient, ClientIdentity.Kind.UNVERIFIED);
        assertEquals(ClientIdentity.Kind.UNVERIFIED, timedOut.kind(), "a timed-out resolve grants nothing");
        // Let the resolve deadline (60 ms) actually elapse before the provider answers.
        Thread.sleep(150);

        // The provider answers late — the response echoes the OLD connectionId, but the
        // resolve already timed out: it must stay ineffective.
        final Map<String, Object> lateEcho = new LinkedHashMap<>();
        lateEcho.put("protocolVersion", 1);
        lateEcho.put("providerEpoch", PROVIDER_EPOCH.toString());
        lateEcho.put("connectionId", requests.get(0).get("connectionId"));
        final Map<String, Object> lateResponse = new LinkedHashMap<>(lateEcho);
        lateResponse.put("status", "VERIFIED");
        lateResponse.put("xuid", XUID);
        lateResponse.put("bedrockUsername", "LatePlayer");
        lateResponse.put("bridgeSessionId", BRIDGE_SESSION);
        pending.get(0).complete(lateResponse);
        Thread.sleep(100);
        assertEquals(ClientIdentity.Kind.UNVERIFIED, identityOf(oldClient).kind(),
                "a late VERIFIED response must not upgrade the timed-out connection");
        assertFalse(endpoint.isSessionManaged(BRIDGE_SESSION), "a late response must not change any state");

        // The new connection must not inherit the old connection's answer either.
        final EmbeddedChannel newClient = new EmbeddedChannel();
        capture.onClient2ProxyChannelInitialize(
                new Client2ProxyChannelInitializeEvent(ITyped.Type.PRE, newClient, false));
        final ProxyConnection newProxy = proxyOf(newClient);
        newProxy.setGameProfile(new GameProfile(UUID.randomUUID(), "FreshPlayer"));
        capture.onClientLoggedIn(new ClientLoggedInEvent(newProxy));
        assertEquals(2, requests.size());
        assertEquals(ClientIdentity.Kind.UNVERIFIED, identityOf(newClient).kind(),
                "the new connection is still waiting for its own answer");

        // And when its own correctly echoed answer arrives, only then it verifies.
        final Map<String, Object> ownResponse = new LinkedHashMap<>();
        ownResponse.put("status", "VERIFIED");
        ownResponse.put("protocolVersion", 1);
        ownResponse.put("providerEpoch", PROVIDER_EPOCH.toString());
        ownResponse.put("connectionId", requests.get(1).get("connectionId"));
        ownResponse.put("xuid", "9999999999");
        ownResponse.put("bedrockUsername", "FreshPlayer");
        ownResponse.put("bridgeSessionId", UUID.randomUUID().toString());
        pending.get(1).complete(ownResponse);
        final ClientIdentity fresh = awaitIdentity(newClient, ClientIdentity.Kind.VERIFIED_BEDROCK);
        assertEquals("9999999999", fresh.xuid());
        assertEquals(ClientIdentity.Kind.UNVERIFIED, identityOf(oldClient).kind(),
                "the old connection stays unverified; any old response must not grant a new connection its identity");
        oldClient.finishAndReleaseAll();
        newClient.finishAndReleaseAll();
    }

    @Test
    void aConnectionWithoutPreCaptureIsNeverResolved() {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(false);
        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        assertEquals("REGISTERED", endpoint.register(descriptor(), request -> {
            requests.add(request);
            return CompletableFuture.completedFuture(Map.<String, Object>of("status", "UNAVAILABLE",
                    "protocolVersion", 1, "providerEpoch", PROVIDER_EPOCH.toString()));
        }).get("status"));
        final EmbeddedChannel client = new EmbeddedChannel();
        final ProxyConnection proxy = proxyOf(client);
        proxy.setGameProfile(new GameProfile(UUID.randomUUID(), "NoCapture"));

        // No PRE event ran: no raw addresses/connectionId were captured, so the capture
        // must not send a RESOLVE (the protocol requires the raw connection data).
        captureOver(endpoint).onClientLoggedIn(new ClientLoggedInEvent(proxy));
        assertEquals(0, requests.size());
        assertEquals(ClientIdentity.Kind.UNVERIFIED, identityOf(client).kind());
        client.finishAndReleaseAll();
    }

    @Test
    void repeatedLoginEventsDoNotReplaceTheIdentity() {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(true);
        final EmbeddedChannel client = new EmbeddedChannel();
        final ProxyConnection proxy = proxyOf(client);
        proxy.setGameProfile(new GameProfile(UUID.randomUUID(), "PremiumPlayer"));
        markVerifiedBranch(client);
        final ClientIdentityCapture capture = captureOver(null);

        capture.onClientLoggedIn(new ClientLoggedInEvent(proxy));
        final ClientIdentity first = identityOf(client);
        assertEquals(ClientIdentity.Kind.VERIFIED_JAVA, first.kind());

        // A backend rewrite must not replace the original authenticated owner.
        proxy.setGameProfile(new GameProfile(UUID.randomUUID(), "BackendIdentity"));
        capture.onClientLoggedIn(new ClientLoggedInEvent(proxy));
        assertSame(first, identityOf(client));
        client.finishAndReleaseAll();
    }

    /**
     * The shipped default (docs/config-zh.md): with {@code geyser-support.enabled}
     * = false the bridge endpoint refuses every registration (DISABLED), so an
     * unverified bedrock connection's RESOLVE finds no registration (UNAVAILABLE)
     * and the connection stays UNVERIFIED with no protected access, while a really
     * verified Java login under the same configuration still classifies VERIFIED_JAVA.
     */
    @Test
    void aDisabledBridgeKeepsBedrockUnverifiedWhileVerifiedJavaJoinsStillWork() {
        ViaProxyTestConfig.init();
        ViaProxy.getConfig().setProxyOnlineMode(false);
        CPConfig.GeyserSupport.enabled = false; // the default; restoreGeyserSupport undoes this
        final BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();

        // A provider cannot attach at all: DISABLED precedes every other check.
        final Map<String, Object> refusal = endpoint.register(descriptor(),
                request -> CompletableFuture.completedFuture(Map.of()));
        assertEquals("REJECTED", refusal.get("status"));
        assertEquals("DISABLED", refusal.get("reasonCode"));

        final ClientIdentityCapture capture = captureOver(endpoint);
        final EmbeddedChannel bedrock = new EmbeddedChannel();
        capture.onClient2ProxyChannelInitialize(
                new Client2ProxyChannelInitializeEvent(ITyped.Type.PRE, bedrock, false));
        final ProxyConnection bedrockProxy = proxyOf(bedrock);
        bedrockProxy.setGameProfile(new GameProfile(
                UUID.nameUUIDFromBytes("OfflinePlayer:BedrockPlayer".getBytes(StandardCharsets.UTF_8)),
                "BedrockPlayer"));

        capture.onClientLoggedIn(new ClientLoggedInEvent(bedrockProxy));
        final ClientIdentity bedrockIdentity = identityOf(bedrock);
        assertNotNull(bedrockIdentity);
        assertEquals(ClientIdentity.Kind.UNVERIFIED, bedrockIdentity.kind(),
                "with the bridge disabled no bedrock identity can be verified");

        ViaProxy.getConfig().setProxyOnlineMode(true);
        final EmbeddedChannel javaClient = new EmbeddedChannel();
        final ProxyConnection javaProxy = proxyOf(javaClient);
        final UUID verifiedUuid = UUID.randomUUID();
        javaProxy.setGameProfile(new GameProfile(verifiedUuid, "PremiumPlayer"));
        markVerifiedBranch(javaClient);

        capture.onClientLoggedIn(new ClientLoggedInEvent(javaProxy));
        final ClientIdentity javaIdentity = identityOf(javaClient);
        assertNotNull(javaIdentity);
        assertEquals(ClientIdentity.Kind.VERIFIED_JAVA, javaIdentity.kind(),
                "verified Java joins are unaffected by the disabled bridge");
        assertEquals(verifiedUuid, javaIdentity.verifiedJavaUuid());

        bedrock.finishAndReleaseAll();
        javaClient.finishAndReleaseAll();
    }
}
