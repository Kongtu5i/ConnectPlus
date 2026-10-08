package dev.connectplus.compat;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.mojang.authlib.GameProfile;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_0;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_1;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_3;
import dev.connectplus.accounts.CPAccount;

import javax.annotation.Nullable;
import net.lenni0451.mcping.MCPing;
import net.lenni0451.mcping.pings.sockets.impl.factories.TCPSocketFactory;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.viaproxy.proxy.external_interface.ExternalInterface;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import net.raphimc.viaproxy.proxy.session.UserOptions;
import net.raphimc.viaproxy.util.AddressUtil;
import net.raphimc.viaproxy.util.ProtocolVersionDetector;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.protocoltranslator.ProtocolTranslator;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Compat wrappers for the deeper ViaProxy internals the switch engine needs
 * (player data filling, protocol auto detection, address parsing, config reads).
 * Keeps the engine's own imports limited to ViaProxy's plugin-facing proxy API.
 */
public final class CompatSwitchSupport {

    private CompatSwitchSupport() {
    }

    /**
     * Fills the account's game profile and chat session data on the (new) p2s
     * connection's user connection, mirroring ViaProxy's original login flow.
     * May throw (e.g. {@code CloseAndReturn} via kickClient) on outdated tokens.
     */
    public static void fillPlayerData(final ProxyConnection proxyConnection) {
        ExternalInterface.fillPlayerData(proxyConnection);
    }

    /**
     * Backend-only identity reset: a null account alone leaves ViaProxy's previous
     * game profile and signed login hello intact. The original c2p owner identity
     * and the lobby's saved account are deliberately kept outside this reset.
     */
    public static void prepareOfflinePlayerData(final ProxyConnection proxyConnection, final String playerName) {
        final UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
        proxyConnection.setGameProfile(new GameProfile(offlineId, playerName));
        proxyConnection.setLoginHelloPacket(new C2SLoginHelloPacket(playerName, null, null, null, offlineId));
        final var userConnection = proxyConnection.getUserConnection();
        if (userConnection != null) {
            userConnection.remove(ChatSession1_19_0.class);
            userConnection.remove(ChatSession1_19_1.class);
            userConnection.remove(ChatSession1_19_3.class);
        }
    }

    /**
     * Wraps the ConnectPlus account into ViaProxy's UserOptions for the backend login.
     */
    public static UserOptions userOptions(@Nullable final CPAccount account) {
        return new UserOptions(null, CpAccounts.unwrapViaProxyAccount(account));
    }

    /**
     * The public Auto Detect selector is a request to probe, not a wire protocol.
     * Its field is supported on official ViaProxy 3.4.13 and 3.4.14. Recognize
     * only that native placeholder and the plugin's unset (null) representation.
     */
    public static boolean isAutomaticVersion(@Nullable final ProtocolVersion version) {
        return version == null || version == ProtocolTranslator.AUTO_DETECT_PROTOCOL;
    }

    /**
     * Resolves the protocol version of a target server by a short status ping
     * (ViaProxy's auto detect); throws a runtime exception on failure.
     *
     * <p>ViaProxy moved this helper between releases (3.4.13:
     * {@code ProtocolVersionDetector.get}, sync; 3.4.14: removed,
     * {@code StatusPingUtil.detectProtocolVersion}, async-future), so the call
     * goes through the 3.4.13 name first and reflectively falls back to the
     * 3.4.14 one — a hard reference to either name crashes the other release
     * with NoSuchFieldError/NoClassDefFoundError mid-connection (public
     * deployment finding).</p>
     */
    public static ProtocolVersion detectProtocolVersion(final SocketAddress address, final ProtocolVersion clientVersion) {
        final ProtocolVersion advertised = detectAdvertisedProtocol(address, clientVersion);
        if (advertised.getOriginalVersion() != clientVersion.getOriginalVersion()) return advertised;

        // Velocity's local status response echoes any supported querying client.
        // An unknown status protocol asks it to advertise its maximum instead.
        // This is a status-only probe: the actual login still uses the client's
        // protocol and ViaVersion's normal translation to the selected target.
        try {
            final var response = MCPing.pingModern(-1, true)
                    .tcpSocketFactory(new TCPSocketFactory())
                    .address(address).noResolve().timeout(3000, 3000).getSync();
            if (response != null && response.version != null && ProtocolVersion.isRegistered(response.version.protocol)) {
                final ProtocolVersion independent = ProtocolVersion.getProtocol(response.version.protocol);
                if (!isAutomaticVersion(independent)) return independent;
            }
        } catch (final RuntimeException unsupportedProbe) {
            // Some servers reject unknown status handshakes. Their successful
            // original response remains usable; never invent a fallback version.
        }
        return advertised;
    }

    private static ProtocolVersion detectAdvertisedProtocol(final SocketAddress address, final ProtocolVersion probeVersion) {
        try {
            return ProtocolVersionDetector.get(address, probeVersion);
        } catch (final NoSuchMethodError | NoClassDefFoundError legacyRemoved) {
            try {
                final Class<?> statusPingUtil = Class.forName("net.raphimc.viaproxy.util.StatusPingUtil");
                final java.util.concurrent.Future<?> future = (java.util.concurrent.Future<?>) statusPingUtil
                        .getMethod("detectProtocolVersion", SocketAddress.class, ProtocolVersion.class)
                        .invoke(null, address, probeVersion);
                return (ProtocolVersion) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final Exception e) {
                throw new IllegalStateException("Protocol auto detection failed (" + e + ")", e);
            }
        }
    }

    /**
     * Parses a {@code host:port} (or bare host) address for the given target version.
     */
    public static SocketAddress parseAddress(final String address, final ProtocolVersion version) {
        return AddressUtil.parse(dev.connectplus.session.ServerAddress.normalize(address), version);
    }

    /**
     * Human readable form of a socket address (for logs and the handshake address).
     */
    public static String toString(final SocketAddress address) {
        return AddressUtil.toString(address);
    }

    /**
     * The login hello packet the proxy replays towards backends and the lobby.
     */
    public static C2SLoginHelloPacket loginHello(final ProxyConnection proxyConnection) {
        return proxyConnection.getLoginHelloPacket();
    }

    /**
     * Whether ViaProxy is configured to send an HAProxy preamble to backends.
     */
    public static boolean useBackendHaProxy() {
        return ViaProxy.getConfig().useBackendHaProxy();
    }

}
