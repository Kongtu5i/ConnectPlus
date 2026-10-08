package dev.connectplus.access;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {

    private static final UUID JAVA_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String XUID = "2530000000000001";
    private static final AccessKey JAVA_KEY = AccessKey.javaUuid(JAVA_UUID);
    private static final AccessKey BEDROCK_KEY = AccessKey.bedrockXuid(XUID);

    // ---- helpers -----------------------------------------------------------

    private static AccessListStore.Snapshot snap(final boolean available, final AccessKey... keys) {
        final java.util.Map<AccessKey, AccessEntry> entries = new java.util.LinkedHashMap<>();
        for (final AccessKey key : keys) {
            entries.put(key, key.type() == AccessKey.ClientType.JAVA
                    ? new AccessEntry(AccessKey.ClientType.JAVA, "N", javaUuidOf(key.id()), null)
                    : new AccessEntry(AccessKey.ClientType.BEDROCK, "N", null, key.id()));
        }
        return new AccessListStore.Snapshot(7, available, entries);
    }

    private static UUID javaUuidOf(final String id) {
        return UUID.fromString(id);
    }

    private static AccessPolicy.State state(final boolean whiteOn, final boolean blackOn,
                                            final AccessListStore.Snapshot whitelist,
                                            final AccessListStore.Snapshot blacklist,
                                            final Set<AccessKey> pending) {
        return new AccessPolicy.State(whiteOn, blackOn, whitelist, blacklist, pending, 1);
    }

    /** Empty-list evaluation with both switches; parameter order: blackOn, whiteOn, subject. */
    private static AccessPolicy.Decision evaluateEmptyLists(final boolean blackOn, final boolean whiteOn,
                                                            final AccessSubject subject) {
        return AccessPolicy.evaluate(state(whiteOn, blackOn,
                snap(true), snap(true), Set.of()), subject);
    }

    private static AccessSubject javaSubject(final UUID uuid, final String name) {
        return new AccessSubject(AccessKey.ClientType.JAVA, name, uuid, null);
    }

    private static AccessSubject bedrockSubject(final String name, final UUID wireUuid, final String xuid) {
        return new AccessSubject(AccessKey.ClientType.BEDROCK, name, wireUuid, xuid);
    }

    // ---- the design's four-switch table for a known identifier -------------

    @Test
    void knownJavaSubjectFollowsTheSwitchTable() {
        final AccessSubject onBoth = javaSubject(JAVA_UUID, "A");
        final AccessListStore.Snapshot whiteWithA = snap(true, JAVA_KEY);
        final AccessListStore.Snapshot blackWithA = snap(true, JAVA_KEY);
        final AccessListStore.Snapshot empty = snap(true);

        // black off, white off: lists do not restrict.
        assertTrue(AccessPolicy.evaluate(state(false, false, empty, empty, Set.of()), onBoth).allowed());
        // black on, white off: allowed when not blacklisted.
        assertTrue(AccessPolicy.evaluate(state(false, true, empty, empty, Set.of()), onBoth).allowed());
        // black on, white off: blacklisted.
        assertEquals(AccessPolicy.Reason.BLACKLISTED,
                AccessPolicy.evaluate(state(false, true, empty, blackWithA, Set.of()), onBoth).reason());
        // black off, white on: allowed when whitelisted.
        assertTrue(AccessPolicy.evaluate(state(true, false, whiteWithA, empty, Set.of()), onBoth).allowed());
        // black off, white on: not whitelisted.
        assertEquals(AccessPolicy.Reason.NOT_WHITELISTED,
                AccessPolicy.evaluate(state(true, false, empty, empty, Set.of()), onBoth).reason());
        // both on, only whitelisted: allowed.
        assertTrue(AccessPolicy.evaluate(state(true, true, whiteWithA, empty, Set.of()), onBoth).allowed());
        // both on, only blacklisted: blacklist wins over not-whitelisted.
        assertEquals(AccessPolicy.Reason.BLACKLISTED,
                AccessPolicy.evaluate(state(true, true, empty, blackWithA, Set.of()), onBoth).reason());
        // both on, on both lists: blacklist priority.
        assertEquals(AccessPolicy.Reason.BLACKLISTED,
                AccessPolicy.evaluate(state(true, true, whiteWithA, blackWithA, Set.of()), onBoth).reason());
    }

    @Test
    void disabledListsDoNotParticipateEvenWithUnavailableData() {
        final AccessSubject subject = javaSubject(JAVA_UUID, "A");
        // The disabled whitelist has no valid data — it still must not reject.
        assertTrue(AccessPolicy.evaluate(state(false, false,
                snap(false), snap(true), Set.of()), subject).allowed());
        // Disabled blacklist with the subject on it stays out of the decision.
        assertTrue(AccessPolicy.evaluate(state(false, false,
                snap(true), snap(true, JAVA_KEY), Set.of()), subject).allowed());
    }

    @Test
    void enabledUnavailableListRejectsBeforeIdentifierException() {
        // The enabled whitelist has no valid data: LIST_UNAVAILABLE wins over the
        // missing-XUID reason, never a silent "not on the whitelist".
        assertEquals(AccessPolicy.Reason.LIST_UNAVAILABLE,
                AccessPolicy.evaluate(state(true, false, snap(false), snap(true), Set.of()),
                        bedrockSubject("B", JAVA_UUID, null)).reason());
        assertEquals(AccessPolicy.Reason.LIST_UNAVAILABLE,
                AccessPolicy.evaluate(state(false, true, snap(true), snap(false), Set.of()),
                        javaSubject(JAVA_UUID, "A")).reason());
    }

    // ---- the design's missing-XUID table ------------------------------------

    @Test
    void evaluateEmptyListsForBedrockWithoutXuid() {
        final AccessSubject subject = bedrockSubject("B", JAVA_UUID, null);
        assertTrue(evaluateEmptyLists(true, false, subject).allowed());
        assertEquals(AccessPolicy.Reason.IDENTIFIER_UNAVAILABLE,
                evaluateEmptyLists(false, true, subject).reason());
        assertEquals(AccessPolicy.Reason.IDENTIFIER_UNAVAILABLE,
                evaluateEmptyLists(true, true, subject).reason());
    }

    @Test
    void missingXuidIsRejectedEvenWhenProtocolUuidIsWhitelisted() {
        final AccessSubject subject = bedrockSubject("B", JAVA_UUID, null);
        final AccessListStore.Snapshot whiteWithWireUuid = snap(true, JAVA_KEY);
        assertEquals(AccessPolicy.Reason.IDENTIFIER_UNAVAILABLE,
                AccessPolicy.evaluate(state(true, false, whiteWithWireUuid, snap(true), Set.of()),
                        subject).reason());
    }

    @Test
    void knownBedrockMatchesOnlyByXuid() {
        final AccessSubject subject = bedrockSubject("B", JAVA_UUID, XUID);
        // The wire UUID is not the matching identifier for bedrock.
        assertEquals(AccessPolicy.Reason.NOT_WHITELISTED,
                AccessPolicy.evaluate(state(true, false, snap(true, JAVA_KEY), snap(true), Set.of()),
                        subject).reason());
        assertTrue(AccessPolicy.evaluate(state(true, false, snap(true, BEDROCK_KEY), snap(true), Set.of()),
                subject).allowed());
    }

    // ---- pending blacklist inheritance --------------------------------------

    @Test
    void pendingBlacklistInheritanceRejectsWhileBlacklistIsEnabled() {
        final AccessSubject subject = javaSubject(JAVA_UUID, "A");
        assertEquals(AccessPolicy.Reason.LIST_UNAVAILABLE,
                AccessPolicy.evaluate(state(false, true, snap(true), snap(true), Set.of(JAVA_KEY)),
                        subject).reason());
        // With the blacklist off the pending state is irrelevant.
        assertTrue(AccessPolicy.evaluate(state(false, false, snap(true), snap(true), Set.of(JAVA_KEY)),
                subject).allowed());
        // A pending key that is not this subject's key does not reject.
        assertTrue(AccessPolicy.evaluate(state(false, true, snap(true), snap(true), Set.of(BEDROCK_KEY)),
                subject).allowed());
    }

    @Test
    void decisionAllowedFlagMatchesTheReason() {
        assertTrue(new AccessPolicy.Decision(AccessPolicy.Reason.ALLOWED).allowed());
        for (final AccessPolicy.Reason reason : AccessPolicy.Reason.values()) {
            if (reason != AccessPolicy.Reason.ALLOWED) {
                assertFalse(new AccessPolicy.Decision(reason).allowed());
            }
        }
    }
}
