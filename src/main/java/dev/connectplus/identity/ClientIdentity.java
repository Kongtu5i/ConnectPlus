package dev.connectplus.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * The authentication identity of one connection: the original wire identity the
 * entry protocol presented (never replaced by linking) plus the verified
 * identity snapshot. VERIFIED_BEDROCK keeps the xuid, providerEpoch and
 * bridgeSessionId of the verification; VERIFIED_JAVA keeps the actually
 * verified premium Java UUID. UNVERIFIED carries no protected identity and must
 * never resolve to a ProfileKey (a global online-mode flag, a NO_MATCH bridge
 * answer or a plain login event are not verification).
 */
public final class ClientIdentity {

    public enum Kind { VERIFIED_JAVA, VERIFIED_BEDROCK, UNVERIFIED }

    private final Kind kind;
    private final UUID wireUuid;
    private final String wireName;
    private final UUID verifiedJavaUuid;
    private final String xuid;
    private final UUID providerEpoch;
    private final String bridgeSessionId;

    private ClientIdentity(final Kind kind, final UUID wireUuid, final String wireName,
                           final UUID verifiedJavaUuid, final String xuid,
                           final UUID providerEpoch, final String bridgeSessionId) {
        this.kind = kind;
        this.wireUuid = wireUuid;
        this.wireName = wireName;
        this.verifiedJavaUuid = verifiedJavaUuid;
        this.xuid = xuid;
        this.providerEpoch = providerEpoch;
        this.bridgeSessionId = bridgeSessionId;
    }

    /**
     * An identity that has not been authenticated: it may keep the basic
     * connection alive but grants no access to protected profiles, the link
     * index or persistent credentials.
     */
    public static ClientIdentity unverified(final UUID wireUuid, final String wireName) {
        return new ClientIdentity(Kind.UNVERIFIED, wireUuid, wireName, null, null, null, null);
    }

    /**
     * A Java identity whose premium login was actually verified on this
     * connection. The verified UUID is the account's real profile UUID, not a
     * wire or offline UUID.
     */
    public static ClientIdentity verifiedJava(final UUID wireUuid, final String wireName, final UUID verifiedJavaUuid) {
        Objects.requireNonNull(verifiedJavaUuid, "verifiedJavaUuid");
        return new ClientIdentity(Kind.VERIFIED_JAVA, wireUuid, wireName, verifiedJavaUuid, null, null, null);
    }

    /**
     * A Bedrock identity verified by the bridge; keeps the exact verification
     * snapshot. The XUID must be canonical (validated through ProfileKey, the
     * sole owner of key validation).
     */
    public static ClientIdentity verifiedBedrock(final UUID wireUuid, final String wireName,
                                                 final String xuid, final UUID providerEpoch,
                                                 final String bridgeSessionId) {
        final String canonicalXuid = ProfileKey.bedrockProfile(xuid).value();
        Objects.requireNonNull(providerEpoch, "providerEpoch");
        Objects.requireNonNull(bridgeSessionId, "bridgeSessionId");
        return new ClientIdentity(Kind.VERIFIED_BEDROCK, wireUuid, wireName, null, canonicalXuid, providerEpoch, bridgeSessionId);
    }

    public Kind kind() {
        return this.kind;
    }

    /** The original protocol identity of the connection; may be null before login. */
    public UUID wireUuid() {
        return this.wireUuid;
    }

    /** The original protocol name of the connection; may be null before login. */
    public String wireName() {
        return this.wireName;
    }

    /** The verified premium Java UUID; only set for VERIFIED_JAVA. */
    public UUID verifiedJavaUuid() {
        return this.verifiedJavaUuid;
    }

    /** The canonical verified XUID; only set for VERIFIED_BEDROCK. */
    public String xuid() {
        return this.xuid;
    }

    /** The providerEpoch of the verifying bridge registration; only set for VERIFIED_BEDROCK. */
    public UUID providerEpoch() {
        return this.providerEpoch;
    }

    /** The bridgeSessionId of the verified bridge session; only set for VERIFIED_BEDROCK. */
    public String bridgeSessionId() {
        return this.bridgeSessionId;
    }

    @Override
    public String toString() {
        //Deliberately excludes the bridgeSessionId: identity snapshots can end up in logs.
        return this.kind + "(wire=" + this.wireUuid + "/" + this.wireName + ")";
    }

}
