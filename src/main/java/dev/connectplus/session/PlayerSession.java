package dev.connectplus.session;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.accounts.CPAccount;
import dev.connectplus.identity.ProfileKey;
import io.netty.channel.Channel;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Function;

/**
 * One player currently sitting in the lobby. Derived from MiniConnect's
 * PlayerConfig (MIT, Copyright (c) 2024 Lenni0451). Modified in this repo:
 * the persisted data lives in a {@link PlayerData} loaded from the PlayerStore
 * (M3) and the target version is optional (null = auto detect).
 */
public class PlayerSession {

    /**
     * The original protocol identity of the entry connection (wire identity);
     * never replaced by linking — every outgoing protocol packet keeps it. The
     * protected profile owner lives in {@link #profileKey}, not here.
     */
    public final UUID uuid;
    /** The original protocol name of the entry connection; never replaced by linking. */
    public final String name;

    /**
     * ConnectPlus's per-connection id (bridge protocol v1 §3): the real client's
     * c2p channel id, stable across server switches and lobby returns, so the
     * session keeps one registry slot for its whole c2p lifetime. Bare
     * diagnostic lobby joins without a c2p link have no separate connection
     * identity; their entry protocol uuid doubles as the connection id. Never
     * used to select a profile file — that is the {@link #profileKey}'s job.
     */
    public transient volatile UUID connectionId;

    /**
     * The right-of-use generation of this session: set from the granted
     * {@link #lease} and compared by {@link SessionRegistry#release} so a
     * stale release cannot remove a newer holder's slot. 0 = no protected
     * right of use.
     */
    public transient volatile long generation;

    /**
     * The protected profile this session is using (the profile OWNER, resolved
     * from the verified {@code ClientIdentity} through the link snapshot); null
     * without a protected profile (unverified identities get no protected path
     * at all, bare diagnostic connections keep the legacy entry-uuid load).
     */
    @Nullable
    public transient volatile ProfileKey profileKey;

    /**
     * The lease granting the right to use {@link #profileKey}; null without a
     * protected profile. Survives switches and lobby returns (it binds to the
     * c2p, not to a lobby channel).
     */
    @Nullable
    public transient volatile SessionLease lease;

    /**
     * The real client's c2p channel behind this session — the channel whose
     * lifetime the lease binds (§5: 切服/返回大厅/p2s 断开不释放，c2p 退出才释放).
     * Null for bare diagnostic connections, which never hold a lease. Set per
     * loadSession; stable across switches and lobby returns.
     */
    @Nullable
    public transient volatile Channel c2pChannel;

    /**
     * Set by the {@link AccountSessionCoordinator} when this session's lease
     * was invalidated by a displacement (顶号) or a coordination exit: every
     * late callback of this session must leave the profile alone — the new
     * holder owns it now (plan Review Focus 1).
     */
    public transient volatile boolean displaced;

    /**
     * The target server address in {@code host:port} form as entered by the player; null until set.
     */
    @Nullable
    public String serverAddress;

    /**
     * The protocol version to connect with; null = auto detect on connect.
     */
    @Nullable
    public ProtocolVersion targetVersion;

    /**
     * The stored account of this player (restored from the encrypted token blob);
     * null while not logged in.
     */
    @Nullable
    public volatile CPAccount account;

    /**
     * The persisted per-player data (bookmarks + encrypted account blob).
     */
    @Nullable
    public transient volatile PlayerData playerData;

    /**
     * Guards against concurrent device-code logins for one session; only touched
     * on the connection's event loop thread.
     */
    public transient volatile boolean loginInProgress;

    /** Current accepted lobby channel; old asynchronous work loses ownership on a switch. */
    @Nullable
    public transient volatile Channel lobbyChannel;

    /** Survives lobby returns, but never persists beyond this client connection. */
    private transient int lobbyWindowSequence;

    /** Vanilla-compatible window IDs; a new lobby handler cannot reuse the last one. */
    public synchronized int nextLobbyWindowId() {
        this.lobbyWindowSequence = this.lobbyWindowSequence % 100 + 1;
        return this.lobbyWindowSequence;
    }

    /**
     * Consumes the next chat message while a GUI input is pending; returning true consumes and clears it.
     */
    @Nullable
    public transient Function<String, Boolean> chatListener;

    /**
     * The original handshake host the client used to reach the proxy; replayed in the transfer packet.
     */
    public transient String handshakeAddress;
    /**
     * The original handshake port the client used to reach the proxy; replayed in the transfer packet.
     */
    public transient int handshakePort;
    /**
     * The client's real protocol version (from the HAProxy TLV or the raw handshake); drives GUI decisions.
     */
    @Nullable
    public transient ProtocolVersion clientVersion;

    /**
     * The client settings locale ("en_us", "zh_cn", ...); null until the client
     * sent its client information packet (M6 F1.4 bilingual texts). Lower-case
     * comparison only, never persisted.
     */
    @Nullable
    public transient String locale;

    public PlayerSession(final UUID uuid, final String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public boolean offlineMode() {
        return this.playerData != null && this.playerData.offlineMode;
    }

}
