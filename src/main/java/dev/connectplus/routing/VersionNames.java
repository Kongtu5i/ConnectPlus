package dev.connectplus.routing;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Loose lookup of protocol versions by name against ViaVersion's protocol list.
 * Matching is deliberately forgiving because the name comes from chat input:
 * trim, then exact match, then case-insensitive match, then a unique prefix
 * hit; anything else (unknown or ambiguous) resolves to null.
 */
public final class VersionNames {

    private VersionNames() {
    }

    /**
     * Finds the protocol version with the given name, or null.
     */
    @Nullable
    public static ProtocolVersion find(final String name) {
        if (name == null) {
            return null;
        }
        final String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        for (final ProtocolVersion protocol : ProtocolVersion.getProtocols()) {
            if (protocol.getName().equals(trimmed)) {
                return protocol;
            }
        }

        final String lowered = trimmed.toLowerCase(Locale.ROOT);
        for (final ProtocolVersion protocol : ProtocolVersion.getProtocols()) {
            if (protocol.getName().toLowerCase(Locale.ROOT).equals(lowered)) {
                return protocol;
            }
        }

        ProtocolVersion uniquePrefix = null;
        for (final ProtocolVersion protocol : ProtocolVersion.getProtocols()) {
            if (protocol.getName().toLowerCase(Locale.ROOT).startsWith(lowered)) {
                if (uniquePrefix != null) {
                    return null; //more than one protocol name shares this prefix
                }
                uniquePrefix = protocol;
            }
        }
        return uniquePrefix;
    }

}
