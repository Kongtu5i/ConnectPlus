package dev.connectplus.compat;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.access.AccessConnectionRegistry;
import dev.connectplus.access.AccessKey;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.lobby.LobbyServer;
import dev.connectplus.lobby.screen.Messages.Console;
import dev.connectplus.lobby.screen.Msg;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.switching.SwitchJob;
import dev.connectplus.switching.SwitchSuppressionHandler;
import io.netty.channel.Channel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.util.ArrayList;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static dev.connectplus.lobby.screen.Languages.consoleText;

/** Read-only console snapshots of client connections, including players outside the lobby. */
public final class OnlinePlayerQuery {
    public enum Location {
        LOBBY(Console.LocationLobby), SERVER(Console.LocationServer), SWITCHING(Console.LocationSwitching);

        private final Msg label;
        Location(final Msg label) { this.label = label; }
        public String label() { return consoleText(this.label); }
    }

    public record Player(UUID connectionId, String name, UUID wireUuid, ClientIdentity identity,
                         String clientVersion, Location location, String currentServer, String serverVersion,
                         PlayerSession session) { }

    /** Incomplete snapshots must never turn an absent player into a definitive offline result. */
    public record Snapshot(List<Player> players, boolean available) {
        public Snapshot { players = List.copyOf(players); }
    }

    private final Supplier<AccessConnectionRegistry> registry;
    private final Supplier<LobbyServer> lobby;
    private final BiFunction<UUID, String, String> bedrockVersion;

    public OnlinePlayerQuery(final Supplier<AccessConnectionRegistry> registry, final Supplier<LobbyServer> lobby) {
        this(registry, lobby, ViaProxyCompat::bedrockClientVersion);
    }

    public OnlinePlayerQuery(final Supplier<AccessConnectionRegistry> registry, final Supplier<LobbyServer> lobby,
                             final BiFunction<UUID, String, String> bedrockVersion) {
        this.registry = registry;
        this.lobby = lobby;
        this.bedrockVersion = bedrockVersion;
    }

    /** Called on the console executor. Mutable host state is inspected on each client's event loop. */
    public Snapshot snapshot() {
        final AccessConnectionRegistry connections = this.registry.get();
        if (connections == null) return new Snapshot(List.of(), false);
        final LobbyServer currentLobby = this.lobby.get();
        final List<CompletableFuture<Player>> pending = new ArrayList<>();
        for (final var entry : connections.snapshot()) {
            if (!entry.c2p().isActive() || entry.status() != AccessConnectionRegistry.Status.ALLOWED
                    || entry.subject() == null) continue;
            final CompletableFuture<Player> result = new CompletableFuture<>();
            pending.add(result);
            final Runnable read = () -> {
                try {
                    final var current = connections.get(entry.connectionId());
                    result.complete(current == null || current.c2p() != entry.c2p() ? null : this.read(current, currentLobby));
                } catch (final RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            };
            try {
                if (entry.c2p().eventLoop().inEventLoop()) read.run();
                else entry.c2p().eventLoop().execute(read);
            } catch (final RuntimeException unavailable) {
                result.completeExceptionally(unavailable);
            }
        }
        boolean available = true;
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            available = false;
        } catch (final Exception unavailable) {
            available = false;
        }
        final List<Player> players = new ArrayList<>();
        for (final var result : pending) {
            if (!result.isDone() || result.isCompletedExceptionally()) continue;
            final Player player = result.getNow(null);
            if (player != null) players.add(player);
        }
        return new Snapshot(players, available);
    }

    private Player read(final AccessConnectionRegistry.Connection entry, final LobbyServer lobby) {
        final Channel c2p = entry.c2p();
        if (!c2p.isActive() || entry.status() != AccessConnectionRegistry.Status.ALLOWED
                || entry.subject() == null || Boolean.TRUE.equals(c2p.attr(CPAttributeKeys.ACCESS_DENIED).get())) return null;
        final ProxyConnection pc = ProxyConnection.fromChannel(c2p);
        if (pc == null) throw new IllegalStateException("Proxy connection unavailable");
        final PlayerSession retained = c2p.attr(CPAttributeKeys.LOBBY_SESSION).get();
        final ClientIdentity identity = c2p.attr(CPAttributeKeys.CLIENT_IDENTITY).get();
        final UUID wireUuid = identity != null ? identity.wireUuid() : entry.subject().entryUuid();
        final var platform = entry.subject().clientType();
        final String version;
        if (platform == AccessKey.ClientType.BEDROCK) {
            String original = null;
            try { original = this.bedrockVersion.apply(wireUuid, entry.subject().xuid()); }
            catch (final RuntimeException unavailable) { /* Display metadata cannot hide an online player. */ }
            version = original == null || original.isBlank() ? consoleText(Console.BedrockVersionUnknown) : consoleText(Console.BedrockVersion, original);
        } else if (platform == AccessKey.ClientType.JAVA) {
            version = javaVersion(pc.getClientVersion());
        } else {
            version = consoleText(Console.PlatformVersionUnknown);
        }
        final SwitchSuppressionHandler suppression = pc.getPacketHandler(SwitchSuppressionHandler.class);
        final SwitchJob job = suppression == null ? null : suppression.currentJob();
        final SocketAddress serverAddress = pc.getServerAddress();
        final Location location;
        if (job != null && (job.isSwitching() || job.isFailed())) {
            location = Location.SWITCHING;
        } else if (lobby != null && lobby.localAddress() != null && lobby.localAddress().equals(serverAddress)
                && pc.getChannel() != null && pc.getChannel().isActive()
                && pc.getP2sConnectionState() == ConnectionState.PLAY) {
            location = Location.LOBBY;
        } else if (pc.getChannel() != null && pc.getChannel().isActive()
                && pc.getP2sConnectionState() == ConnectionState.PLAY) {
            location = Location.SERVER;
        } else {
            location = Location.SWITCHING;
        }
        final String name = entry.subject().name() != null ? entry.subject().name() : consoleText(Console.UnknownPlayerName);
        final String currentServer = location == Location.SERVER ? formatAddress(serverAddress) : null;
        final ProtocolVersion serverProtocol = location == Location.SERVER ? pc.getServerVersion() : null;
        final String serverVersion = serverProtocol == null || !serverProtocol.isKnown() ? consoleText(Console.Unknown) : serverProtocol.getName();
        return new Player(entry.connectionId(), name, wireUuid, identity, version, location, currentServer, serverVersion, retained);
    }

    private static String formatAddress(final SocketAddress address) {
        if (address == null) return consoleText(Console.Unavailable);
        if (address instanceof InetSocketAddress target) {
            return com.google.common.net.HostAndPort.fromParts(target.getHostString(), target.getPort()).toString();
        }
        return address.toString();
    }

    private static String javaVersion(final ProtocolVersion protocol) {
        return protocol == null ? consoleText(Console.JavaVersionUnknown) : consoleText(Console.JavaVersion, protocol.getName());
    }
}
