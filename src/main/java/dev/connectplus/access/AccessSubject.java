package dev.connectplus.access;

import java.util.Objects;
import java.util.UUID;

/**
 * The identity of one connection as it enters the access check: the platform,
 * display name, entry UUID and the XUID when one is available. The primary
 * list identifier and any authentication status are separate concerns — this
 * type never proves an identity, it only carries what the lists match against.
 */
public record AccessSubject(AccessKey.ClientType clientType, String name, UUID entryUuid, String xuid) {

    public AccessSubject {
        // null is a transient UNKNOWN platform, never a persisted list-entry type.
        if (clientType == null && xuid != null) {
            throw new IllegalArgumentException("unknown platform cannot carry an XUID");
        }
        if (clientType == AccessKey.ClientType.JAVA) {
            Objects.requireNonNull(entryUuid, "a java subject must carry its entry UUID");
            if (xuid != null) {
                AccessKey.bedrockXuid(xuid); // an auxiliary XUID must still be canonical
            }
        } else {
            if (xuid != null) {
                AccessKey.bedrockXuid(xuid); // canonical form check
            }
        }
    }

    /**
     * The primary list key of this subject, or null when the platform is known
     * bedrock but no XUID is available (no UUID fallback ever happens).
     */
    public AccessKey key() {
        if (this.clientType == null) return null;
        return switch (this.clientType) {
            case JAVA -> AccessKey.javaUuid(this.entryUuid);
            case BEDROCK -> this.xuid == null ? null : AccessKey.bedrockXuid(this.xuid);
        };
    }
}
