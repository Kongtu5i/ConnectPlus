package dev.connectplus.compat;

import com.mojang.authlib.GameProfile;
import dev.connectplus.bridge.BedrockBridgeEndpoint;
import dev.connectplus.identity.ClientIdentity;
import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.viaproxy.plugins.events.Client2ProxyChannelInitializeEvent;
import net.raphimc.viaproxy.plugins.events.ClientLoggedInEvent;
import net.raphimc.viaproxy.plugins.events.types.ITyped;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.net.SocketAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Produces the {@link ClientIdentity} bound to each c2p channel.
 *
 * <p>PRE phase ({@link Client2ProxyChannelInitializeEvent}): captures the raw socket
 * addresses (before Geyser-ViaProxy rewrites them, bridge spec §2.1) and hands out
 * the per-connection id.</p>
 *
 * <p>Java auth completion: ViaProxy 3.4.13 fires {@link ClientLoggedInEvent} in BOTH
 * authentication branches — the offline/skipped branch (global online-mode off, or a
 * cancelled {@code ShouldVerifyOnlineModeEvent}) fires it with the client-supplied
 * profile, while the premium branch fires it with the encryption handler
 * ({@code MCPipeline.ENCRYPTION_ATTRIBUTE_KEY}) installed on the c2p. The handler is
 * installed BEFORE the Mojang session check, and the premium branch fires the event
 * on EVERY sub-path (LoginPacketHandler bytecode): after a successful
 * {@code hasJoinedServer} with the server-verified profile, but also after
 * {@code hasJoinedServer} returned null ("Invalid session" → kickClient) and after a
 * session-check Throwable ("Failed to authenticate" → kickClient) — in those failure
 * cases the profile is still the client-supplied one and the connection is being
 * kicked. The marker for the really-verified sub-path is therefore the encryption
 * non-null cipher AND the c2p still alive at event time: PacketCryptor creates the
 * attribute even on unencrypted connections; its existence is not proof.
 * kickClient's disconnect
 * write + CLOSE is queued on the event loop ahead of the scheduled event, so a
 * connection the session check just rejected is dead (or dying with its close
 * already initiated) before the event fires, while a genuinely verified connection
 * is alive. The global online-mode flag alone never verifies anything (plan
 * §"Java 认证完成接入"). Verified connections become VERIFIED_JAVA with the game
 * profile the session server returned; everything else starts UNVERIFIED.</p>
 *
 * <p>Bedrock: an unverified connection is offered to the registered bridge endpoint
 * (RESOLVE with the raw connection data). A VERIFIED answer for exactly this
 * connection upgrades the identity in place to VERIFIED_BEDROCK; NO_MATCH, a
 * rejection, a timeout or a late answer all leave it UNVERIFIED, and a negative
 * result never grants anything.</p>
 */
public final class ClientIdentityCapture {

    /** Supplies the bridge endpoint; may supply null when the bridge is not wired up. */
    private final Supplier<BedrockBridgeEndpoint> bridgeEndpoint;

    public ClientIdentityCapture(final Supplier<BedrockBridgeEndpoint> bridgeEndpoint) {
        this.bridgeEndpoint = Objects.requireNonNull(bridgeEndpoint, "bridgeEndpoint");
    }

    @EventHandler
    public void onClient2ProxyChannelInitialize(final Client2ProxyChannelInitializeEvent event) {
        if (!ITyped.Type.PRE.equals(event.getType())) {
            return;
        }
        this.captureRawConnection(event.getChannel());
    }

    /** Records the pre-rewrite connection data of a freshly accepted c2p channel. */
    void captureRawConnection(final Channel channel) {
        channel.attr(CPAttributeKeys.CONNECTION_ID).setIfAbsent(UUID.randomUUID());
        channel.attr(CPAttributeKeys.RAW_LOCAL_ADDRESS).set(channel.localAddress());
        channel.attr(CPAttributeKeys.RAW_REMOTE_ADDRESS).set(channel.remoteAddress());
    }

    @EventHandler
    public void onClientLoggedIn(final ClientLoggedInEvent event) {
        this.onJavaAuthBranchResolved(event.getProxyConnection());
    }

    /**
     * The explicit Java-auth-completion point: establishes the connection identity
     * once, from the branch ViaProxy actually took. Repeated events (e.g. a backend
     * rewriting the game profile) never replace the original owner.
     */
    void onJavaAuthBranchResolved(final ProxyConnection connection) {
        final Channel c2p = connection.getC2P();
        if (c2p == null) {
            return;
        }
        final GameProfile profile = connection.getGameProfile();
        if (profile == null || profile.getId() == null || profile.getName() == null) {
            return;
        }
        if (c2p.attr(CPAttributeKeys.CLIENT_IDENTITY).get() != null) return;
        final CompletableFuture<ClientIdentity> resolution = new CompletableFuture<>();
        // Publish the notification first: a lobby that sees UNVERIFIED must also
        // be able to subscribe, including when the bridge answers immediately.
        if (c2p.attr(CPAttributeKeys.CLIENT_IDENTITY_RESOLUTION).setIfAbsent(resolution) != null) return;
        final ClientIdentity identity = this.isJavaAuthCompleted(c2p)
                ? ClientIdentity.verifiedJava(profile.getId(), profile.getName(), profile.getId())
                : ClientIdentity.unverified(profile.getId(), profile.getName());
        if (c2p.attr(CPAttributeKeys.CLIENT_IDENTITY).setIfAbsent(identity) != null) {
            resolution.complete(c2p.attr(CPAttributeKeys.CLIENT_IDENTITY).get());
            return; // The identity of this connection is already established.
        }
        if (identity.kind() == ClientIdentity.Kind.UNVERIFIED) {
            c2p.closeFuture().addListener(future -> resolution.complete(identity));
            this.dispatchBedrockResolve(c2p, identity, resolution);
        } else {
            resolution.complete(identity);
        }
    }

