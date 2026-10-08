package dev.connectplus.compat;

import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import dev.connectplus.session.PlayerIdentity;
import dev.connectplus.session.PlayerSession;
import io.netty.channel.Channel;
import javax.annotation.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the lobby server side to ViaProxy's proxy connections: the p2s side of a
 * lobby connection generates a random link id, registers its ProxyConnection here and
 * carries the id to the lobby inside the HAProxy 0xE1 TLV; the lobby stores it as the
 * accepted channel's {@link CPAttributeKeys#LOBBY_LINK_ID} attribute (HAProxyHandler).
 * Lobby clicks resolve their ProxyConnection through that attribute — in-band identity,
 * no port pairing and no IP-keyed state (design F8.2).
 */
public final class LobbyLink {

    /** Read only CP's original authenticated identity, never the linked profile owner. */
    public static @Nullable dev.connectplus.identity.ClientIdentity sessionIdentity(final PlayerSession session) {
        final dev.connectplus.identity.ClientIdentity identity = session.c2pChannel != null
                ? session.c2pChannel.attr(CPAttributeKeys.CLIENT_IDENTITY).get()
                : session.lobbyChannel != null ? clientIdentityOf(session.lobbyChannel) : null;
        return identity != null && session.uuid.equals(identity.wireUuid()) ? identity : null;
    }

    private static final ConcurrentHashMap<UUID, ProxyConnection> LINKS = new ConcurrentHashMap<>();

    private LobbyLink() {
    }

    /**
     * Registers the proxy connection belonging to the p2s channel with the given link
     * id; a re-registration (new switch back to the lobby) overwrites the old entry.
     */
    public static void register(final UUID linkId, final ProxyConnection proxyConnection) {
        LINKS.put(linkId, proxyConnection);
    }

    /**
     * Removes the entry of a closed p2s channel.
     */
    public static void unregister(final UUID linkId) {
        LINKS.remove(linkId);
    }

    /**
     * Resolves the proxy connection of the lobby connection carrying the given link id;
     * null when the id is unset (e.g. bare lobby connections from tests) or the p2s side
     * has meanwhile disconnected.
     */
    public static ProxyConnection resolve(final UUID linkId) {
        return LINKS.get(linkId);
    }

    /** Resolve original identity through the in-process link, never a client-supplied UUID TLV. */
    public static @Nullable PlayerIdentity identityOf(final Channel lobbyChannel) {
        final UUID linkId = lobbyChannel.attr(CPAttributeKeys.LOBBY_LINK_ID).get();
        if (linkId == null) return null;
        final ProxyConnection connection = resolve(linkId);
        if (connection == null || connection.getC2P() == null) return null;
        return connection.getC2P().attr(CPAttributeKeys.PLAYER_IDENTITY).get();
    }

    /**
     * Resolve the verified (or unverified) {@link dev.connectplus.identity.ClientIdentity}
     * through the in-process link, never from client-supplied content. The lobby can
     * only ever see the identity that was captured on the real c2p channel.
     */
    public static @Nullable dev.connectplus.identity.ClientIdentity clientIdentityOf(final Channel lobbyChannel) {
        final UUID linkId = lobbyChannel.attr(CPAttributeKeys.LOBBY_LINK_ID).get();
        if (linkId == null) return null;
        final ProxyConnection connection = resolve(linkId);
        if (connection == null || connection.getC2P() == null) return null;
        return connection.getC2P().attr(CPAttributeKeys.CLIENT_IDENTITY).get();
    }

    /**
     * The ConnectPlus connection id of the client behind this lobby channel:
     * the c2p channel's {@link CPAttributeKeys#CONNECTION_ID}, which survives
     * server switches and lobby returns (the c2p channel does), so the session
     * keeps one identity across the whole c2p lifetime. A c2p without a captured
     * id (test rigs) gets one assigned. Bare diagnostic lobby joins without a
     * link have no separate connection identity; their entry protocol uuid
     * ({@code fallbackUuid}) doubles as the connection id — such connections
     * never hold a lease or a protected profile.
     */
    public static java.util.UUID connectionIdOf(final Channel lobbyChannel, final java.util.UUID fallbackUuid) {
        final UUID linkId = lobbyChannel.attr(CPAttributeKeys.LOBBY_LINK_ID).get();
        if (linkId != null) {
            final ProxyConnection connection = resolve(linkId);
            if (connection != null && connection.getC2P() != null) {
                final java.util.UUID existing =
                        connection.getC2P().attr(CPAttributeKeys.CONNECTION_ID).setIfAbsent(java.util.UUID.randomUUID());
                return existing != null ? existing : connection.getC2P().attr(CPAttributeKeys.CONNECTION_ID).get();
            }
        }
        return fallbackUuid;
    }

    /**
     * The real client's c2p channel behind this lobby channel — the channel whose
     * lifetime a session's lease binds (§5). Null without a link (bare diagnostic
     * lobby joins from tests), which never hold a lease.
     */
    public static @Nullable Channel c2pOf(final Channel lobbyChannel) {
        final UUID linkId = lobbyChannel.attr(CPAttributeKeys.LOBBY_LINK_ID).get();
        if (linkId == null) return null;
        final ProxyConnection connection = resolve(linkId);
        return connection != null ? connection.getC2P() : null;
    }

    /**
     * Retain the lobby session on the verified client's c2p channel, not a backend
     * channel or a UUID-wide cache. Temporary logins survive switches but never
     * survive a real client disconnect or leak to another connection.
     */
    public static @Nullable PlayerSession resumeSession(final Channel lobbyChannel, final UUID uuid, final String name) {
        final PlayerIdentity identity = identityOf(lobbyChannel);
        if (identity == null || !identity.uuid().equals(uuid) || !identity.name().equals(name)) return null;
        final ProxyConnection connection = resolve(lobbyChannel.attr(CPAttributeKeys.LOBBY_LINK_ID).get());
        if (connection == null || connection.getC2P() == null || !connection.getC2P().isActive()) return null;
        final Channel client = connection.getC2P();
        final var attribute = client.attr(CPAttributeKeys.LOBBY_SESSION);
        final PlayerSession candidate = new PlayerSession(uuid, name);
        final PlayerSession previous = attribute.setIfAbsent(candidate);
        if (previous != null) {
            return previous.uuid.equals(uuid) && previous.name.equals(name) ? previous : null;
        }
        client.closeFuture().addListener(future -> {
            final PlayerSession ended = attribute.getAndSet(null);
            if (ended != null) {
                synchronized (ended) {
                    ended.lobbyChannel = null;
                    ended.account = null;
                    ended.chatListener = null;
                    ended.loginInProgress = false;
                }
            }
        });
        return candidate;
    }

    /**
     * Current number of links; for diagnostics.
     */
    public static int size() {
        return LINKS.size();
    }

}
