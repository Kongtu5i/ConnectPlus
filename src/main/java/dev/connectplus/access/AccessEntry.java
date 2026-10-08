package dev.connectplus.access;

import java.util.Objects;
import java.util.UUID;

/**
 * One saved list record: the display name plus the primary and (optional)
 * auxiliary identifiers of one account, matching one JSON entry of
 * whitelist.json / blacklist.json. The auxiliary identifier never takes part
 * in matching.
 */
public record AccessEntry(AccessKey.ClientType clientType, String name, UUID uuid, String xuid) {

    public AccessEntry {
        Objects.requireNonNull(clientType, "clientType");
        switch (clientType) {
            case JAVA -> {
                Objects.requireNonNull(uuid, "uuid for a java entry");
                if (xuid != null) {
                    AccessKey.bedrockXuid(xuid); // auxiliary XUID must still be canonical
                }
            }
            case BEDROCK -> {
                Objects.requireNonNull(xuid, "xuid for a bedrock entry");
                AccessKey.bedrockXuid(xuid); // canonical form check
            }
        }
    }

    /** The key this entry is stored and matched under: only the type's primary identifier. */
    public AccessKey key() {
        return switch (this.clientType) {
            case JAVA -> AccessKey.javaUuid(this.uuid);
            case BEDROCK -> AccessKey.bedrockXuid(this.xuid);
        };
    }

    /** The name for display; null means "added by identifier, name unknown". */
    public String displayName() {
        return this.name == null || this.name.isEmpty() ? null : this.name;
    }
}
