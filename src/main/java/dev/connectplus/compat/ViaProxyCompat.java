package dev.connectplus.compat;

import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.ViaProxyPlugin;

import javax.annotation.Nullable;
import java.io.File;

/**
 * The only class in the plugin allowed to touch ViaProxy internals directly.
 * Everything else goes through this facade so a ViaProxy upgrade only means adapting this file.
 */
public final class ViaProxyCompat {

    private static ViaProxyPlugin plugin;

    private ViaProxyCompat() {
    }

    public static void init(final ViaProxyPlugin plugin) {
        ViaProxyCompat.plugin = plugin;
    }

    /**
     * Whether {@link #init} has run; false in environments without ViaProxy (unit tests).
     */
    public static boolean isInitialized() {
        return ViaProxyCompat.plugin != null;
    }

    /**
     * Returns the plugin data folder, or null when the plugin is not initialized.
     */
    public static File dataFolder() {
        return plugin == null ? null : plugin.getDataFolder();
    }

    /**
     * The running ViaProxy version when resolvable (null when the host is unavailable).
     * The bridge endpoint compares it against the descriptor's
     * declared viaproxyVersion and refuses mismatches (bridge spec §2.1).
     */
    public static @Nullable String runningVersion() {
        try {
            // VERSION is a compile-time constant: direct access would inline the
            // build dependency version instead of reading the running host.
            return (String) ViaProxy.class.getField("VERSION").get(null);
        } catch (final Throwable e) {
            return null;
        }
    }

    /**
     * The ConnectPlus plugin version as reported by the RUNNING host's plugin
     * model (null without a host, e.g. unit tests). Never a compile-time
     * constant: the console version query must show what actually runs.
     */
    public static @Nullable String pluginVersion() {
        return plugin == null ? null : plugin.getVersion();
    }

    /**
     * The actual listen address of the host's current proxy server channel
     * (null when the host is unavailable or no proxy server runs). Read-only
     * inspection of the live channel; the console labels it as the host's
     * address, distinct from the lobby's internal one.
     */
    public static @Nullable java.net.SocketAddress hostListenAddress() {
        try {
            final net.raphimc.netminecraft.netty.connection.NetServer server = ViaProxy.getCurrentProxyServer();
            if (server == null || server.getChannel() == null) {
                return null;
            }
            return server.getChannel().localAddress();
        } catch (final Throwable e) {
            return null;
        }
    }

    public static void registerListener(final Object listener) {
        ViaProxy.EVENT_MANAGER.register(listener);
    }

    public static void unregisterListener(final Object listener) {
        ViaProxy.EVENT_MANAGER.unregister(listener);
    }

    /** Optional Geyser public API; no hard dependency on the installed Geyser version. */
    public static @Nullable String bedrockClientVersion(final java.util.UUID wireUuid, final String xuid) {
        try {
            final var manager = ViaProxy.getPluginManager();
            if (manager == null) return null;
            for (final var hostPlugin : manager.getPlugins()) {
                if (!hostPlugin.getName().equalsIgnoreCase("Geyser-ViaProxy")
                        && !hostPlugin.getClass().getName().startsWith("org.geysermc.geyser.")) continue;
                return bedrockClientVersion(hostPlugin.getClass().getClassLoader(), wireUuid, xuid);
            }
        } catch (final RuntimeException | LinkageError unavailable) {
            return null;
        }
        return null;
    }

    static @Nullable String bedrockClientVersion(final ClassLoader loader, final java.util.UUID wireUuid, final String xuid) {
        try {
            final Class<?> apiType = Class.forName("org.geysermc.geyser.api.GeyserApi", true, loader);
            final Object api = apiType.getMethod("api").invoke(null);
            if (api == null) return null;
            final Object connection = xuid != null
                    ? apiType.getMethod("connectionByXuid", String.class).invoke(api, xuid)
                    : wireUuid != null ? apiType.getMethod("connectionByUuid", java.util.UUID.class).invoke(api, wireUuid) : null;
            if (connection == null) return null;
            final Class<?> connectionType = Class.forName("org.geysermc.geyser.api.connection.GeyserConnection", true, loader);
            if (wireUuid != null && !wireUuid.equals(connectionType.getMethod("javaUuid").invoke(connection))) return null;
            final Object version = connectionType.getMethod("version").invoke(connection);
            return version instanceof String text && !text.isBlank() ? text : null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** True only when public host metadata proves there is no Geyser transport. */
    public static boolean isJavaOnlyHost() {
        try {
            final var manager = ViaProxy.getPluginManager();
            return manager != null && manager.getPlugins().stream().noneMatch(hostPlugin ->
                    hostPlugin.getName().equalsIgnoreCase("Geyser-ViaProxy")
                            || hostPlugin.getClass().getName().startsWith("org.geysermc.geyser."));
        } catch (final RuntimeException unavailable) {
            return false; // Missing host metadata is not proof of the platform.
        }
    }
}
