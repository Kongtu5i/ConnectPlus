package dev.connectplus.identity;

import dev.connectplus.accounts.CPAccount;
import dev.connectplus.session.PlayerSession;

import java.util.concurrent.CompletionStage;

/**
 * The binding/unbinding transaction service (plan §4.1/§4.2, task 5 = link).
 * Owns the one-to-one link transaction end to end: identity and generation
 * verification, the pre-check and serial re-check of the conflict, the claim
 * of the target profile's exclusive use, the PREPARED journal, the atomic
 * index commit (THE binding commit point), the old bedrock profile's discard
 * and the crash recovery.
 *
 * <p>Not responsible for UI or Microsoft HTTP: callers hand in an already
 * verified {@link CPAccount} (device-code flow) and the session to link.</p>
 */
public interface IdentityLinkService {

    /** The fixed outcome of a link attempt (§7). */
    enum Result {
        /** Full success: binding committed, old bedrock profile discarded, journal cleaned. */
        COMMITTED,
        /** The binding was committed but the old bedrock profile's deletion failed; recovery continues the cleanup. */
        COMMITTED_CLEANUP_PENDING,
        /**
         * The binding was committed but the blacklist inheritance (access lists, task 6)
         * could not be written: the accounts are registered as pending (the gate rejects
         * them while the blacklist is on and their protected operations pause) and the
         * startup recovery replays the recorded delta. Never a FAILED and never a full
         * success — AccountFlow shows the lists-temporarily-unavailable notice.
         */
        COMMITTED_ACCESS_PENDING,
        /** A one-to-one conflict: the player must unlink first (nothing was changed). */
        CONFLICT,
        /** The session/generation/identity was no longer current when the transaction ran. */
        STALE,
        /** The transaction aborted before the commit point; the old bedrock profile is preserved. */
        FAILED
    }

    /**
     * Links the verified Java account {@code verifiedAccount} to the bedrock
     * identity of {@code session} (§4.1). The §1.1 rules hold: the bedrock
     * profile A is DISCARDED (no bookmark merge, no archiving); a missing
     * target B profile is created as a default empty one; the new credentials
     * are persisted per B's saveLoginInfo setting; the wire identity of the
     * session never changes.
     *
     * <p>Blocking IO inside; the returned stage completes with one of the five
     * results. After a {@link Result#COMMITTED_CLEANUP_PENDING} the committed
     * binding stays and the XUID is blocked from new link/unlink operations
     * until the startup recovery completes the cleanup.</p>
     */
    CompletionStage<Result> link(PlayerSession session, CPAccount verifiedAccount);

    /**
     * §4.2 (task 7): unlinks the session's existing binding — only the CURRENT
     * trusted bedrock session running its linked Java profile may call this.
     * Serial flow: freeze → save B's accepted modifications → UNLINK journal →
     * atomic index removal (THE unlink commit point) → invalidate the old
     * lease/account callbacks → clear the in-memory B credentials → release the
     * session's B lease → create the blank A bedrock profile. The saved Java
     * login and B's bookmarks/settings survive (the existing logout() is never
     * called); A gets a blank profile and keeps its c2p connection.
     *
     * <p>A pre-commit failure keeps the association and B's profile untouched;
     * past the commit point the removal never rolls back — a failing blank
     * -profile creation leaves the restricted blank state and the startup
     * recovery completes it ({@link Result#COMMITTED_CLEANUP_PENDING}).</p>
     */
    CompletionStage<Result> unlink(PlayerSession session);

}
