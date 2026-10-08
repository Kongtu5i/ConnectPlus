package dev.connectplus.identity;

import dev.connectplus.accounts.CPAccount;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.compat.AccountLoginPolicy;
import dev.connectplus.session.AccountSessionCoordinator;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The §4.1 first-link transaction (task 5). The binding commit point is the
 * atomic commit of the new identity-links.json revision (step 4); everything
 * before it is abortable with A's profile fully preserved, everything after it
 * never rolls back — a failed deletion of A's old bedrock file becomes a
 * {@link Result#COMMITTED_CLEANUP_PENDING} that the startup recovery continues
 * (Review Focus R2: never fall back to A's old file once committed).
 *
 * <p><b>Serialization story.</b> All blocking IO of the transaction runs on
 * the caller-provided executor — the lobby's shared storage executor in
 * production, a direct executor in tests. The transaction itself is serialized
 * through the coordinator's claim (step 2): the exclusive use of B's profile
 * is held through the coordinator's serial domain, and every phase
 * re-checks the session's lease currency before its writes, so two racing
 * links (or a link racing a join) cannot interleave their persistence steps
 * for the same resources. The index's own read-check-commit is additionally
 * serialized inside {@link IdentityLinkStore}.</p>
 *
 * <p><b>§1.1 outcome rules.</b> B's bookmarks survive, A's are discarded (no
 * merge, no archive); a missing B profile is created empty; the new
 * credentials are persisted per B's saveLoginInfo; authorization cancellation
 * or any pre-commit failure keeps A's original profile; the session's wire
 * identity is never replaced.</p>
 */
public final class DefaultIdentityLinkService implements IdentityLinkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultIdentityLinkService.class);

    private final PlayerStore playerStore;
    private final IdentityLinkStore identityLinkStore;
    private final AccountSessionCoordinator coordinator;
    private final TokenStore tokenStore;
    private final Executor executor;
    /** The journal factory; overridable by tests to inject journal IO failures. */
    private final Supplier<LinkTransactionJournal> journalFactory;

    public DefaultIdentityLinkService(final PlayerStore playerStore,
                                      final IdentityLinkStore identityLinkStore,
                                      final AccountSessionCoordinator coordinator,
                                      final TokenStore tokenStore,
                                      final Executor executor) {
        this(playerStore, identityLinkStore, coordinator, tokenStore, executor,
                () -> new LinkTransactionJournal(playerStore.playersDir()));
    }

    /** Test seam: an explicit journal factory (production always builds its own). */
    public DefaultIdentityLinkService(final PlayerStore playerStore,
                               final IdentityLinkStore identityLinkStore,
                               final AccountSessionCoordinator coordinator,
                               final TokenStore tokenStore,
                               final Executor executor,
                               final Supplier<LinkTransactionJournal> journalFactory) {
        this.playerStore = Objects.requireNonNull(playerStore, "playerStore");
        this.identityLinkStore = Objects.requireNonNull(identityLinkStore, "identityLinkStore");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.journalFactory = Objects.requireNonNull(journalFactory, "journalFactory");
    }

    /**
     * The access-list binding coordinator (task 6); nullable — without it the
     * link transaction keeps its pre-access behaviour (no blacklist
     * inheritance, no shared commit lock). Production wires it in CoreMain
     * before the lobby starts.
     */
    public void setAccessBindingCoordinator(@Nullable final dev.connectplus.access.AccessBindingCoordinator coordinator) {
        this.accessBindingCoordinator = coordinator;
    }

    private @Nullable dev.connectplus.access.AccessBindingCoordinator accessBindingCoordinator;

    @Override
    public CompletionStage<Result> link(final PlayerSession session, @Nullable final CPAccount verifiedAccount) {
        final CompletableFuture<Result> result = new CompletableFuture<>();
        this.executor.execute(() -> {
            try {
                result.complete(this.linkBlocking(session, verifiedAccount));
            } catch (final Exception e) {
                LOGGER.warn("The link transaction of session {} failed", session.connectionId, e);
                result.complete(Result.FAILED);
            }
        });
        return result;
    }

    /**
     * The whole §4.1 state machine, blocking. Called on this service's executor
     * (blocking IO is legal there). Every step documents its phase.
     */
    private Result linkBlocking(final PlayerSession session, @Nullable final CPAccount verifiedAccount) throws IOException {
        // ---- Step 1: verify identity/auth result/session generation ----------
        //No verified account = the authorization was cancelled or failed: nothing
        //may change (§1.1 授权取消、失败或绑定尚未提交时保留 A 原档案).
        if (verifiedAccount == null || verifiedAccount.uuid() == null) {
            return Result.FAILED;
        }
        final ClientIdentity identity = identityOf(session);
        if (identity == null || identity.kind() != ClientIdentity.Kind.VERIFIED_BEDROCK) {
            //Linking is a bedrock-only flow; a Java identity owns its profile already.
            return identity == null ? Result.STALE : Result.FAILED;
        }
        if (!AccountLoginPolicy.isAllowedForSession(session)) return Result.STALE;
        if (session.lease == null || !this.coordinator.isCurrent(session.lease)
                || session.profileKey == null || session.playerData == null) {
            //Displaced, released, or never loaded: the session is no longer current.
            return Result.STALE;
        }
        final String xuid = identity.xuid();
        final ProfileKey ownBedrockKey = ProfileKey.bedrockProfile(xuid);
        if (!ownBedrockKey.equals(session.profileKey)) {
            //A linked bedrock session uses its Java profile; linking again needs an
            //unlink first — and would be rejected by the one-to-one pre-check anyway.
            return Result.CONFLICT;
        }
        //§4.1: 恢复完成前禁止该 XUID 执行新绑定/解绑 — a runtime-failed recovery
        //(or any journal operation still pending for this XUID) blocks new link
        //attempts until the next startup recovery resolves it.
        if (!linkAllowedNow(this.identityLinkStore, this.journalFactory.get(), xuid)) {
            LOGGER.warn("The link attempt of XUID {} is blocked: an earlier transaction of this identity"
                    + " is still awaiting recovery", xuid);
            return Result.FAILED;
        }
        final UUID javaUuid = verifiedAccount.uuid(); //CPAccount.uuid() only — never email/name/entry Geyser UUID
        final IdentityLinkStore.Snapshot snapshot = this.identityLinkStore.snapshot();
        //One-to-one pre-check (§4.1 step 1), re-checked under the index's own
        //commit serialization below.
        if (snapshot.links().containsKey(xuid) || snapshot.links().containsValue(javaUuid)) {
            return Result.CONFLICT;
        }
        final long expectedRevision = snapshot.revision();
        final long targetRevision = expectedRevision + 1;

        // ---- Step 2: freeze A's changes; acquire B's exclusive use -----------
        //The claim on the SAME session supersedes its old bedrock-profile lease
        //(the coordinator's re-claim rule): from that moment A's lease is no
        //longer current, so every screen/async write path of A is refused — the
        //freeze of §4.1 step 2. The claim displaces a concurrently online B
        //(§1.1 item 1). The pre-claim staleness was verified in step 1; if the
        //session was displaced between step 1 and here, the claim completes but
        //the displaced flag / released c2p catches it below.
        final ProfileKey targetKey = ProfileKey.javaProfile(javaUuid);
        final SessionLease targetLease;
        try {
            targetLease = this.coordinator.claim(session, targetKey, Set.of(javaUuid))
                    .toCompletableFuture()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final Exception e) {
            LOGGER.warn("The claim of the target profile {} failed; the link is aborted", targetKey, e);
            return Result.FAILED;
        }
        //After the supersede, the transaction's write permission IS the new lease:
        //it must be current for this connection (displacement, disconnect or a
        //release since the claim → the whole attempt is stale and B is handed back).
        if (!this.coordinator.isCurrent(targetLease) || !AccountLoginPolicy.isAllowedForSession(session)) {
            this.coordinator.release(targetLease);
            this.reacquireBedrockAfterAbort(session);
            return Result.STALE;
        }
        //From here the transaction either commits (then B's lease becomes the
        //session's) or aborts (then B's lease is handed straight back).

        final String operationId = UUID.randomUUID().toString();
        final LinkTransactionJournal journal = this.journalFactory.get();
        // Declared outside the try so the late-failure fallback can keep the
        // blacklist delta on the journal (task 6).
        @Nullable LinkTransactionJournal.Entry prepared = null;
        dev.connectplus.access.AccessBindingCoordinator.LinkCommitResult commitOutcome =
                dev.connectplus.access.AccessBindingCoordinator.LinkCommitResult.COMPLETE;
        try {
            // ---- Step 3: write PREPARED; load/create B; persist credentials ----
            // The blacklist inheritance delta is recorded BEFORE the index commit
            // (task 6): a committed binding with an unwritten delta is recoverable.
            prepared = new LinkTransactionJournal.Entry(operationId,
                    LinkTransactionJournal.Operation.LINK, xuid, javaUuid, expectedRevision, targetRevision,
                    LinkTransactionJournal.Phase.PREPARED, java.util.List.of());
            journal.write(prepared);
            final PlayerData targetData = this.playerStore.load(targetKey); //missing file = empty default
            //The new credentials follow B's saveLoginInfo (§1.1 item 3).
            if (targetData.saveLoginInfo) {
                targetData.accountBlob = this.tokenStore.encrypt(verifiedAccount.toJson());
            } else {
                targetData.accountBlob = null;
            }
            this.requireAccountPermission(session, targetLease);
            this.playerStore.save(targetKey, targetData);
            //A must not be deleted yet (§4.1 step 3) — nothing has touched A's file.

            // ---- Step 4: THE binding commit point (atomic index revision) ----
            //replace() is OUTSIDE the committed-ending region: every exception it
            //throws happens BEFORE the commit lands (a revision conflict from a
            //concurrent winning link/unlink, a corrupt index, a failed atomic
            //write) — those are aborts with A fully preserved, never committed
            //endings. Only statements AFTER this call returns may be treated as
            //"the binding is committed".
            try {
                if (this.accessBindingCoordinator != null) {
                    // The intent and index commit share one critical section with console
                    // add/remove; no stale inheritance may be lost or resurrected.
                    record AccessCommit(LinkTransactionJournal.Entry intent,
                                        dev.connectplus.access.AccessBindingCoordinator.LinkCommitResult result) {}
                    final AccessCommit committedAccess = this.accessBindingCoordinator.withCommitLock(() -> {
                        final LinkTransactionJournal.Entry intent = new LinkTransactionJournal.Entry(operationId,
                                LinkTransactionJournal.Operation.LINK, xuid, javaUuid, expectedRevision, targetRevision,
                                LinkTransactionJournal.Phase.PREPARED,
                                this.accessBindingCoordinator.blacklistInheritanceDelta(xuid, javaUuid));
                        journal.write(intent);
                        final var outcome = this.accessBindingCoordinator.commitLink(journal, intent, () -> {
                                this.requireAccountPermission(session, targetLease);
                                this.identityLinkStore.replace(expectedRevision, commitMap(snapshot, xuid, javaUuid));
                                return null;
                            });
                        return new AccessCommit(intent, outcome);
                    });
                    prepared = committedAccess.intent();
                    commitOutcome = committedAccess.result();
                } else {
                    this.requireAccountPermission(session, targetLease);
                    this.identityLinkStore.replace(expectedRevision, commitMap(snapshot, xuid, javaUuid));
                }
            } catch (final Exception commitFailure) {
                try {
                    journal.clear(operationId);
                } catch (final IOException journalFailure) {
                    LOGGER.warn("The journal of the link {} whose index commit failed could not be cleaned;"
                            + " the startup recovery will resolve it from the index state", operationId, journalFailure);
                }
                this.coordinator.release(targetLease);
                LOGGER.warn("The index commit of the link {}->{} failed; the binding is NOT committed"
                        + " and the transaction aborts with A preserved", xuid, javaUuid, commitFailure);
                this.reacquireBedrockAfterAbort(session);
                return Result.FAILED;
            }
            //=============================================================
            // THE COMMIT POINT IS BEHIND US: from here every possible ending
            // is a committed one — finishSwitch + COMMITTED or COMMITTED_CLEANUP
            // _PENDING. Never release B's lease, never re-claim A's bedrock
            // usage, never return FAILED past this line (R2): the delete and
            // the journal share one disk, so a double failure (delete fails
            // AND the CLEANUP_PENDING write fails) is realistic, and a
            // swallowed journal failure must not roll the caller back onto
            // the discarded A.
            //=============================================================
            try {
                // ---- Step 5: delete A's profile; complete the switch ---------
                this.playerStore.delete(ownBedrockKey);
                if (commitOutcome == dev.connectplus.access.AccessBindingCoordinator.LinkCommitResult.COMPLETE) {
                    journal.clear(operationId); // inheritance AND original profile cleanup finished
                }
                this.finishSwitch(session, targetKey, targetData, targetLease, verifiedAccount);
                return commitOutcome == dev.connectplus.access.AccessBindingCoordinator.LinkCommitResult.ACCESS_PENDING
                        ? Result.COMMITTED_ACCESS_PENDING : Result.COMMITTED;
            } catch (final Exception postCommitFailure) {
                //The binding is committed; only the cleanup failed. Record
                //CLEANUP_PENDING, keep the committed binding, keep using B —
                //and never fall back to A's old file (R2). The journal write
                //below may itself fail in any way (same dead disk): the binding
                //stays committed either way — the startup recovery re-derives the
                //truth from the index revision + actual mapping (§4.1), and
                //the leftover journal (if any) resolves against it.
                LOGGER.error("The committed binding {}->{} could not finish its cleanup;"
                        + " recovery will continue the cleanup", xuid, javaUuid, postCommitFailure);
                this.writeCleanupPendingQuietly(journal, prepared, commitOutcome);
                this.finishSwitch(session, targetKey, targetData, targetLease, verifiedAccount);
                return commitOutcome == dev.connectplus.access.AccessBindingCoordinator.LinkCommitResult.ACCESS_PENDING
                        ? Result.COMMITTED_ACCESS_PENDING : Result.COMMITTED_CLEANUP_PENDING;
            }
        } catch (final IOException | RuntimeException failure) {
            // ---- fallback: failures BEFORE the commit point (or escaping the
            // committed ending itself) ------------------------------------------
            //The commit-throwing branch above returns directly, so this catch is
            //reached by PRE-commit failures (the PREPARED write, the B profile
            //load/save) — or by a RuntimeException escaping the committed ending
            //(finishSwitch twice-throwing). Classification follows the recovery's
            //rule — committed index revision AND the actual mapping (a phase
            //string alone never decides, and a concurrent winner's revision
            //without our mapping is still an abort for THIS operation).
            final IdentityLinkStore.Snapshot current = this.identityLinkStore.snapshot();
            final boolean committed = current.revision() >= targetRevision
                    && javaUuid.equals(current.javaUuidFor(xuid));
            if (committed) {
                //The binding committed despite the late failure: keep it, keep B.
                LOGGER.error("The link failed after the binding commit point; the binding {}->{} stays"
                        + " committed and the cleanup is pending", xuid, javaUuid, failure);
                this.writeCleanupPendingQuietly(journal, prepared, commitOutcome);
                return commitOutcome == dev.connectplus.access.AccessBindingCoordinator.LinkCommitResult.ACCESS_PENDING
                        ? Result.COMMITTED_ACCESS_PENDING : Result.COMMITTED_CLEANUP_PENDING;
            }
            // ---- genuine pre-commit abort ---------------------------------------
            //Keep A's profile, hand B's usage back, clean the journal. The
            //credentials already written for B are NOT blindly rolled back
            //(they are still valid and B's file is never deleted, §4.1).
            try {
                journal.clear(operationId);
            } catch (final IOException journalFailure) {
                LOGGER.warn("The journal of the aborted link {} could not be cleaned; the startup"
                        + " recovery will resolve it from the index state", operationId, journalFailure);
            }
            this.coordinator.release(targetLease);
            LOGGER.warn("The link transaction aborted before the commit point", failure);
            //§4.1: 失败时释放本次持有的 B 资源；只有 A 的 c2p 和身份仍有效时才重新
            //取得 A 基岩档案的使用权。The claim superseded A's old bedrock lease, so
            //an aborted transaction must re-acquire it — but only while the session's
            //c2p is still alive and its identity still current (a displaced or dead
            //session never gets usage back; its re-join would claim normally anyway).
            this.reacquireBedrockAfterAbort(session);
            return Result.FAILED;
        }
    }

    private void requireAccountPermission(final PlayerSession session, final SessionLease lease) throws IOException {
        if (!AccountLoginPolicy.isAllowedForSession(session) || !this.coordinator.isCurrent(lease)) {
            throw new IOException("The account permission or transaction lease expired");
        }
    }

    /**
     * Records the CLEANUP_PENDING phase for a committed binding, swallowing ANY
     * failure of the journal write itself (checked or unchecked): past the
     * commit point a failed journal write can never turn the outcome into an
     * abort — the post-commit region must be unable to reach release/
     * re-acquire/FAILED by any throw. The startup recovery resolves the truth
     * from the committed index revision and actual mapping (a phase string
     * alone never decides, §4.1), so a missing CLEANUP_PENDING entry is safe.
     */
    private void writeCleanupPendingQuietly(final LinkTransactionJournal journal,
                                            final LinkTransactionJournal.Entry prepared,
                                            final dev.connectplus.access.AccessBindingCoordinator.LinkCommitResult commitOutcome) {
        try {
            // Phase transitions keep the blacklist delta (task 6); a pending delta
            // never degrades into a plain CLEANUP_PENDING record.
            final LinkTransactionJournal.Phase phase = commitOutcome
                    == dev.connectplus.access.AccessBindingCoordinator.LinkCommitResult.ACCESS_PENDING
                    ? LinkTransactionJournal.Phase.ACCESS_PENDING : LinkTransactionJournal.Phase.CLEANUP_PENDING;
            journal.write(LinkTransactionJournal.Entry.withPhase(prepared, phase));
        } catch (final Exception journalFailure) {
            LOGGER.error("The post-commit journal entry of the committed binding {}->{} could not be"
                    + " written; the committed index still decides the recovery",
                    prepared.xuid(), prepared.javaUuid(), journalFailure);
        }
    }

    /**
     * Re-acquires A's bedrock profile usage after an aborted link. The claim in
     * step 2 superseded the old lease, so without this the session would be left
     * without write permission for its own profile. The re-acquisition is
     * NON-DISPLACING: a newer verified login of the same identity that claimed
     * the freed resources while the transaction was aborting keeps its lease —
     * "后来通过身份验证的连接顶掉旧连接" is one-directional (Review Focus 3).
     */
    private void reacquireBedrockAfterAbort(final PlayerSession session) {
        try {
            final io.netty.channel.Channel c2p = session.c2pChannel;
            if (c2p == null || !c2p.isActive() || session.displaced) {
                return; //A is gone or displaced: nobody re-acquires anything
            }
            final ClientIdentity identity = this.identityOf(session);
            if (identity == null || identity.kind() != ClientIdentity.Kind.VERIFIED_BEDROCK) {
                return;
            }
            final ProfileKey ownBedrockKey = ProfileKey.bedrockProfile(identity.xuid());
            final SessionLease reacquired = this.coordinator.claimNonDisplacing(session, ownBedrockKey, Set.of())
                    .toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (reacquired == null || !this.coordinator.isCurrent(reacquired)) {
                return; //someone newer holds the profile: they keep it
            }
            //Only re-publish the profile if the session still runs on it (it was
            //never switched — the abort path publishes nothing).
            if (ownBedrockKey.equals(session.profileKey)) {
                session.lease = reacquired;
                session.generation = reacquired.generation();
            } else {
                this.coordinator.release(reacquired);
            }
        } catch (final Exception e) {
            LOGGER.warn("The bedrock usage of session {} could not be re-acquired after the aborted link;"
                    + " a lobby return re-claims it through the normal join flow", session.connectionId, e);
        }
    }

    /**
     * The new committed mapping: the old snapshot plus exactly this one-to-one
     * binding. The one-to-one constraint was pre-checked in step 1 and is
     * re-checked by {@link IdentityLinkStore#replace}'s validation.
     */
    private static Map<String, UUID> commitMap(final IdentityLinkStore.Snapshot snapshot,
                                               final String xuid, final UUID javaUuid) {
        final Map<String, UUID> links = new LinkedHashMap<>(snapshot.links());
        links.put(xuid, javaUuid);
        return links;
    }

    /**
     * §4.2, task 7: the unlink transaction (binding). Only the CURRENT trusted
     * bedrock session may unlink its OWN existing mapping. Serial flow:
     * freeze operations → save B's accepted modifications → write the UNLINK
     * journal entry → atomically remove the index mapping (THE unlink commit
     * point) → invalidate the session's old lease/account callbacks → clear the
     * in-memory B credentials → release the session's B lease → create the
     * blank A bedrock profile. The existing {@code logout()} is NEVER called:
     * it wipes the saved Java login, which unlink must preserve (B's
     * bookmarks/settings/accountBlob survive byte-identically, nothing is
     * restored for A — the old bookmarks were deleted at the first link).
     *
     * <p><b>Commit point discipline (mirrors §4.1).</b> Every failure of the
     * index {@code replace} happens BEFORE the commit: those keep the original
     * association, do NOT clear B's profile and change no session state. Once
     * the mapping removal is committed, the unlink can never be rolled back to
     * "linked" and B's credentials can never be used again by this session —
     * a failing blank-A creation leaves the lobby in a restricted blank state
     * with the bounded retry applied, the CLEANUP_PENDING journal for the
     * startup recovery (which continues the blank-profile creation), and a
     * live lease on the blank bedrock profile.</p>
     *
     * <p><b>Serialization (Review Focus R5).</b> The new claim on the SAME
     * session supersedes its old lease inside the coordinator's serial domain,
     * which is the same machine B's other new logins arbitrate on; a session
     * displaced before or during the transaction fails stale WITHOUT releasing
     * the later holder's lease (a superseded/invalid lease's release is a
     * no-op, and the transaction only ever releases leases it still holds).</p>
     */
    @Override
    public CompletionStage<Result> unlink(final PlayerSession session) {
        final CompletableFuture<Result> result = new CompletableFuture<>();
        this.executor.execute(() -> {
            try {
                result.complete(this.unlinkBlocking(session));
            } catch (final Exception e) {
                LOGGER.warn("The unlink transaction of session {} failed", session.connectionId, e);
                result.complete(Result.FAILED);
            }
        });
        return result;
    }

    /**
     * The whole §4.2 state machine, blocking. Called on this service's executor
     * (blocking IO is legal there). Same claim/serialization story as
     * {@link #linkBlocking}: the transaction's write permission after step 2 is
     * the freshly granted lease, re-checked before every write.
     */
    private Result unlinkBlocking(final PlayerSession session) throws IOException {
        // ---- Step 1: verify identity and the current mapping -------------------
        final ClientIdentity identity = identityOf(session);
        if (identity == null || identity.kind() != ClientIdentity.Kind.VERIFIED_BEDROCK) {
            //Only a trusted bedrock session can unlink; a Java player owns its
            //profile outright and an unverified identity owns nothing.
            return identity == null ? Result.STALE : Result.FAILED;
        }
        if (!AccountLoginPolicy.isAllowedForSession(session)) return Result.STALE;
        if (session.lease == null || !this.coordinator.isCurrent(session.lease)
                || session.profileKey == null || session.playerData == null) {
            //Displaced, released, or never loaded: the session is no longer current.
            return Result.STALE;
        }
        final String xuid = identity.xuid();
        final ProfileKey targetBedrockKey = ProfileKey.bedrockProfile(xuid);
        final IdentityLinkStore.Snapshot snapshot = this.identityLinkStore.snapshot();
        final UUID javaUuid = snapshot.javaUuidFor(xuid);
        if (javaUuid == null || !ProfileKey.javaProfile(javaUuid).equals(session.profileKey)) {
            //Unlinked bedrock player (own BEDROCK profile) or a mapping that
            //does not match the profile this session is running: nothing to unlink.
            return Result.FAILED;
        }
        //§4.1/§4.2: 恢复完成前禁止该 XUID 执行新绑定/解绑.
        if (!linkAllowedNow(this.identityLinkStore, this.journalFactory.get(), xuid)) {
            LOGGER.warn("The unlink attempt of XUID {} is blocked: an earlier transaction of this identity"
                    + " is still awaiting recovery", xuid);
            return Result.FAILED;
        }
        final long expectedRevision = snapshot.revision();
        final long targetRevision = expectedRevision + 1;
        final ProfileKey linkedJavaKey = session.profileKey;

        // ---- Step 2: freeze operations; acquire B's exclusive use --------------
        //The claim supersedes the session's old lease: from that moment the old
        //lease is no longer current, so the session's own screens/async writes are
        //refused (the freeze) — and the serial coordinator is the SAME machine a
        //concurrent new B login arbitrates on, so a racing displacement either
        //happened before this claim (step-1/step-2 staleness catches it) or loses
        //against this claim.
        final SessionLease transactionLease;
        try {
            transactionLease = this.coordinator.claim(session, linkedJavaKey, Set.of(javaUuid))
                    .toCompletableFuture()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final Exception e) {
            LOGGER.warn("The claim of the linked profile {} failed; the unlink is aborted", linkedJavaKey, e);
            return Result.FAILED;
        }
        if (!this.coordinator.isCurrent(transactionLease) || !AccountLoginPolicy.isAllowedForSession(session)) {
            //Superseded (a new B login won between the claim and here) or the
            //connection is gone: stale, and NO release of a lease we do not hold
            //— the later holder's lease is untouchable (R5). A superseded lease's
            //release would be a no-op anyway, but not calling it is the contract.
            if (transactionLease != null && this.coordinator.isCurrent(transactionLease)) {
                this.coordinator.release(transactionLease);
            }
            this.reacquireAfterAbort(session, linkedJavaKey, Set.of(javaUuid));
            return Result.STALE;
        }

        final String operationId = UUID.randomUUID().toString();
        final LinkTransactionJournal journal = this.journalFactory.get();
        try {
            // ---- Step 3: save B's completed modifications; write UNLINK PREPARED
            //B's accepted in-memory modifications reach disk before the binding is
            //removed (§4.2: 保存 B 已完成的修改) — exactly the displacement save
            //discipline, applied to the transaction's own freeze. The persisted
            //account blob is only rewritten when saveLoginInfo is off (wipe);
            //nothing about the live credentials changes until a COMMITTED ending.
            this.requireAccountPermission(session, transactionLease);
            this.saveAcceptedModifications(session);
            journal.write(new LinkTransactionJournal.Entry(operationId, LinkTransactionJournal.Operation.UNLINK,
                    xuid, javaUuid, expectedRevision, targetRevision, LinkTransactionJournal.Phase.PREPARED));

            // ---- Step 4: THE unlink commit point (atomic index revision) --------
            //Review Focus 1 (R5 round-2 fix): the save + journal write span the
            //window in which a later verified B login can win the coordinator
            //race and displace this session. The currency is therefore re-checked
            //IMMEDIATELY before the index commit — a displaced unlink must never
            //remove the mapping while a newer holder runs B's profile.
            if (!this.coordinator.isCurrent(transactionLease) || !AccountLoginPolicy.isAllowedForSession(session)) {
                try {
                    journal.clear(operationId);
                } catch (final IOException journalFailure) {
                    LOGGER.warn("The journal of the displaced unlink {} could not be cleaned; the startup"
                            + " recovery will resolve it from the index state", operationId, journalFailure);
                }
                if (this.coordinator.isCurrent(transactionLease)) {
                    //The lease is somehow still current although the session was
                    //displaced: hand the transaction's own lease back.
                    this.coordinator.release(transactionLease);
                }
                this.reacquireAfterAbort(session, linkedJavaKey, Set.of(javaUuid));
                return Result.STALE;
            }
            //replace() is OUTSIDE the committed-ending region: every exception it
            //throws happens BEFORE the commit (a revision conflict from a
            //concurrent winner, a corrupt index, a failed atomic write) — those
            //are aborts that keep the original association and B's profile, never
            //committed endings. Only statements AFTER this call returns may be
            //treated as "the unlink is committed".
            try {
                final Map<String, UUID> removed = new LinkedHashMap<>(snapshot.links());
                removed.remove(xuid);
                if (this.accessBindingCoordinator != null) {
                    // The unlink commit shares the list commit critical section (task 6);
                    // unlink itself never touches the lists.
                    this.accessBindingCoordinator.withCommitLock(() -> {
                        this.identityLinkStore.replace(expectedRevision, removed);
                        return null;
                    });
                } else {
                    this.identityLinkStore.replace(expectedRevision, removed);
                }
            } catch (final Exception commitFailure) {
                try {
                    journal.clear(operationId);
                } catch (final IOException journalFailure) {
                    LOGGER.warn("The journal of the unlink {} whose index commit failed could not be cleaned;"
                            + " the startup recovery will resolve it from the index state", operationId, journalFailure);
                }
                //The transaction's own lease is handed back; the abort re-acquires
                //the (unchanged) linked profile usage for the still-live session.
                this.coordinator.release(transactionLease);
                LOGGER.warn("The index commit of the unlink of XUID {} failed; the binding is NOT removed"
                        + " and the transaction aborts with the association kept", xuid, commitFailure);
                this.reacquireAfterAbort(session, linkedJavaKey, Set.of(javaUuid));
                return Result.FAILED;
            }
            //=============================================================
            // THE COMMIT POINT IS BEHIND US: the binding is removed on disk.
            // From here every ending is a committed one — the session NEVER
            // rolls back to linked and NEVER uses B's credentials again. B's
            // own lease (the pre-unlink current one) is superseded by the
            // transaction claim already; the account callbacks are invalidated
            // by the lease invalidation + the cleared session state (§6).
            //=============================================================
            // ---- Step 5: invalidate the old lease/account callbacks, clear the
            // in-memory B credentials, release the session's B lease -------------
            //The transaction lease IS the current right of use; the old lease was
            //superseded by the claim. Clearing the account while the lease is
            //current makes every stale account callback of this session drop (§6:
            //解绑后到达的结果丢弃); the generation bump removes the old right.
            session.account = null;
            // ---- Step 6: create the blank A bedrock profile ---------------------
            //A gets a fresh empty profile: the old bookmarks were deleted at the
            //first link, nothing is restored and nothing is copied from B (§1.3).
            //A single bounded retry: the committed unlink must not degrade into a
            //FAILED (never roll back to linked, R2/R5), but a transient disk
            //hiccup should not force a restart.
            IOException lastBlankFailure = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    final PlayerData blank = this.playerStore.load(targetBedrockKey);
                    blank.bookmarks.clear();
                    blank.accountBlob = null;
                    this.playerStore.save(targetBedrockKey, blank);
                    lastBlankFailure = null;
                    break;
                } catch (final IOException e) {
                    lastBlankFailure = e;
                }
            }
            // ---- Step 7: land the blank profile on the session (finishSwitch) --
            if (lastBlankFailure == null) {
                final PlayerData blank = this.playerStore.load(targetBedrockKey);
                this.finishSwitch(session, targetBedrockKey, blank, transactionLease, null);
                try {
                    journal.clear(operationId);
                } catch (final IOException journalFailure) {
                    LOGGER.warn("The journal of the finished unlink {} could not be cleaned; the startup"
                            + " recovery will resolve it from the index state", operationId, journalFailure);
                }
                return Result.COMMITTED;
            }
            //The blank-profile creation failed even after the retry: COMMITTED —
            //the binding stays removed, the session stays on the (in-memory)
            //blank bedrock profile and can never reach B's credentials again.
            //Record CLEANUP_PENDING so the startup recovery continues the blank
            //profile creation; keep the restricted blank state live (§4.2).
            LOGGER.error("The committed unlink of XUID {} could not create the blank bedrock profile;"
                    + " the restricted blank state stays and recovery will continue the creation", xuid, lastBlankFailure);
            this.writeUnlinkCleanupPendingQuietly(journal, operationId, xuid, javaUuid,
                    expectedRevision, targetRevision);
            final PlayerData restrictedBlank = new PlayerData(targetBedrockKey);
            this.finishSwitch(session, targetBedrockKey, restrictedBlank, transactionLease, null);
            return Result.COMMITTED_CLEANUP_PENDING;
        } catch (final IOException | RuntimeException failure) {
            // ---- fallback: failures BEFORE the commit point ----------------------
            //The commit-throwing branch above returns directly, so this catch is
            //reached by PRE-commit failures (the save of B's modifications, the
            //PREPARED write) — or by a RuntimeException escaping the committed
            //ending itself. Classification follows the recovery's rule — the
            //committed state is derived from the index revision and the ACTUAL
            //mapping, never from a phase string.
            final IdentityLinkStore.Snapshot current = this.identityLinkStore.snapshot();
            final boolean committed = current.revision() >= targetRevision
                    && !current.links().containsKey(xuid);
            if (committed) {
                //The unlink committed despite the late failure: keep it, never
                //roll back to linked, keep B's credentials off the session.
                LOGGER.error("The unlink failed after the binding removal commit point; the removal of"
                        + " XUID {} stays committed and the cleanup is pending", xuid, failure);
                this.writeUnlinkCleanupPendingQuietly(journal, operationId, xuid, javaUuid,
                        expectedRevision, targetRevision);
                final PlayerData restrictedBlank = new PlayerData(targetBedrockKey);
                this.finishSwitch(session, targetBedrockKey, restrictedBlank, transactionLease, null);
                return Result.COMMITTED_CLEANUP_PENDING;
            }
            // ---- genuine pre-commit abort ---------------------------------------
            //The association is kept, B's profile is untouched, the journal is
            //cleaned, the transaction lease is handed back and the (still live)
            //session re-acquires the linked profile usage. No GUI state changed.
            try {
                journal.clear(operationId);
            } catch (final IOException journalFailure) {
                LOGGER.warn("The journal of the aborted unlink {} could not be cleaned; the startup"
                        + " recovery will resolve it from the index state", operationId, journalFailure);
            }
            this.coordinator.release(transactionLease);
            LOGGER.warn("The unlink transaction aborted before the commit point", failure);
            this.reacquireAfterAbort(session, linkedJavaKey, Set.of(javaUuid));
            return Result.FAILED;
        }
    }

    /**
     * B's accepted modifications reach disk before the unlink removes the
     * binding (§4.2: 保存 B 已完成的修改). IMPORTANT review fix: this save is
     * strictly non-destructive — the in-memory account/blob are NEVER touched
     * and the profile is persisted exactly as accepted, so every abort path
     * (pre-commit failure, displaced transaction) leaves a still-linked session
     * with its working backend account fully intact. The saveLoginInfo=off
     * credential wipe stays the disconnect/displacement machinery's job (§5/§6)
     * — on a COMMITTED unlink the blank A profile carries no credentials anyway.
     */
    private void saveAcceptedModifications(final PlayerSession session) throws IOException {
        final PlayerData data = session.playerData;
        if (data == null) return;
        this.playerStore.save(data.key, data);
    }

    /**
     * Records the CLEANUP_PENDING phase for a committed unlink, swallowing ANY
     * failure of the journal write itself: past the commit point a failed
     * journal write can never turn the outcome into an abort. The startup
     * recovery re-derives the truth from the index revision and the actual
     * mapping (a phase string alone never decides, §4.2).
     */
    private void writeUnlinkCleanupPendingQuietly(final LinkTransactionJournal journal, final String operationId,
                                                  final String xuid, final UUID javaUuid,
                                                  final long expectedRevision, final long targetRevision) {
        try {
            journal.write(new LinkTransactionJournal.Entry(operationId, LinkTransactionJournal.Operation.UNLINK,
                    xuid, javaUuid, expectedRevision, targetRevision, LinkTransactionJournal.Phase.CLEANUP_PENDING));
        } catch (final Exception journalFailure) {
            LOGGER.error("The CLEANUP_PENDING journal entry of the committed unlink of XUID {} could not be"
                    + " written; the committed index still decides the recovery", xuid, journalFailure);
        }
    }

    /**
     * Re-acquires the session's previous profile usage after an aborted unlink.
     * The transaction claim superseded the old lease, so without this the
     * session would be left without write permission. The re-acquisition is
     * NON-DISPLACING: a newer verified login of the same identity that claimed
     * the freed resources while the transaction was aborting keeps its lease —
     * the displacement direction is only ever "newer displaces older"
     * (Review Focus 3), so the aborted session simply gets nothing when it
     * already lost and re-claims through the normal join flow on its next
     * lobby return.
     */
    private void reacquireAfterAbort(final PlayerSession session, final ProfileKey profileKey,
                                     final Set<UUID> accountUuids) {
        try {
            final io.netty.channel.Channel c2p = session.c2pChannel;
            if (c2p == null || !c2p.isActive() || session.displaced) {
                return; //the session is gone or displaced: nobody re-acquires anything
            }
            final ClientIdentity identity = this.identityOf(session);
            if (identity == null || identity.kind() != ClientIdentity.Kind.VERIFIED_BEDROCK) {
                return;
            }
            final SessionLease reacquired = this.coordinator.claimNonDisplacing(session, profileKey, accountUuids)
                    .toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (reacquired == null || !this.coordinator.isCurrent(reacquired)) {
                return; //someone newer holds the resources: they keep them
            }
            //Only re-publish the profile if the session still runs on it (the
            //abort path publishes nothing).
            if (profileKey.equals(session.profileKey)) {
                session.lease = reacquired;
                session.generation = reacquired.generation();
            } else {
                this.coordinator.release(reacquired);
            }
        } catch (final Exception e) {
            LOGGER.warn("The usage of session {} could not be re-acquired after the aborted unlink;"
                    + " a lobby return re-claims it through the normal join flow", session.connectionId, e);
        }
    }

    /**
     * Completes the in-memory switch onto B: publishes the new profile key,
     * data, account and lease on the session (only after a successful commit —
     * §4.1: 成功后才对 GUI 和切服流程发布新的活动档案) and hands the old
     * bedrock-profile lease back. The wire identity is untouched.
     */
    private void finishSwitch(final PlayerSession session, final ProfileKey targetKey,
                              final PlayerData targetData, final SessionLease targetLease,
                              @Nullable final CPAccount verifiedAccount) {
        session.playerData = targetData;
        session.profileKey = targetKey;
        session.account = AccountLoginPolicy.isAllowedForSession(session) && this.coordinator.isCurrent(targetLease)
                ? verifiedAccount : null;
        session.generation = targetLease.generation();
        final SessionLease oldLease = session.lease;
        session.lease = targetLease;
        if (oldLease != null && oldLease != targetLease) {
            this.coordinator.release(oldLease);
        }
    }

    /**
     * The ClientIdentity captured on the session's c2p channel — the only
     * trusted source of the XUID (never a claimed name or entry UUID).
     */
    private static @Nullable ClientIdentity identityOf(final PlayerSession session) {
        final io.netty.channel.Channel c2p = session.c2pChannel;
        if (c2p == null) {
            return null;
        }
        return c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).get();
    }

    // ---- startup recovery -----------------------------------------------------

    /** The startup recovery result: which XUIDs aborted, which finished their cleanup, which failed. */
    public record RecoveryOutcome(Set<String> aborted,
                                  Set<String> cleanedUp,
                                  Map<String, java.io.IOException> failures) {
    }

    /**
     * Completes the interrupted link/unlink transactions at startup, BEFORE
     * profile loading opens for the affected identities (§4.1/§4.2: 启动时先完成
     * 事务恢复). The phase string alone never decides: every entry is resolved
     * against the committed index revision and the actual mapping.
     *
     * <ul>
     *   <li>LINK — the index shows the operation's target revision AND mapping
     *       → the commit happened: continue the cleanup (delete the old bedrock
     *       file), then clean the journal. Anything else → aborted, A's file
     *       stays, the journal is cleaned.</li>
     *   <li>UNLINK — the index moved to (or beyond) the target revision WITHOUT
     *       this XUID's mapping → the removal committed: continue the cleanup
     *       (create the blank bedrock profile), then clean the journal. The
     *       mapping still present (or a different one at the target revision)
     *       → the removal never committed: aborted, the association is kept,
     *       the journal is cleaned. A committed unlink NEVER rolls back to
     *       linked (§4.2) and never deletes B's Java profile.</li>
     * </ul>
     *
     * <p>The recovery deletes nothing on the UNLINK path — its cleanup is the
     * blank-profile creation, which cannot damage a later state: a re-linked
     * XUID's fresh bedrock profile must not be replaced, so the blank creation
     * only writes when no profile file exists and the mapping is still absent.</p>
     */
    public static RecoveryOutcome recoverAtStartup(final PlayerStore playerStore,
                                                   final IdentityLinkStore identityLinkStore,
                                                   final LinkTransactionJournal journal) {
        return recoverAtStartup(playerStore, identityLinkStore, journal, null);
    }

    /**
     * Recovery with the access-list binding coordinator (task 6): a committed
     * LINK operation with a recorded blacklist delta replays it idempotently
     * BEFORE the original cleanup, and the pending keys clear only after the
     * replay AND the cleanup succeeded. A failed replay keeps the journal and
     * reports the XUID in the failures map (blocked from link/unlink until the
     * next startup recovery), matching the existing recovery contract.
     */
    public static RecoveryOutcome recoverAtStartup(final PlayerStore playerStore,
                                                   final IdentityLinkStore identityLinkStore,
                                                   final LinkTransactionJournal journal,
                                                   @Nullable final dev.connectplus.access.AccessBindingCoordinator accessBindingCoordinator) {
        final Set<String> aborted = new LinkedHashSet<>();
        final Set<String> cleanedUp = new LinkedHashSet<>();
        final Map<String, java.io.IOException> failures = new LinkedHashMap<>();
        final Map<String, LinkTransactionJournal.Entry> entries;
        try {
            entries = journal.readAll();
        } catch (final IOException e) {
            LOGGER.error("The transaction journal could not be read; link/unlink stays blocked for every"
                    + " affected identity until it is repaired", e);
            return new RecoveryOutcome(Set.of(), Set.of(), Map.of());
        }
        for (final Map.Entry<String, LinkTransactionJournal.Entry> journalEntry : entries.entrySet()) {
            final LinkTransactionJournal.Entry entry = journalEntry.getValue();
            try {
                final IdentityLinkStore.Snapshot snapshot = identityLinkStore.snapshot();
                final UUID actuallyLinked = snapshot.javaUuidFor(entry.xuid());
                if (entry.operation() == LinkTransactionJournal.Operation.LINK) {
                    final boolean committed = actuallyLinked != null
                            && actuallyLinked.equals(entry.javaUuid())
                            && snapshot.revision() >= entry.targetRevision();
                    if (committed) {
                        //The binding committed: replay the blacklist delta first
                        //(idempotent), then continue the cleanup, never roll back.
                        if (accessBindingCoordinator != null && !entry.blacklistAdds().isEmpty()) {
                            accessBindingCoordinator.recoverCommittedLink(entry);
                        }
                        playerStore.delete(ProfileKey.bedrockProfile(entry.xuid()));
                        journal.clear(entry.operationId());
                        cleanedUp.add(entry.xuid());
                        if (accessBindingCoordinator != null && !entry.blacklistAdds().isEmpty()) {
                            // The delta replayed and the original cleanup done: the
                            // pending inheritance state for these keys is resolved.
                            final java.util.Set<dev.connectplus.access.AccessKey> pendingKeys =
                                    new java.util.LinkedHashSet<>();
                            for (final dev.connectplus.access.AccessEntry add : entry.blacklistAdds()) {
                                pendingKeys.add(add.key());
                            }
                            try {
                                accessBindingCoordinator.pendingRecovered(pendingKeys);
                            } catch (final IOException e) {
                                LOGGER.warn("The pending inheritance keys of XUID {} could not be cleared", entry.xuid(), e);
                            }
                        }
                    } else {
                        //The commit never happened: abort, A stays, journal cleaned.
                        journal.clear(entry.operationId());
                        aborted.add(entry.xuid());
                    }
                } else {
                    // ---- UNLINK (§4.2) -----------------------------------------
                    final boolean committed = snapshot.revision() >= entry.targetRevision()
                            && actuallyLinked == null;
                    if (committed) {
                        //The removal committed: continue the cleanup — create the
                        //blank bedrock profile (restricted blank state continues),
                        //never roll back to linked, never touch B's Java profile.
                        if (!playerStore.exists(ProfileKey.bedrockProfile(entry.xuid()))) {
                            final PlayerData blank = new PlayerData(ProfileKey.bedrockProfile(entry.xuid()));
                            blank.accountBlob = null;
                            playerStore.save(ProfileKey.bedrockProfile(entry.xuid()), blank);
                        }
                        journal.clear(entry.operationId());
                        cleanedUp.add(entry.xuid());
                    } else {
                        //The removal never committed: abort, the association stays.
                        journal.clear(entry.operationId());
                        aborted.add(entry.xuid());
                    }
                }
            } catch (final IOException e) {
                LOGGER.error("The recovery of {} operation {} (XUID {}) failed; the XUID stays blocked"
                        + " from link/unlink until the next startup recovery", entry.operation(), entry.operationId(), entry.xuid(), e);
                failures.put(entry.xuid(), e);
            } catch (final RuntimeException e) {
                LOGGER.error("The recovery of {} operation {} hit an unexpected failure", entry.operation(), entry.operationId(), e);
                failures.put(entry.xuid(), new java.io.IOException(e.getMessage(), e));
            }
        }
        return new RecoveryOutcome(aborted, cleanedUp, failures);
    }

    /**
     * Whether link/unlink operations for {@code xuid} may run right now: an
     * unfinished journal operation for the same XUID blocks them (§4.1: 恢复完成前
     * 禁止该 XUID 执行新绑定/解绑). Used at startup ordering and by later tasks.
     */
    public static boolean linkAllowedNow(final IdentityLinkStore identityLinkStore,
                                         final LinkTransactionJournal journal,
                                         final String xuid) {
        try {
            for (final LinkTransactionJournal.Entry entry : journal.readAll().values()) {
                if (entry.xuid().equals(xuid)) {
                    return false; // an unfinished recovery blocks this XUID
                }
            }
            return true;
        } catch (final IOException e) {
            return false; // an unreadable journal blocks everything: conservative
        }
    }

    PlayerStore playerStore() {
        return this.playerStore;
    }

    AccountSessionCoordinator coordinator() {
        return this.coordinator;
    }

    IdentityLinkStore identityLinkStore() {
        return this.identityLinkStore;
    }

    TokenStore tokenStore() {
        return this.tokenStore;
    }
}