    /**
     * Whether THIS connection really completed premium verification: ViaProxy 3.4.13
     * installs the netty encryption handler on the c2p channel in the premium branch
     * — but BEFORE the Mojang session check, and the branch then fires
     * ClientLoggedInEvent on every sub-path, including the kick paths
     * ("Invalid session", "Failed to authenticate") where the profile is still the
     * client-supplied one. On those paths kickClient's disconnect write + CLOSE is
     * queued on the event loop ahead of the scheduled event, so a rejected
     * connection is no longer active when the event fires. The classification
     * therefore requires a non-null cipher AND a still-alive channel; a cancelled
     * verification or a global online-mode flag never installs a cipher. Merely
     * allocating the attribute, as PacketCryptor does for every client, is not proof.
     */
    private boolean isJavaAuthCompleted(final Channel c2p) {
        // PacketCryptor allocates this attribute for every connection, including
        // offline and Geyser connections. Only an actual cipher marks the premium branch.
        return c2p.isActive() && c2p.attr(MCPipeline.ENCRYPTION_ATTRIBUTE_KEY).get() != null;
    }

    /**
     * Asks the bridge whether this exact connection is one of its verified Bedrock
     * sessions. Fail-closed: without the raw connection data captured in the PRE
     * phase the protocol request cannot be built, so nothing is sent and the
     * connection stays UNVERIFIED.
     */
    private void dispatchBedrockResolve(final Channel c2p, final ClientIdentity placeholder,
                                       final CompletableFuture<ClientIdentity> resolution) {
        if (!c2p.isActive()) {
            // A dying connection (e.g. kicked by a failed session check) must not ask
            // the bridge for an identity.
            resolution.complete(placeholder);
            return;
        }
        final BedrockBridgeEndpoint endpoint = this.bridgeEndpoint.get();
        if (endpoint == null) {
            resolution.complete(placeholder);
            return;
        }
        final UUID connectionId = c2p.attr(CPAttributeKeys.CONNECTION_ID).get();
        final SocketAddress rawLocal = c2p.attr(CPAttributeKeys.RAW_LOCAL_ADDRESS).get();
        final SocketAddress rawRemote = c2p.attr(CPAttributeKeys.RAW_REMOTE_ADDRESS).get();
        if (connectionId == null || rawLocal == null || rawRemote == null) {
            resolution.complete(placeholder);
            return;
        }
        endpoint.resolve(c2p, rawLocal, rawRemote, connectionId, placeholder.wireUuid(), placeholder.wireName())
                .whenComplete((result, error) -> {
                    if (error != null || result == null
                            || result.status() != BedrockBridgeEndpoint.ResolveStatus.VERIFIED
                            || result.identity() == null) {
                        // A REJECTED answer whose reason implies the bridge DID match a
                        // bedrock session on this raw address pair (untracked, untrusted
                        // or invalid identity) fixes the platform as BEDROCK for the
                        // access lists — without granting any identity or XUID.
                        if (result != null && result.status() == BedrockBridgeEndpoint.ResolveStatus.REJECTED
                                && result.reasonCode() != null
                                && BedrockBridgeEndpoint.SESSION_MATCHED_REJECT_REASONS.contains(result.reasonCode())) {
                            c2p.attr(CPAttributeKeys.PLATFORM_CLASSIFICATION)
                                    .setIfAbsent(ClientPlatformInspector.Classification.BEDROCK);
                        }
                        resolution.complete(placeholder);
                        return; // NO_MATCH/REJECTED/UNAVAILABLE/TIMEOUT: nothing is granted.
                    }
                    final ClientIdentity verified;
                    try {
                        verified = ClientIdentity.verifiedBedrock(placeholder.wireUuid(), placeholder.wireName(),
                                result.identity().xuid(), result.providerEpoch(),
                                result.identity().bridgeSessionId());
                    } catch (final IllegalArgumentException e) {
                        // Non-canonical XUID double-check (ProfileKey is the sole validator).
                        resolution.complete(placeholder);
                        return;
                    }
                    c2p.eventLoop().execute(() -> {
                        // Closing the client, timing out or stopping/replacing the
                        // provider invalidates the reply before any identity is installed.
                        if (resolution.isDone() || !endpoint.isCurrentIdentity(verified, c2p)) {
                            resolution.complete(placeholder);
                            return;
                        }
                        final var attribute = c2p.attr(CPAttributeKeys.CLIENT_IDENTITY);
                        c2p.attr(CPAttributeKeys.BEDROCK_BRIDGE_ENDPOINT).set(endpoint);
                        if (attribute.compareAndSet(placeholder, verified)) {
                            resolution.complete(verified);
                        } else {
                            resolution.complete(placeholder);
                        }
                    });
                });
    }
}
