package dev.connectplus.utils;

import dev.connectplus.config.CPConfig;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

/**
 * Player-facing target address policy: with {@code blockLocalTargets} enabled
 * (the default), players may not target the proxy host itself or the
 * loopback/private/link-local ranges. The proxy dials every connect target
 * from its own machine, so without the block any player could reach services
 * co-located with the proxy (other Minecraft servers, admin panels, ...) by
 * addressing them directly — an SSRF-style reach into the host. The block
 * covers:
 *
 * <ul>
 *   <li>the loopback/any/link-local and private IPv4 ranges
 *       ({@link InetUtils#isLocal}),</li>
 *   <li>every address assigned to one of this host's network interfaces,
 *       which also covers the host's public IPv4 / global IPv6 addresses that
 *       no range check can recognize as "this machine".</li>
 * </ul>
 *
 * <p>Unresolvable host names are accepted and resolved when the proxy connects
 * (best effort, same as the pre-guard behaviour). The engine-internal paths —
 * the lobby fallback and the reconnect chain — never consult this guard: they
 * re-dial the player's already validated target or the built-in lobby.</p>
 */
public final class TargetAddressGuard {

    private TargetAddressGuard() {
    }

    /**
     * Whether the given target host may be dialed for a player; the config
     * switch {@code blockLocalTargets} (default on) governs the check.
     */
    public static boolean isAllowedHost(final String host) {
        if (!CPConfig.blockLocalTargets) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            for (final InetAddress resolved : InetAddress.getAllByName(host)) {
                if (InetUtils.isLocal(resolved) || isOwnHostAddress(resolved)) {
                    return false;
                }
            }
            return true;
        } catch (final UnknownHostException ignored) {
            return true; //unresolvable hosts are accepted; resolution happens when the proxy connects
        }
    }

    /**
     * Whether the address belongs to one of this host's network interfaces
     * (including loopback), which no address-range check can recognize.
     */
    static boolean isOwnHostAddress(final InetAddress resolved) {
        try {
            final Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                final Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (resolved.equals(addresses.nextElement())) {
                        return true;
                    }
                }
            }
        } catch (final SocketException ignored) {
            //interface enumeration unavailable: the range checks still apply
        }
        return false;
    }

}
