package dev.connectplus.compat;

import dev.connectplus.CoreMain;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.session.PlayerSession;
import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.lenni0451.optconfig.ConfigContext;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.events.ViaProxyLoadedEvent;
import net.raphimc.viaproxy.plugins.events.ProxyStartEvent;

import javax.annotation.Nullable;
import java.io.IOException;

/** Stored Microsoft credentials require both verified proxy identity and admin opt-in. */
public final class AccountLoginPolicy {
    private final @Nullable ConfigContext<CPConfig> configContext;

    public AccountLoginPolicy(@Nullable final ConfigContext<CPConfig> configContext) {
        this.configContext = configContext;
    }

    /** Java entry authentication policy; connection identity must also be verified. */
    public static boolean isEnabled() {
        final var config = ViaProxy.getConfig();
        return config != null && config.isProxyOnlineMode() && CPConfig.allowAccountLogin;
    }

    /**
     * Per-connection authorization for credential/account operations: only a trusted
     * identity (verified through the real Java auth branch or through the bridge
     * provider) may use them, and the admin disable always wins. This deliberately
     * ignores the global online-mode flag for bridge-verified Bedrock identities —
     * their trust comes from the registered provider, not from the proxy switch.
     * Enabling geyser-support never flips {@link CPConfig#allowAccountLogin} itself.
     */
    public static boolean isAllowedFor(final @Nullable ClientIdentity identity) {
        if (!CPConfig.allowAccountLogin) return false;
        if (identity == null) return false;
        return switch (identity.kind()) {
            case VERIFIED_JAVA -> isEnabled();
            case VERIFIED_BEDROCK -> CPConfig.GeyserSupport.enabled;
            case UNVERIFIED -> false;
        };
    }

    /** Reads the original authenticated client, including while its backend changes. */
    public static boolean isAllowedForConnection(final @Nullable Channel client) {
        if (client == null || !client.isActive()) return false;
        // The access gate first: a pending or denied connection (or an unfinished
        // blacklist inheritance) cannot use account credentials at all.
        final dev.connectplus.access.AccessGate gate = dev.connectplus.access.AccessGate.installed();
        if (gate != null && !gate.protectedOperationsAllowed(client)) return false;
        final ClientIdentity identity = client.attr(CPAttributeKeys.CLIENT_IDENTITY).get();
        if (!isAllowedFor(identity)) return false;
        if (identity.kind() != ClientIdentity.Kind.VERIFIED_BEDROCK) return true;
        final var endpoint = client.attr(CPAttributeKeys.BEDROCK_BRIDGE_ENDPOINT).get();
        return endpoint != null && endpoint.isCurrentIdentity(identity, client);
    }

    /** A missing identity or displaced session cannot use account credentials. */
    public static boolean isAllowedForSession(final @Nullable PlayerSession session) {
        return session != null && !session.displaced && isAllowedForConnection(session.c2pChannel);
    }

    @EventHandler
    public void onViaProxyLoaded(final ViaProxyLoadedEvent event) {
        // Plugin enable precedes ViaProxy config/CLI loading. Enforce only after
        // that lifecycle stage, so a missing early config cannot disable a safe setup.
        this.enforceProxyMode();
    }

    @EventHandler
    public void onProxyStart(final ProxyStartEvent event) {
        // The GUI can change online mode after loading, before starting/restarting.
        this.enforceProxyMode();
    }

    private void enforceProxyMode() {
        // Bridge-authenticated Bedrock players can use accounts with Java entry
        // authentication off. Keep the administrator's setting in that deployment;
        // the per-connection policy still rejects every unverified Java connection.
        if (CPConfig.GeyserSupport.enabled) return;
        final var config = ViaProxy.getConfig();
        if (config != null && config.isProxyOnlineMode()) return;
        if (!CPConfig.allowAccountLogin) return;
        CPConfig.allowAccountLogin = false;
        CoreMain.logger().info("Disabled ConnectPlus account login because proxy-online-mode is false; saved accounts will not be restored or used");
        if (this.configContext == null) return;
        try {
            this.configContext.save();
        } catch (final IOException | IllegalAccessException e) {
            // Runtime checks still deny access even when the config file is read-only.
            CoreMain.logger().error("Account login is disabled, but allowAccountLogin=false could not be saved to config.yml", e);
        }
    }
}
