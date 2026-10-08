package dev.connectplus.compat;

import dev.connectplus.lobby.model.HandshakeData;
import dev.connectplus.session.PlayerIdentity;
import dev.connectplus.session.PlayerSession;
import io.netty.util.AttributeKey;

/**
 * Channel attribute keys shared across the plugin. Only netty types are referenced here
 * so every package can use these keys without touching ViaProxy internals.
 *
 */
public final class CPAttributeKeys {

    /**
     * Set on the c2p channel when the connection is routed into the lobby; the p2s
     * side then forwards an HAProxy v2 message with the client handshake information.
     */
    public static final AttributeKey<Boolean> ENABLE_HAPROXY = AttributeKey.valueOf("connectplus_enable_haproxy");

    /**
     * The handshake information parsed from the HAProxy 0xE0 TLV on the lobby side.
     */
    public static final AttributeKey<HandshakeData> HANDSHAKE_DATA = AttributeKey.valueOf("connectplus_handshake_data");

    /**
     * The switch link id parsed from the HAProxy 0xE1 TLV on the lobby side. The p2s
     * side generates the id and registers its proxy connection under it (LobbyLink);
     * the switch engine resolves the lobby channel's proxy connection through it. This
     * replaces the former local/remote port pairing assumption, which collided with the
     * HAProxy real-client-address rewrite of the accepted channel's remoteAddress.
     */
    public static final AttributeKey<java.util.UUID> LOBBY_LINK_ID = AttributeKey.valueOf("connectplus_lobby_link_id");

    /** Original login identity on c2p, captured after ViaProxy's client authentication. */
    public static final AttributeKey<PlayerIdentity> PLAYER_IDENTITY = AttributeKey.valueOf("connectplus_player_identity");

    /**
     * The authentication identity of the c2p connection (verified Java, verified
     * Bedrock via the bridge, or unverified). Set once at the auth-completion point;
     * the only later transition is UNVERIFIED -> VERIFIED_BEDROCK when the bridge
     * resolve answers for exactly this connection.
     */
    public static final AttributeKey<dev.connectplus.identity.ClientIdentity> CLIENT_IDENTITY =
            AttributeKey.valueOf("connectplus_client_identity");

    /** Published before the initial identity; completes once this connection's authentication resolves. */
    public static final AttributeKey<java.util.concurrent.CompletableFuture<dev.connectplus.identity.ClientIdentity>> CLIENT_IDENTITY_RESOLUTION =
            AttributeKey.valueOf("connectplus_client_identity_resolution");

    /** The endpoint that verified this channel; its live registration owns the proof. */
    public static final AttributeKey<dev.connectplus.bridge.BedrockBridgeEndpoint> BEDROCK_BRIDGE_ENDPOINT =
            AttributeKey.valueOf("connectplus_bedrock_bridge_endpoint");

    /**
     * ConnectPlus's per-c2p connection id (bridge protocol v1 §3), generated in the
     * Client2ProxyChannelInitializeEvent PRE phase.
     */
    public static final AttributeKey<java.util.UUID> CONNECTION_ID = AttributeKey.valueOf("connectplus_connection_id");

    /**
     * The raw socket addresses of the c2p channel, captured in the PRE phase before
     * Geyser-ViaProxy can rewrite them (bridge spec §2.1). These — not the rewritten
     * player IP — are what the bridge RESOLVE request reports.
     */
    public static final AttributeKey<java.net.SocketAddress> RAW_LOCAL_ADDRESS =
            AttributeKey.valueOf("connectplus_raw_local_address");

    public static final AttributeKey<java.net.SocketAddress> RAW_REMOTE_ADDRESS =
            AttributeKey.valueOf("connectplus_raw_remote_address");

    /** Lobby login lives for the client's connection, including offline backend visits. */
    public static final AttributeKey<PlayerSession> LOBBY_SESSION = AttributeKey.valueOf("connectplus_lobby_session");

    /** Current ConnectPlus window on c2p; 0 means its chest GUI is closed. */
    public static final AttributeKey<Integer> LOBBY_WINDOW_ID = AttributeKey.valueOf("connectplus_lobby_window_id");

    /**
     * The independent platform classification signal for the access lists,
     * written by {@code ClientIdentityCapture} when the bridge matched a bedrock
     * session on the raw address pair but could not hand out a trusted identity.
     * Read-only for everyone else; never upgrades the {@link #CLIENT_IDENTITY}.
     */
    public static final AttributeKey<ClientPlatformInspector.Classification> PLATFORM_CLASSIFICATION =
            AttributeKey.valueOf("connectplus_platform_classification");

    /** CP-owned terminal refusal survives registry cleanup and blocks delayed recoveries. */
    public static final AttributeKey<Boolean> ACCESS_DENIED = AttributeKey.valueOf("connectplus_access_denied");

    /** Completion of this channel's first admission, independent of identity verification. */
    public static final AttributeKey<java.util.concurrent.CompletableFuture<Boolean>> ACCESS_ADMISSION =
            AttributeKey.valueOf("connectplus_access_admission");

    private CPAttributeKeys() {
    }
}
