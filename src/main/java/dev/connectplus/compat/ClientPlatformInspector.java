package dev.connectplus.compat;

import dev.connectplus.identity.ClientIdentity;
import io.netty.channel.Channel;

/**
 * The independent platform classification for the access lists, per the
 * official-API capability report (2026-10-07 §3): a verified java identity is
 * JAVA, a verified bedrock identity is BEDROCK, a bridge answer that matched a
 * bedrock session on the raw address pair without a trusted identity is
 * BEDROCK, and everything else stays UNKNOWN.
 *
 * <p>UNKNOWN is deliberately never bedrock: an offline java player and a
 * bedrock player whose bridge failed must not share one class. The inspection
 * never upgrades the captured {@link ClientIdentity}'s authentication level —
 * trusted identity stays the capture's business.</p>
 */
public final class ClientPlatformInspector {

    private final java.util.function.BooleanSupplier javaOnlyHost;

    public ClientPlatformInspector() {
        this(ViaProxyCompat::isJavaOnlyHost);
    }

    public ClientPlatformInspector(final java.util.function.BooleanSupplier javaOnlyHost) {
        this.javaOnlyHost = java.util.Objects.requireNonNull(javaOnlyHost);
    }

    /** The platform of one connection as far as the access lists care. */
    public enum Classification { JAVA, BEDROCK, UNKNOWN }

    /**
     * Classifies the connection behind this c2p channel. Read-only.
     */
    public Classification inspect(final Channel c2p) {
        if (c2p == null) {
            return Classification.UNKNOWN;
        }
        final ClientIdentity identity = c2p.attr(CPAttributeKeys.CLIENT_IDENTITY).get();
        if (identity != null) {
            return switch (identity.kind()) {
                case VERIFIED_JAVA -> Classification.JAVA;
                case VERIFIED_BEDROCK -> Classification.BEDROCK;
                case UNVERIFIED -> this.unverifiedClassification(c2p);
            };
        }
        return this.unverifiedClassification(c2p);
    }

    private Classification unverifiedClassification(final Channel c2p) {
        final ClientPlatformInspector.Classification signal =
                c2p.attr(CPAttributeKeys.PLATFORM_CLASSIFICATION).get();
        if (signal == Classification.BEDROCK) return Classification.BEDROCK;
        return c2p.attr(CPAttributeKeys.CLIENT_IDENTITY).get() != null && this.javaOnlyHost.getAsBoolean()
                ? Classification.JAVA : Classification.UNKNOWN;
    }
}
