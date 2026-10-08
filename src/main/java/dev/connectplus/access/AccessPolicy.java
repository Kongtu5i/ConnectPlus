package dev.connectplus.access;

import java.util.Set;

/**
 * The pure access rule: from the two runtime switches, the list snapshots and
 * one subject it derives a decision. No IO, no clock, no authentication.
 * Skeleton — the evaluation order lands with the failing tests.
 */
public final class AccessPolicy {

    /** The two independent lists. */
    public enum Kind { WHITELIST, BLACKLIST }

    /** Why a subject was allowed or rejected. */
    public enum Reason { ALLOWED, BLACKLISTED, NOT_WHITELISTED, IDENTIFIER_UNAVAILABLE, LIST_UNAVAILABLE }

    /** One evaluation result; only ALLOWED lets the connection proceed. */
    public record Decision(Reason reason) {
        public boolean allowed() {
            return this.reason == Reason.ALLOWED;
        }
    }

    /**
     * One immutable published rule state: the runtime switches, both list
     * snapshots, the primary keys with an unfinished blacklist inheritance and
     * a process-local revision.
     */
    public record State(boolean whitelistEnabled, boolean blacklistEnabled,
                        AccessListStore.Snapshot whitelist, AccessListStore.Snapshot blacklist,
                        Set<AccessKey> pendingInheritance, long revision) {
        public State {
            pendingInheritance = Set.copyOf(pendingInheritance);
        }
    }

    /**
     * Evaluates the subject against the state; a pure function. Order:
     * an enabled list without valid data rejects first (LIST_UNAVAILABLE before
     * any identifier reason), then a known bedrock without an XUID follows only
     * the whitelist switch (no UUID fallback), then an unfinished blacklist
     * inheritance for this key rejects while the blacklist is enabled, then a
     * blacklist hit wins over the whitelist membership check.
     */
    public static Decision evaluate(final State state, final AccessSubject subject) {
        if ((state.whitelistEnabled() && !state.whitelist().available())
                || (state.blacklistEnabled() && !state.blacklist().available())) {
            return new Decision(Reason.LIST_UNAVAILABLE);
        }
        final AccessKey key = subject.key();
        if (subject.clientType() == null) {
            return new Decision(state.whitelistEnabled() || state.blacklistEnabled()
                    ? Reason.IDENTIFIER_UNAVAILABLE : Reason.ALLOWED);
        }
        if (key == null) {
            // Known bedrock, no XUID: never fall back to the wire/protocol UUID.
            return new Decision(state.whitelistEnabled() ? Reason.IDENTIFIER_UNAVAILABLE : Reason.ALLOWED);
        }
        if (state.blacklistEnabled() && state.pendingInheritance().contains(key)) {
            return new Decision(Reason.LIST_UNAVAILABLE);
        }
        if (state.blacklistEnabled() && state.blacklist().entries().containsKey(key)) {
            return new Decision(Reason.BLACKLISTED);
        }
        if (state.whitelistEnabled() && !state.whitelist().entries().containsKey(key)) {
            return new Decision(Reason.NOT_WHITELISTED);
        }
        return new Decision(Reason.ALLOWED);
    }
}
