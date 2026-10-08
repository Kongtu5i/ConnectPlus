package dev.connectplus.testutil;

import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.session.AccountSessionCoordinator;
import dev.connectplus.session.PlayerIdentity;
import dev.connectplus.session.PlayerSession;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Explicit upstream authentication fixtures; bare lobby channels stay unverified. */
public final class TestClientIdentity {
    private TestClientIdentity() { }

    public static void javaIdentity(PlayerSession session, Channel client) {
        client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(
                ClientIdentity.verifiedJava(session.uuid, session.name, session.uuid));
        session.c2pChannel = client;
    }

    public static void linkJava(EmbeddedChannel lobby, UUID uuid, String name) {
        lobby.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.verifiedJava(uuid, name, uuid));
        lobby.attr(CPAttributeKeys.PLAYER_IDENTITY).set(new PlayerIdentity(uuid, name));
        lobby.attr(CPAttributeKeys.CONNECTION_ID).set(uuid);
        UUID link = UUID.randomUUID();
        LobbyLink.register(link, new ProxyConnection(new MinecraftChannelInitializer(Proxy2ServerHandler::new), lobby));
        lobby.attr(CPAttributeKeys.LOBBY_LINK_ID).set(link);
        lobby.closeFuture().addListener(ignored -> LobbyLink.unregister(link));
    }

    /** Uses the real registration/RESOLVE validation, with an in-process provider. */
    public static BedrockProvider bedrock(Channel client, ClientIdentity identity) {
        BedrockBridgeEndpoint endpoint = new BedrockBridgeEndpoint();
        boolean previous = CPConfig.GeyserSupport.enabled;
        Map<String, Object> registration;
        try {
            CPConfig.GeyserSupport.enabled = true;
            registration = endpoint.register(Map.of(
                    "protocolVersion", 1, "providerId", BedrockBridgeEndpoint.PROVIDER_ID,
                    "providerEpoch", identity.providerEpoch().toString(),
                    "bridgeVersion", "test", "geyserVersion", "test", "viaproxyVersion", "3.4.13",
                    "capabilities", List.copyOf(BedrockBridgeEndpoint.REQUIRED_CAPABILITIES)), request ->
                    CompletableFuture.completedFuture(Map.of(
                            "protocolVersion", 1, "providerEpoch", identity.providerEpoch().toString(),
                            "status", "VERIFIED", "connectionId", request.get("connectionId"),
                            "xuid", identity.xuid(), "bedrockUsername", identity.wireName(),
                            "bridgeSessionId", identity.bridgeSessionId())));
        } finally {
            CPConfig.GeyserSupport.enabled = previous;
        }
        if (!"REGISTERED".equals(registration.get("status"))) throw new AssertionError(registration);
        UUID connectionId = client.attr(CPAttributeKeys.CONNECTION_ID).setIfAbsent(UUID.randomUUID());
        if (connectionId == null) connectionId = client.attr(CPAttributeKeys.CONNECTION_ID).get();
        var result = endpoint.resolve(client, new InetSocketAddress("127.0.0.1", 25565),
                new InetSocketAddress("127.0.0.1", 40000), connectionId, identity.wireUuid(), identity.wireName())
                .toCompletableFuture().join();
        if (result.status() != BedrockBridgeEndpoint.ResolveStatus.VERIFIED) throw new AssertionError(result);
        client.attr(CPAttributeKeys.BEDROCK_BRIDGE_ENDPOINT).set(endpoint);
        client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(identity);
        return new BedrockProvider(endpoint, (String) registration.get("registrationId"), identity.providerEpoch());
    }

    public record BedrockProvider(BedrockBridgeEndpoint endpoint, String registrationId, UUID epoch) {
        public void stop() {
            endpoint.handleEvent(registrationId, Map.of("protocolVersion", 1,
                    "providerEpoch", epoch.toString(), "op", "PROVIDER_STOPPING", "reasonCode", "TEST"))
                    .toCompletableFuture().join();
        }
    }

    public static void awaitProfile(LobbyServerHandler handler, ExecutorService storage, EmbeddedChannel lobby) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (handler.getSession().playerData == null && System.nanoTime() < deadline) {
            dev.connectplus.session.CoordinatorTestSupport.awaitIdle((AccountSessionCoordinator) handler.getLeaseGranter());
            storage.submit(() -> { }).get(3, TimeUnit.SECONDS);
            lobby.runPendingTasks();
        }
        if (handler.getSession().playerData == null) throw new AssertionError("Protected profile did not load");
    }

    public static void settle(LobbyServerHandler handler, ExecutorService storage, EmbeddedChannel lobby) throws Exception {
        for (int round = 0; round < 12; round++) {
            dev.connectplus.session.CoordinatorTestSupport.awaitIdle((AccountSessionCoordinator) handler.getLeaseGranter());
            storage.submit(() -> { }).get(3, TimeUnit.SECONDS);
            lobby.runPendingTasks();
        }
    }
}
