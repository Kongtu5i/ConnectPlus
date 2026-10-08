package dev.connectplus.access;

import dev.connectplus.identity.ProfileKey;

import java.util.Objects;
import java.util.UUID;

/**
 * The primary list identity of one account: the client type plus the canonical
 * primary identifier (Java entry UUID or Bedrock XUID). This is a list key, not
 * an authentication proof. Equality includes the client type.
 */
public final class AccessKey {

    /** The client platform of a list entry or connection subject. */
    public enum ClientType { JAVA, BEDROCK }

    private final ClientType type;
    private final String id;

    private AccessKey(final ClientType type, final String id) {
        this.type = type;
        this.id = id;
    }

    /** The Java key of one entry UUID; the UUID is stored in canonical lowercase form. */
    public static AccessKey javaUuid(final UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return new AccessKey(ClientType.JAVA, uuid.toString());
    }

    /**
     * The Bedrock key of one canonical XUID. Validation reuses the project's
     * single XUID format authority ({@link ProfileKey}); this is a format check,
     * never an authentication statement.
     */
    public static AccessKey bedrockXuid(final String xuid) {
        Objects.requireNonNull(xuid, "xuid");
        ProfileKey.bedrockProfile(xuid); // rejects non-canonical input
        return new AccessKey(ClientType.BEDROCK, xuid);
    }

    public ClientType type() {
        return this.type;
    }

    /** The canonical primary identifier: lowercase hyphenated UUID or canonical decimal XUID. */
    public String id() {
        return this.id;
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof final AccessKey other
                && this.type == other.type && this.id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.type, this.id);
    }

    @Override
    public String toString() {
        return this.type + "/" + this.id;
    }
}
