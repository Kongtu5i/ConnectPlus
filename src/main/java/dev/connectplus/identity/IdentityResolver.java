package dev.connectplus.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Resolves a verified {@link ClientIdentity} to the {@link ProfileKey} of the
 * profile it may use, given the current link snapshot: a verified Java identity
 * always owns its own JAVA profile; a verified Bedrock identity uses the linked
 * Java profile when one exists and its own BEDROCK profile otherwise.
 *
 * <p>UNVERIFIED identities are rejected: they never produce a protected
 * ProfileKey.</p>
 */
public final class IdentityResolver {

    /**
     * Resolves the profile key of {@code identity} under the committed link
     * snapshot {@code links}.
     *
     * @throws IllegalArgumentException when the identity is UNVERIFIED
     */
    public ProfileKey resolve(final ClientIdentity identity, final IdentityLinkStore.Snapshot links) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(links, "links");
        return switch (identity.kind()) {
            case VERIFIED_JAVA -> ProfileKey.javaProfile(identity.verifiedJavaUuid());
            case VERIFIED_BEDROCK -> {
                final UUID linked = links.javaUuidFor(identity.xuid());
                yield linked != null ? ProfileKey.javaProfile(linked) : ProfileKey.bedrockProfile(identity.xuid());
            }
            case UNVERIFIED -> throw new IllegalArgumentException(
                    "UNVERIFIED identities must not resolve to a protected profile key");
        };
    }

}
