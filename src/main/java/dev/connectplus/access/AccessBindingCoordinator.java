package dev.connectplus.access;

import dev.connectplus.identity.IdentityLinkStore;

import java.io.IOException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Coordinates the access lists with the binding lifecycle (design §4):
 *
 * <ul>
 *   <li>{@code change} — a console add/remove synchronizes the CURRENT bound
 *       counterpart in the SAME list commit; an unreadable link index reports
 *       instead of silently degrading to start-only sync.</li>
 *   <li>{@code commitLink} — the new-binding blacklist inheritance runs inside
 *       the shared commit critical section right after the binding index
 *       commit: the delta is written to the PREPARED journal before the index
 *       commit and applied after it; a failed application leaves the binding
 *       committed with the delta pending (journal ACCESS_PENDING + registered
 *       pending keys), never a full success and never a rollback.</li>
 *   <li>{@code recoverCommittedLink} — the startup recovery replays a
 *       committed delta idempotently (existing entries with the same primary
 *       key are never overwritten); the pending keys clear only after the
 *       replay AND the original cleanup succeeded.</li>
 * </ul>
 *
 * <p>The whitelist is never written by any inheritance. Unlink keeps every
 * list record (解绑保留记录). Network waits, device-code logins and session
 * leases never run inside the shared commit lock.</p>
 */
public final class AccessBindingCoordinator {

    /** The outcome of a binding commit: the lists are either done or pending. */
    public enum LinkCommitResult { COMPLETE, ACCESS_PENDING }

    private final AccessService accessService;
    private final AccessMutationCoordinator commitLock;
    private final Supplier<IdentityLinkStore> linkStore;
    private final AccessTargetResolver resolver; // nullable: names enriched from the lists only

    public AccessBindingCoordinator(final AccessService accessService, final AccessMutationCoordinator commitLock,
                                    final Supplier<IdentityLinkStore> linkStore,
                                    final AccessTargetResolver resolver) {
        this.accessService = Objects.requireNonNull(accessService, "accessService");
        this.commitLock = Objects.requireNonNull(commitLock, "commitLock");
        this.accessService.useCommitLock(this.commitLock);
        this.linkStore = Objects.requireNonNull(linkStore, "linkStore");
        this.resolver = resolver;
    }

    /** Runs an action inside the shared commit critical section (binding commits use this). */
    public <T> T withCommitLock(final Callable<T> action) throws Exception {
        return this.commitLock.withCommitLock(action);
    }

    // ---- console change ------------------------------------------------------

