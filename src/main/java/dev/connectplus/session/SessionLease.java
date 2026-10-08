package dev.connectplus.session;

import java.util.UUID;

/**
 * The right of one connection to use a protected profile, granted by
 * {@link SessionLeaseGranter#claim} before any profile read or write happens
 * (plan §7: claim 异步完成前调用方没有读写档案权限). The lease binds to the
 * real client's c2p lifecycle through its {@link #connectionId()}: switches,
 * lobby returns and p2s disconnects do not release it — only the c2p exit does
 * (§5, enforced by the coordinator arriving in task 4).
 *
 * <p>{@link #generation()} is the right-of-use generation: a newer grant for
 * the same connection supersedes older ones, and a release naming an older
 * generation must be a no-op.</p>
 */
public interface SessionLease {

    /** The connection id this lease belongs to (the c2p channel's id). */
    UUID connectionId();

    /** The right-of-use generation of this lease; grows with every re-grant. */
    long generation();

}
