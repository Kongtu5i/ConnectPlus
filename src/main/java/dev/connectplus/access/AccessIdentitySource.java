package dev.connectplus.access;

import com.mojang.authlib.GameProfile;
import dev.connectplus.compat.ClientPlatformInspector;
import dev.connectplus.identity.ClientIdentity;
import io.netty.channel.Channel;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Builds the {@link AccessSubject} of one connection from the entry identity:
 * the raw identity captured on the c2p channel (never the linked-profile owner
 * and never an upgraded authentication level) plus the independent platform
 * classification. Bedrock resolution rides the existing capture pipeline and
 * its {@code BedrockBridgeEndpoint.DEFAULT_RESOLVE_TIMEOUT} (3 seconds) budget;
 * a confirmed bedrock platform without a usable XUID stays BEDROCK with no
 * primary key. An unknown platform has no matching key. Offline Java can use
 * the wire UUID only when public host metadata proves Geyser is absent.
 */
public final class AccessIdentitySource {

    private final ClientPlatformInspector inspector;

    public AccessIdentitySource(final ClientPlatformInspector inspector) {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
    }

    /** Resolves the access subject of this connection; completes within the bridge budget. */
    public CompletionStage<AccessSubject> resolve(final ProxyConnection connection) {
        Objects.requireNonNull(connection, "connection");
        final Channel c2p = connection.getC2P();
        final CompletableFuture<AccessSubject> result = new CompletableFuture<>();

        final ClientIdentity identity = c2p == null ? null : c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).get();
        if (identity != null && identity.kind() == ClientIdentity.Kind.VERIFIED_JAVA) {
            result.complete(this.subjectOf(identity, c2p, connection));
            return result;
        }
        final CompletableFuture<ClientIdentity> resolution = c2p == null ? null
                : c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY_RESOLUTION).get();
        if (resolution == null) {
            // No capture pipeline (e.g. an unusual host): the wire profile is the
            // subject, classified as far as the current signals allow.
            result.complete(this.subjectOf(identity, c2p, connection));
            return result;
        }
        resolution.whenComplete((resolved, error) ->
                result.complete(this.subjectOf(resolved, c2p, connection)));
        return result;
    }

    private AccessSubject subjectOf(final ClientIdentity identity, final Channel c2p, final ProxyConnection connection) {
        if (identity != null && identity.kind() == ClientIdentity.Kind.VERIFIED_JAVA) {
            return new AccessSubject(AccessKey.ClientType.JAVA, identity.wireName(),
                    identity.verifiedJavaUuid(), null);
        }
        if (identity != null && identity.kind() == ClientIdentity.Kind.VERIFIED_BEDROCK) {
            return new AccessSubject(AccessKey.ClientType.BEDROCK, identity.wireName(),
                    identity.wireUuid(), identity.xuid());
        }
        // UNVERIFIED or no identity: the platform signal decides the class.
        final ClientPlatformInspector.Classification platform =
                this.inspector.inspect(c2p != null ? c2p : connection.getC2P());
        if (platform == ClientPlatformInspector.Classification.BEDROCK) {
            return new AccessSubject(AccessKey.ClientType.BEDROCK, this.wireName(identity, connection),
                    this.wireUuid(identity, connection), null);
        }
        return new AccessSubject(platform == ClientPlatformInspector.Classification.JAVA
                ? AccessKey.ClientType.JAVA : null, this.wireName(identity, connection),
                this.wireUuid(identity, connection), null);
    }

    private UUID wireUuid(final ClientIdentity identity, final ProxyConnection connection) {
        if (identity != null && identity.wireUuid() != null) {
            return identity.wireUuid();
        }
        final GameProfile profile = connection.getGameProfile();
        return profile != null ? profile.getId() : null;
    }

    private String wireName(final ClientIdentity identity, final ProxyConnection connection) {
        if (identity != null && identity.wireName() != null) {
            return identity.wireName();
        }
        final GameProfile profile = connection.getGameProfile();
        return profile != null ? profile.getName() : null;
    }
}