    /**
     * Console add/remove: resolves the CURRENT bound counterpart from the link
     * index and commits both entries in one list write. The returned stage
     * completes with the new state, or exceptionally when the list commit or
     * the link index failed (the caller reports; nothing is half-applied).
     */
    public CompletionStage<AccessPolicy.State> change(final AccessPolicy.Kind kind, final boolean add,
                                                      final AccessEntry target) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        try {
            return CompletableFuture.completedFuture(this.commitLock.withCommitLock(() ->
                    this.mutateBoth(kind, add, target)));
        } catch (final Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private AccessPolicy.State mutateBoth(final AccessPolicy.Kind kind, final boolean add,
                                          final AccessEntry target) throws Exception {
        // An unreadable link index throws here (CorruptIndexException): the caller
        // reports the failure instead of silently syncing only the start account.
        final IdentityLinkStore.Snapshot links = this.linkStore.get().snapshot();
        final List<AccessEntry> delta = new ArrayList<>();
        delta.add(target);
        final AccessEntry counterpart = this.counterpartEntry(links, target);
        if (counterpart != null) {
            delta.add(counterpart);
        }
        if (add) {
            return this.accessService.mutateNow(kind, delta, Set.of(), true);
        }
        final Set<AccessKey> removes = new LinkedHashSet<>();
        for (final AccessEntry entry : delta) {
            removes.add(entry.key());
        }
        return this.accessService.mutateNow(kind, List.of(), removes);
    }

    /** The current bound counterpart, or null when the start account has no committed link. */
    private AccessEntry counterpartEntry(final IdentityLinkStore.Snapshot links, final AccessEntry target) {
        if (target.clientType() == AccessKey.ClientType.JAVA) {
            // A java start is the value side of the index: find the xuid mapped to it.
            final String counterpartXuid = xuidFor(links, target.uuid());
            return counterpartXuid == null ? null
                    : this.entry(AccessKey.ClientType.BEDROCK, null, counterpartXuid);
        }
        if (target.xuid() == null) {
            return null;
        }
        final UUID counterpartJava = links.javaUuidFor(target.xuid());
        return counterpartJava == null ? null
                : this.entry(AccessKey.ClientType.JAVA, counterpartJava, null);
    }

    private static String xuidFor(final IdentityLinkStore.Snapshot links, final UUID javaUuid) {
        if (javaUuid == null) {
            return null;
        }
        return links.links().entrySet().stream()
                .filter(e -> javaUuid.equals(e.getValue()))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** Builds an entry for an identifier, keeping any known display name. */
    private AccessEntry entry(final AccessKey.ClientType type, final UUID javaUuid, final String xuid) {
        String name = null;
        if (this.resolver != null) {
            final String token = type == AccessKey.ClientType.JAVA
                    ? (javaUuid == null ? null : javaUuid.toString()) : xuid;
            if (token != null) {
                final List<AccessEntry> known = this.resolver.resolve(type, token);
                if (known.size() == 1 && known.get(0).displayName() != null) {
                    name = known.get(0).displayName();
                }
            }
        }
        return new AccessEntry(type, name, javaUuid, xuid);
    }

    // ---- link commit (new-binding inheritance) --------------------------------

    /**
     * The delta a NEW binding must inherit onto the counterpart: for every side
     * that is currently on the blacklist, the OTHER side's entry. The whitelist
     * is never inherited. Computed before the PREPARED journal write; the
     * caller must calculate and persist it inside the shared commit lock.
     */
    public List<AccessEntry> blacklistInheritanceDelta(final String xuid, final UUID javaUuid) throws IOException {
        final AccessPolicy.State state = this.accessService.state();
        final AccessListStore.Snapshot blacklist = state.blacklist();
        final List<AccessEntry> delta = new ArrayList<>();
        if (!blacklist.available()) {
            throw new IOException("blacklist data unavailable; repair and reload before linking accounts");
        }
        final boolean javaOnBlacklist = blacklist.entries().containsKey(AccessKey.javaUuid(javaUuid));
        final boolean bedrockOnBlacklist = blacklist.entries().containsKey(AccessKey.bedrockXuid(xuid));
        if (javaOnBlacklist) {
            delta.add(this.entry(AccessKey.ClientType.BEDROCK, null, xuid));
        }
        if (bedrockOnBlacklist) {
            delta.add(this.entry(AccessKey.ClientType.JAVA, javaUuid, null));
        }
        return delta;
    }

    /**
     * The binding commit critical section (called inside the shared commit
     * lock): persists the binding through {@code persistBinding} (THE binding
     * commit point) and then applies the journal's blacklist delta. A failed
     * delta application leaves the binding committed, writes the ACCESS_PENDING
     * phase and registers the affected keys as pending.
     */
    public LinkCommitResult commitLink(final dev.connectplus.identity.LinkTransactionJournal journal,
                                       final dev.connectplus.identity.LinkTransactionJournal.Entry prepared,
                                       final Callable<Void> persistBinding) throws Exception {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(persistBinding, "persistBinding");
        persistBinding.call(); // THE binding commit point; a throw here is the caller's abort path
        if (prepared.blacklistAdds().isEmpty()) {
            return LinkCommitResult.COMPLETE;
        }
        try {
            this.applyBlacklistDelta(prepared.blacklistAdds());
            return LinkCommitResult.COMPLETE;
        } catch (final IOException | RuntimeException deltaFailure) {
            // The binding stays committed; the delta is pending. The journal write
            // below may itself fail — the startup recovery re-derives the truth from
            // the index and replays the (already persisted) PREPARED delta idempotently.
            try {
                journal.write(dev.connectplus.identity.LinkTransactionJournal.Entry.withPhase(
                        prepared, dev.connectplus.identity.LinkTransactionJournal.Phase.ACCESS_PENDING));
            } catch (final IOException | RuntimeException journalFailure) {
                // ignored: the PREPARED entry on disk still carries the delta
            }
            this.registerPending(prepared.blacklistAdds());
            return LinkCommitResult.ACCESS_PENDING;
        }
    }

    /** Idempotently applies the delta: existing entries with the same key are never overwritten. */
    public void recoverCommittedLink(final dev.connectplus.identity.LinkTransactionJournal.Entry entry)
            throws IOException {
        Objects.requireNonNull(entry, "entry");
        if (entry.blacklistAdds().isEmpty()) {
            return;
        }
        try {
            this.commitLock.withCommitLock(() -> {
                // Startup starts with an empty pending set; restore it BEFORE replay.
                // Keep it even after replay until the caller completes profile cleanup.
                this.registerPending(entry.blacklistAdds());
                this.applyBlacklistDelta(entry.blacklistAdds());
                return null;
            });
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("the blacklist delta replay failed", e);
        }
    }

    /** Clears the pending keys of a fully recovered operation (replay + cleanup done). */
    public void pendingRecovered(final Set<AccessKey> keys) throws IOException {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        try {
            this.commitLock.withCommitLock(() -> {
                final Set<AccessKey> remaining = new LinkedHashSet<>(this.accessService.state().pendingInheritance());
                remaining.removeAll(keys);
                this.accessService.setPendingInheritanceNow(remaining);
                return null;
            });
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("clearing the pending inheritance keys failed", e);
        }
    }

    private void applyBlacklistDelta(final List<AccessEntry> delta) throws IOException {
        this.accessService.mutateNow(AccessPolicy.Kind.BLACKLIST, delta, Set.of());
    }

    private void registerPending(final List<AccessEntry> delta) {
        final Set<AccessKey> pending = new LinkedHashSet<>(this.accessService.state().pendingInheritance());
        for (final AccessEntry entry : delta) {
            pending.add(entry.key());
        }
        this.accessService.setPendingInheritanceNow(pending);
    }
}
