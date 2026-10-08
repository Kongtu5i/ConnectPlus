package dev.connectplus.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * The protected owner of a player profile: either a verified Java account UUID
 * or a verified Bedrock XUID. ProfileKey is the sole owner of path-key
 * validation: both factory methods validate at construction and only produce
 * path-safe file names inside their namespace directory
 * ({@code players/java/<JavaUUID>.json}, {@code players/bedrock/<XUID>.json}),
 * so a key value can never take part in a path traversal.
 *
 * <p>The two namespaces are never interchangeable: equality includes the kind,
 * and a bedrock profile has no Java UUID.</p>
 */
public final class ProfileKey {

    /** The protected namespace of this key. */
    public enum Kind { JAVA, BEDROCK }

    /**
     * The largest valid XUID: 2^64 - 1. XUIDs are unsigned 64-bit values and
     * are never parsed into a signed Java long; validation compares the decimal
     * digits directly (equal-length digit strings compare lexicographically
     * like numbers).
     */
    private static final String MAX_XUID = "18446744073709551615";

    private final Kind kind;
    private final String value;

    private ProfileKey(final Kind kind, final String value) {
        this.kind = kind;
        this.value = value;
    }

    /**
     * The profile of a verified Java account. The value is the standard
     * lowercase hyphenated UUID form.
     */
    public static ProfileKey javaProfile(final UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return new ProfileKey(Kind.JAVA, uuid.toString().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The profile of a verified Bedrock identity. The XUID must already be the
     * canonical decimal form: ASCII digits only, no leading zeros,
     * 1..18446744073709551615. Non-canonical input is rejected, not normalized.
     */
    public static ProfileKey bedrockProfile(final String xuid) {
        validateXuid(xuid);
        return new ProfileKey(Kind.BEDROCK, xuid);
    }

    /**
     * Validates a canonical XUID without ever parsing it into a signed long:
     * ASCII digits only, no leading zeros, at most 20 digits and, for 20-digit
     * values, not greater than 18446744073709551615.
     */
    private static void validateXuid(final String xuid) {
        if (xuid == null || xuid.isEmpty()) {
            throw new IllegalArgumentException("XUID must be a non-empty decimal string");
        }
        for (int i = 0; i < xuid.length(); i++) {
            final char c = xuid.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("XUID must contain ASCII digits only: " + xuid);
            }
        }
        if (xuid.charAt(0) == '0') {
            throw new IllegalArgumentException("XUID must be a positive decimal without leading zeros: " + xuid);
        }
        if (xuid.length() > MAX_XUID.length()
                || (xuid.length() == MAX_XUID.length() && xuid.compareTo(MAX_XUID) > 0)) {
            throw new IllegalArgumentException("XUID is outside the valid range 1.." + MAX_XUID + ": " + xuid);
        }
    }

    /** The protected namespace of this key. */
    public Kind kind() {
        return this.kind;
    }

    /**
     * The normalized path key of this profile: the lowercase hyphenated UUID
     * for JAVA, the canonical decimal XUID for BEDROCK. Guaranteed path-safe.
     */
    public String value() {
        return this.value;
    }

    /** The data file name of this profile inside its namespace directory. */
    public String fileName() {
        return this.value + ".json";
    }

    /**
     * The Java account UUID of a JAVA key; null for a BEDROCK key (a bedrock
     * profile has no Java UUID).
     */
    public UUID javaUuid() {
        return this.kind == Kind.JAVA ? UUID.fromString(this.value) : null;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (!(other instanceof ProfileKey)) return false;
        final ProfileKey that = (ProfileKey) other;
        return this.kind == that.kind && this.value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return this.kind.hashCode() * 31 + this.value.hashCode();
    }

    @Override
    public String toString() {
        return this.kind + "(" + this.value + ")";
    }

}
