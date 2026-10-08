package dev.connectplus.session;

import dev.connectplus.identity.ProfileKey;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * The §7 session-exclusivity API (plan §5/§7): the right to use a protected
 * profile is granted by {@link #claim} before any profile read or write
 * happens, stays verifiable through {@link #isCurrent} and is handed back
 * through {@link #release} when the real client's c2p connection ends. The
 * resource set of a claim covers the profile AND the actually-used Java
 * accounts, so a credential installed under a different profile still occupies
 * its single account (plan §5).
 *
 * <p>The production implementation is {@link AccountSessionCoordinator} (task
 * 4): serial per-resource arbitration, displacement (顶号) of the current
 * holder, and the bridge duplicate-candidate handshake. The task 3 lease stub
 * was retired — no stub path remains in production wiring; tests substitute
 * their own implementations of this interface where the coordinator's
 * arbitration is not under test.</p>
 */
public interface SessionLeaseGranter {

    /**
     * Claims the right for {@code session} to use the profile {@code profile}
     * plus the Java accounts in {@code accountUuids}. The returned stage
     * completes with the lease once the right is granted; until then the caller
     * has no profile read or write permission. When another connection holds
     * any of the resources, the coordinator displaces it (freeze → save →
     * invalidate → notify → close → grant, plan §5) before completing; a save
     * failure denies the new connection with an exceptional completion.
     */
    CompletionStage<SessionLease> claim(PlayerSession session, ProfileKey profile, Set<UUID> accountUuids);

    /**
     * Whether {@code lease} is still the current right of use for all of its
     * resources. Write paths, refreshes, switches and async callbacks check
     * this before touching profile state; once it answers false the lease's
     * holder may no longer write.
     */
    boolean isCurrent(SessionLease lease);

    /**
     * Hands the lease back. Only a still-current lease frees its resources; a
     * stale release (displaced, superseded or already released) is a no-op.
     * The stage completes when the release has been processed in the
     * coordinator's serial domain.
     */
    CompletionStage<Void> release(SessionLease lease);

}
