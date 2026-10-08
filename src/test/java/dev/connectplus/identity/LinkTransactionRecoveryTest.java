package dev.connectplus.identity;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.session.Bookmark;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.AccountSessionCoordinator;
import dev.connectplus.session.SessionLease;
import dev.connectplus.testutil.StubAccount;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5: crash recovery of the binding transaction (Review Focus R2). Every
 * persistence boundary gets injected IO failures / simulated process exits;
 * the pinned invariant: a COMMITTED index never falls back to A's old bedrock
 * file — recovery continues the cleanup instead.
 */
@org.junit.jupiter.api.extension.ExtendWith(dev.connectplus.testutil.AccountPolicyExtension.class)
class LinkTransactionRecoveryTest {

    private static final String XUID_A = "2533274790000001";
    private static final UUID WIRE_A = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000aa");
    private static final UUID JAVA_B = UUID.fromString("12345678-1234-4234-8234-123456789abc");

    @TempDir
    File tempDir;

    private File playersDir() {
        return new File(this.tempDir, "players");
    }

    private PlayerStore store() {
        return new PlayerStore(playersDir());
    }

    private AccountSessionCoordinator coordinator(final PlayerStore store) {
        return new AccountSessionCoordinator(store, () -> null);
    }

    private PlayerSession bedrockSessionA(final PlayerStore store, final AccountSessionCoordinator coordinator) throws Exception {
        final PlayerSession session = new PlayerSession(WIRE_A, "BedrockA");
        session.connectionId = UUID.randomUUID();
        final EmbeddedChannel c2p = new EmbeddedChannel();
        //Production binds the verified identity to the c2p (ClientIdentityCapture);
        //the service reads the XUID only from there.
        dev.connectplus.testutil.TestClientIdentity.bedrock(c2p,
                    ClientIdentity.verifiedBedrock(WIRE_A, "BedrockA", XUID_A, UUID.randomUUID(), UUID.randomUUID().toString()));
        session.c2pChannel = c2p;
        final ProfileKey ownBedrock = ProfileKey.bedrockProfile(XUID_A);
        session.lease = coordinator.claim(session, ownBedrock, java.util.Set.of())
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        session.generation = session.lease.generation();
        session.profileKey = ownBedrock;
        session.playerData = store.load(ownBedrock);
        return session;
    }

    private void seedBedrockProfile(final PlayerStore store, final String bookmarkName) throws IOException {
        final PlayerData data = new PlayerData(ProfileKey.bedrockProfile(XUID_A));
        data.bookmarks.add(new Bookmark(bookmarkName, "a.example", null, 1, 2));
        store.save(data);
    }

    private static StubAccount accountFor(final UUID javaUuid) {
        return new StubAccount() {
            @Override
            public UUID uuid() {
                return javaUuid;
            }

            @Override
            public String toJson() {
                return "{\"java\":\"" + javaUuid + "\"}";
            }
        };
    }

    private File bedrockFile() {
        return new File(new File(playersDir(), "bedrock"), XUID_A + ".json");
    }

    private File indexFile() {
        return new File(playersDir(), "identity-links.json");
    }

    /**
     * A PlayerStore whose selected operation can be made to fail: "save" and
     * "delete" are the two persistence boundaries of the §4.1 transaction
     * besides the index commit (which IdentityLinkStore already guards with its
     * own revision discipline).
     */
    private static class FaultyStore extends PlayerStore {
        final AtomicBoolean failSaves = new AtomicBoolean();
        final AtomicBoolean failDeletes = new AtomicBoolean();

        FaultyStore(final File playersDir) {
            super(playersDir);
        }

        @Override
        public void save(final ProfileKey key, final PlayerData data) throws IOException {
            if (this.failSaves.get()) {
                throw new IOException("disk full (injected)");
            }
            super.save(key, data);
        }

        @Override
        public void delete(final ProfileKey key) throws IOException {
            if (this.failDeletes.get()) {
                throw new IOException("delete failed (injected)");
            }
            super.delete(key);
        }
    }

    // ---- the commit point: index committed but A's deletion fails -------------

    @Test
    void committedIndexWithFailingDeletionReportsCleanupPendingAndKeepsTheBinding() throws Exception {
        final FaultyStore store = new FaultyStore(playersDir());
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        store.failDeletes.set(true); // step 5 of §4.1 will fail

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED_CLEANUP_PENDING, result);
        //THE binding commit point has passed: the binding stays committed.
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A));
        assertTrue(indexFile().exists(), "the committed index revision must be on disk");
        //The journal still records the unfinished cleanup for the startup recovery.
        final LinkTransactionJournal journal = new LinkTransactionJournal(playersDir());
        assertEquals(1, journal.readAll().size(), "the CLEANUP_PENDING operation must stay journaled");
        final LinkTransactionJournal.Entry entry = journal.readAll().values().iterator().next();
        assertEquals(LinkTransactionJournal.Phase.CLEANUP_PENDING, entry.phase());
        assertEquals(XUID_A, entry.xuid());
        assertEquals(JAVA_B, entry.javaUuid());
        //The session switched to B despite the pending cleanup (CLEANUP_PENDING allows using B).
        assertEquals(ProfileKey.javaProfile(JAVA_B), session.profileKey);
    }

    /**
     * Critical-review pin (R2 double failure): the delete fails AND the
     * CLEANUP_PENDING journal write fails (they share one dead disk). The
     * binding IS committed — the outcome must still be finishSwitch +
     * COMMITTED_CLEANUP_PENDING: B's lease stays current, the session never
     * falls back onto A, and no bedrock re-acquisition happens. The startup
     * recovery still resolves the leftover state from the committed index.
     */
    @Test
    void aPostCommitDoubleFailureStillEndsInCleanupPendingWithALiveBLease() throws Exception {
        final FaultyStore store = new FaultyStore(playersDir()) {
            @Override
            public void delete(final ProfileKey key) throws IOException {
                //Simulate the dead disk AFTER the commit: the delete fails, and
                //the CLEANUP_PENDING journal write on the same disk fails too.
                throw new IOException("dead disk (injected, delete + journal share it)");
            }
        };
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        final SessionLease leaseBeforeLink = session.lease;

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED_CLEANUP_PENDING, result,
                "a committed binding must never be reported as FAILED, whatever the journal did");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "the binding stays committed");
        //The B usage stays live: no release, no FAILED path.
        assertNotNull(session.lease, "the session must end with a lease");
        assertTrue(coordinator.isCurrent(session.lease), "B's lease must stay current after the commit");
        assertFalse(coordinator.isCurrent(leaseBeforeLink), "the old bedrock lease stays superseded");
        //The session never rolled back onto A.
        assertEquals(ProfileKey.javaProfile(JAVA_B), session.profileKey,
                "the session must not fall back onto the discarded A profile");
        assertFalse(session.playerData.key.kind() == ProfileKey.Kind.BEDROCK);
        //And the recovery from this exact on-disk state still reaches the committed outcome.
        DefaultIdentityLinkService.recoverAtStartup(store(), links, new LinkTransactionJournal(playersDir()));
        assertFalse(bedrockFile().exists(), "the recovery finishes the cleanup even without a journal entry");
    }

    /**
     * Critical-review pin (post-commit journal write succeeding): the delete
     * fails but the CLEANUP_PENDING entry lands — COMMITTED_CLEANUP_PENDING
     * WITH the in-memory switch (profileKey = B, live lease, account published).
     */
    @Test
    void aPostCommitCleanupPendingStillPerformsTheInMemorySwitch() throws Exception {
        final FaultyStore store = new FaultyStore(playersDir());
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        store.failDeletes.set(true);

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED_CLEANUP_PENDING, result);
        //finishSwitch ran: profile, account, lease and generation all published.
        assertEquals(ProfileKey.javaProfile(JAVA_B), session.profileKey);
        assertNotNull(session.lease);
        assertTrue(coordinator.isCurrent(session.lease), "the session ends with a live lease for B");
        assertEquals(session.lease.generation(), session.generation);
        assertEquals(JAVA_B, session.account.uuid(), "the committed link publishes B's account");
    }

    /**
     * Critical-review pin (pre-commit journal failure stays abortable): the
     * PREPARED write itself fails — the transaction aborts with FAILED, A's
     * file untouched, no binding ever committed, and the session holds a
     * current bedrock lease again (the re-acquisition is pinned, IMPORTANT
     * review finding 3).
     */
    @Test
    void aFailingPreparedJournalWriteAbortsBeforeTheCommit() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        final SessionLease leaseBeforeLink = session.lease;
        final LinkTransactionJournal broken = new LinkTransactionJournal(playersDir()) {
            @Override
            public void write(final Entry entry) throws IOException {
                throw new IOException("journal write failed (injected)");
            }
        };
        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run, () -> broken)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.FAILED, result,
                "a failed PREPARED write aborts before any commit");
        assertEquals(0, links.snapshot().revision(), "no binding may be committed");
        assertTrue(bedrockFile().exists(), "A's profile survives the pre-commit abort");
        //IMPORTANT-review pin: the abort re-acquired A's bedrock usage — the
        //session holds a CURRENT bedrock lease again (not the old superseded one).
        assertNotNull(session.lease, "the session must not be stranded without usage");
        assertTrue(coordinator.isCurrent(session.lease),
                "after a pre-commit abort the session must hold a current bedrock lease again");
        assertFalse(coordinator.isCurrent(leaseBeforeLink),
                "the re-acquired lease is a fresh grant, not the superseded old one");
        assertEquals(ProfileKey.bedrockProfile(XUID_A), session.profileKey,
                "the session still runs on its own bedrock profile");
        assertEquals(session.lease.generation(), session.generation);
    }

    /**
     * IMPORTANT-review pin: a runtime-failed recovery (a journal entry left on
     * disk for this XUID) BLOCKS new link attempts for that XUID
     * (§4.1: 恢复完成前禁止该 XUID 执行新绑定/解绑) — the guard is consulted
     * inside link(), not just available to callers.
     */
    @Test
    void aPendingJournalEntryForTheXuidBlocksNewLinkAttempts() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        //Simulate a leftover journal operation (a runtime-failed recovery).
        final LinkTransactionJournal journal = new LinkTransactionJournal(playersDir());
        journal.write(new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.PREPARED));

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.FAILED, result,
                "a pending recovery for the XUID must block the link attempt");
        assertEquals(0, links.snapshot().revision(), "nothing may be committed while blocked");
        assertTrue(bedrockFile().exists(), "A's profile is untouched while blocked");
        //After the recovery resolves the entry, the XUID can link again.
        DefaultIdentityLinkService.recoverAtStartup(store, links, journal);
        assertTrue(journal.readAll().isEmpty(), "the recovery cleaned the entry");
        final PlayerSession session2 = bedrockSessionA(store, coordinator);
        final IdentityLinkService.Result second = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .link(session2, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(IdentityLinkService.Result.COMMITTED, second,
                "after the recovery finished, the XUID can link again");
    }

    // ---- recovery: index NOT committed (PREPARED only) preserves A ------------

    @Test
    void aPreparedJournalWithoutCommittedIndexRecoversAsAbortedAndPreservesA() throws Exception {
        final PlayerStore store = store();
        seedBedrockProfile(store, "A's own bookmark");
        //Simulate the crash: PREPARED was written but the index was never committed
        //(revision 0, no links) — a phase string alone must never decide this.
        final LinkTransactionJournal journal = new LinkTransactionJournal(playersDir());
        journal.write(new LinkTransactionJournal.Entry(
                UUID.randomUUID().toString(), LinkTransactionJournal.Operation.LINK,
                XUID_A, JAVA_B, 0, 1, LinkTransactionJournal.Phase.PREPARED));

        final DefaultIdentityLinkService.RecoveryOutcome outcome = DefaultIdentityLinkService.recoverAtStartup(store,
                new IdentityLinkStore(playersDir()), journal);

        assertTrue(outcome.aborted().contains(XUID_A), "the PREPARED operation must be recovered as aborted");
        assertTrue(bedrockFile().exists(), "A's profile must be preserved when the index was never committed");
        assertTrue(journal.readAll().isEmpty(), "the aborted operation's journal must be cleaned");
        assertEquals(0, new IdentityLinkStore(playersDir()).snapshot().revision(),
                "no binding may appear from a PREPARED-only crash");
    }

    // ---- recovery: committed but cleanup pending ------------------------------

    @Test
    void aCommittedButUnfinishedCleanupIsCompletedAtStartupAndNeverFallsBackToA() throws Exception {
        final PlayerStore store = store();
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        //Simulate the crash after the commit: the index is committed, A's file
        //still exists, the journal sits at COMMITTED (crash between the commit
        //and the CLEANUP_PENDING phase update).
        links.replace(0, Map.of(XUID_A, JAVA_B));
        seedBedrockProfile(store, "A's own bookmark");
        final LinkTransactionJournal journal = new LinkTransactionJournal(playersDir());
        journal.write(new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.COMMITTED));

        final DefaultIdentityLinkService.RecoveryOutcome outcome = DefaultIdentityLinkService.recoverAtStartup(store, links, journal);

        assertTrue(outcome.cleanedUp().contains(XUID_A), "the cleanup must continue at startup");
        assertFalse(bedrockFile().exists(),
                "R2: the old bedrock file must never survive as a valid profile once the binding is committed");
        assertTrue(journal.readAll().isEmpty(), "the finished recovery cleans the journal");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "the committed binding is untouched by recovery");
    }

    @Test
    void aCleanupPendingJournalAlsoCompletesAtStartup() throws Exception {
        final PlayerStore store = store();
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        links.replace(0, Map.of(XUID_A, JAVA_B));
        seedBedrockProfile(store, "A's own bookmark");
        final LinkTransactionJournal journal = new LinkTransactionJournal(playersDir());
        journal.write(new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.CLEANUP_PENDING));

        DefaultIdentityLinkService.recoverAtStartup(store, links, journal);

        assertFalse(bedrockFile().exists(), "CLEANUP_PENDING recovery deletes the old bedrock file");
        assertTrue(journal.readAll().isEmpty());
    }

    // ---- recovery must never replay an old deletion onto a new profile -------

    @Test
    void recoveryChecksTheActualMappingAndNeverReplaysAnOldDeletion() throws Exception {
        final PlayerStore store = store();
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        //The index has moved on beyond the operation's target revision (an
        //unrelated later commit changed the mapping): the stale PREPARED entry
        //must be recognized as never-committed and must NOT delete blindly.
        links.replace(0, Map.of(XUID_A, JAVA_B));
        seedBedrockProfile(store, "A's own bookmark");
        final LinkTransactionJournal journal = new LinkTransactionJournal(playersDir());
        //The journal claims expected=0/target=1, but the index is already at 1
        //with a DIFFERENT mapping than this operation would have written.
        links.replace(1, Map.of("7777", UUID.fromString("55555555-5555-4555-8555-555555555555")));
        journal.write(new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.PREPARED));

        final DefaultIdentityLinkService.RecoveryOutcome outcome = DefaultIdentityLinkService.recoverAtStartup(store, links, journal);

        assertTrue(outcome.aborted().contains(XUID_A) || outcome.cleanedUp().contains(XUID_A),
                "the operation must be resolved one way or the other, never left dangling");
        assertTrue(bedrockFile().exists(),
                "the mapping the journal expected was never committed: A's file must stay");
        assertEquals(2, links.snapshot().revision(), "recovery must not rewrite the index");
    }

    // ---- startup ordering: recovery completes BEFORE link ops open again ------

    @Test
    void newLinkOperationsStayBlockedUntilStartupRecoveryFinished() throws Exception {
        final PlayerStore store = store();
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        links.replace(0, Map.of(XUID_A, JAVA_B));
        seedBedrockProfile(store, "A's own bookmark");
        final LinkTransactionJournal journal = new LinkTransactionJournal(playersDir());
        journal.write(new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.CLEANUP_PENDING));

        //Before recovery: link operations for the affected XUID are blocked.
        assertFalse(DefaultIdentityLinkService.linkAllowedNow(links, journal, XUID_A),
                "a pending recovery must block new link operations for the XUID");

        DefaultIdentityLinkService.recoverAtStartup(store, links, journal);

        //After recovery: the same XUID can link/unlink again.
        assertTrue(DefaultIdentityLinkService.linkAllowedNow(links, journal, XUID_A),
                "after the recovery finished, the XUID's link operations open again");
        assertFalse(bedrockFile().exists());
    }

    // ---- every persistence boundary fails → FAILED, A preserved ---------------

    @Test
    void aFailingBCredentialSaveAbortsBeforeTheCommitAndPreservesA() throws Exception {
        final FaultyStore store = new FaultyStore(playersDir());
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        final SessionLease leaseBeforeLink = session.lease;
        //B has a profile with saveLoginInfo=true so the service will attempt the
        //credential save before the index commit — which now fails.
        final PlayerData b = new PlayerData(ProfileKey.javaProfile(JAVA_B));
        b.bookmarks.add(new Bookmark("B's bookmark", "b.example", null, 3, 4));
        store.save(b);
        store.failSaves.set(true);

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.FAILED, result);
        assertEquals(0, links.snapshot().revision(),
                "the index must NOT be committed when the PREPARED-phase save failed");
        assertTrue(bedrockFile().exists(), "A's profile is preserved on a pre-commit failure");
        //IMPORTANT-review pin: the abort re-acquired A's bedrock usage — the
        //session ends with a CURRENT bedrock lease again, not stranded.
        assertNotNull(session.lease, "the session must not be stranded without usage");
        assertTrue(coordinator.isCurrent(session.lease),
                "after a pre-commit abort the session must hold a current bedrock lease again");
        assertFalse(coordinator.isCurrent(leaseBeforeLink),
                "the re-acquired lease is a fresh grant, not the superseded old one");
        assertEquals(ProfileKey.bedrockProfile(XUID_A), session.profileKey);
    }

    @Test
    void aFailingIndexCommitAbortsAndPreservesA() throws Exception {
        //The index commit failing = replace throwing; simulate with a corrupt
        //index (readSnapshot throws → replace cannot commit). The service must
        //answer FAILED and leave A's profile intact. NOTE: the corrupt index
        //trips the step-1 snapshot() before the commit call — the revision-
        //conflict variant below exercises replace() itself throwing.
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        Files.writeString(indexFile().toPath(), "{ this is not json", java.nio.charset.StandardCharsets.UTF_8);

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.FAILED, result, "an uncommittable index aborts the transaction");
        assertTrue(bedrockFile().exists(), "A's profile survives a failed commit");
    }

    /**
     * Round-2 review pin: replace() throwing a REAL revision conflict (a
     * concurrent link/unlink won the race) must be an ABORT — never a committed
     * ending. The loser returns FAILED with A fully preserved, the session is
     * NOT switched onto B (no finishSwitch, no B account), and the journal is
     * aborted/cleaned so the XUID is not blocked by a leftover entry.
     */
    @Test
    void aRevisionConflictAtTheIndexCommitAbortsAndNeverSwitchesTheSession() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        final SessionLease leaseBeforeLink = session.lease;
        //Simulate the concurrent winner: by the time the transaction commits,
        //the index moved on — the loser's expected revision no longer matches
        //and IdentityLinkStore.replace throws its explicit revision conflict.
        final IdentityLinkStore racedStore = new IdentityLinkStore(playersDir()) {
            @Override
            public void replace(final long expectedRevision, final Map<String, UUID> linksMap) throws IOException {
                //A concurrent commit advanced the index past this operation's
                //expected revision: replace must refuse exactly like production.
                throw new IOException("identity-links revision conflict: expected " + expectedRevision
                        + " but the committed revision is " + (expectedRevision + 1)
                        + "; refusing to overwrite the later change");
            }
        };

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, racedStore, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.FAILED, result,
                "a lost revision race is a pre-commit abort, never COMMITTED_CLEANUP_PENDING");
        //NO finishSwitch may have run: the session keeps its bedrock profile.
        assertEquals(ProfileKey.bedrockProfile(XUID_A), session.profileKey,
                "the session must not be switched onto B when the commit never landed");
        assertNull(session.account, "B's account must not be published on an aborted link");
        //A's file preserved; the index untouched by the loser.
        assertTrue(bedrockFile().exists(), "A's profile survives the lost race");
        assertEquals(0, links.snapshot().revision(), "the loser commits nothing");
        //The journal was aborted/cleaned: no leftover entry blocks the XUID.
        assertTrue(new LinkTransactionJournal(playersDir()).readAll().isEmpty(),
                "the aborted operation's journal must be cleaned");
        //And the abort re-acquired A's bedrock usage (same pre-commit contract).
        assertNotNull(session.lease);
        assertTrue(coordinator.isCurrent(session.lease),
                "the session holds a current bedrock lease again after the abort");
        assertFalse(coordinator.isCurrent(leaseBeforeLink),
                "the re-acquired lease is a fresh grant, not the superseded old one");
    }

    /**
     * Round-2 review pin: an unchecked exception escaping the COMMITTED ending
     * itself must still end in a committed outcome — never release/re-acquire/
     * FAILED after the commit (the outer catch re-classifies from the committed
     * index revision + actual mapping).
     */
    @Test
    void anUncheckedThrowInsideTheCommittedEndingStillEndsCommitted() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        seedBedrockProfile(store, "A's own bookmark");
        final PlayerSession session = bedrockSessionA(store, coordinator);
        //A store whose delete throws UNCHECKED (an Error-like RuntimeException,
        //not IOException): the step-5 region throws, the committed ending runs,
        //and even the second finishSwitch / journal write cannot reach an abort.
        final PlayerStore uncheckedFailing = new PlayerStore(playersDir()) {
            @Override
            public void delete(final ProfileKey key) {
                throw new IllegalStateException("disk gone (unchecked, injected)");
            }
        };

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(uncheckedFailing, links,
                coordinator, new TokenStore(this.tempDir), Runnable::run)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED_CLEANUP_PENDING, result,
                "an unchecked failure past the commit is still a committed ending");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "the binding stays committed");
        assertTrue(coordinator.isCurrent(session.lease), "B's usage stays live");
        assertEquals(ProfileKey.javaProfile(JAVA_B), session.profileKey, "the session stays on B");
    }

    // ---- task 7: UNLINK entries recover at startup like link entries ----------

    /**
     * Task 7 (§4.2): a crashed unlink whose index commit DID land (the mapping
     * is gone at or beyond the entry's target revision) recovers as an
     * operation whose cleanup is the blank-bedrock-profile creation: the
     * journal is cleaned, the binding stays removed, and the blank A profile
     * exists afterwards.
     */
    @Test
    void anUnlinkCommittedBeforeTheCrashRecoversIntoTheBlankProfileCreation() throws Exception {
        final PlayerStore store = store();
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        //The crash happened after the index commit: the mapping is already gone.
        links.replace(0, Map.of(XUID_A, JAVA_B));
        links.replace(1, Map.of());
        final LinkTransactionJournal journal = new LinkTransactionJournal(playersDir());
        journal.write(new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.UNLINK, XUID_A, JAVA_B, 1, 2,
                LinkTransactionJournal.Phase.COMMITTED));

        final DefaultIdentityLinkService.RecoveryOutcome outcome =
                DefaultIdentityLinkService.recoverAtStartup(store, links, journal);

        assertTrue(outcome.cleanedUp().contains(XUID_A), "the committed unlink recovery must continue the cleanup");
        assertNull(links.snapshot().javaUuidFor(XUID_A), "the unlink stays removed — never rolled back");
        assertTrue(bedrockFile().exists(),
                "the recovery completed the blank A bedrock profile creation");
        final PlayerData blank = store.load(ProfileKey.bedrockProfile(XUID_A));
        assertTrue(blank.bookmarks.isEmpty(), "the recovered profile is blank");
        assertNull(blank.accountBlob, "the recovered profile carries no credentials");
        assertTrue(journal.readAll().isEmpty(), "the finished recovery cleans the journal");
    }

    /**
     * Task 7 (§4.2): a crashed unlink whose index commit never landed (the
     * mapping still present, the revision below the target) recovers as
     * aborted: the association is kept and the journal is cleaned so the XUID
     * is not blocked.
     */
    @Test
    void anUnlinkThatNeverCommittedRecoversAsAbortedAndKeepsTheAssociation() throws Exception {
        final PlayerStore store = store();
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        links.replace(0, Map.of(XUID_A, JAVA_B));
        final LinkTransactionJournal journal = new LinkTransactionJournal(playersDir());
        journal.write(new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.UNLINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.PREPARED));

        final DefaultIdentityLinkService.RecoveryOutcome outcome =
                DefaultIdentityLinkService.recoverAtStartup(store, links, journal);

        assertTrue(outcome.aborted().contains(XUID_A), "the never-committed unlink must recover as aborted");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "the original association is kept");
        assertTrue(journal.readAll().isEmpty(), "the aborted unlink's journal must be cleaned");
        assertEquals(1, links.snapshot().revision(), "recovery must not rewrite the index");
    }

    /**
     * Task 7 (§4.2): a CLEANUP_PENDING unlink (blank-profile creation kept
     * failing at runtime) is also completed by the startup recovery, and while
     * it is pending the XUID is blocked from new link/unlink operations.
     */
    @Test
    void aCleanupPendingUnlinkIsCompletedAtStartupAndBlocksTheXuidUntilThen() throws Exception {
        final PlayerStore store = store();
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        links.replace(0, Map.of(XUID_A, JAVA_B));
        links.replace(1, Map.of());
        final LinkTransactionJournal journal = new LinkTransactionJournal(playersDir());
        journal.write(new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.UNLINK, XUID_A, JAVA_B, 1, 2,
                LinkTransactionJournal.Phase.CLEANUP_PENDING));

        assertFalse(DefaultIdentityLinkService.linkAllowedNow(links, journal, XUID_A),
                "a pending unlink recovery must block new link/unlink operations for the XUID");

        final DefaultIdentityLinkService.RecoveryOutcome outcome =
                DefaultIdentityLinkService.recoverAtStartup(store, links, journal);

        assertTrue(outcome.cleanedUp().contains(XUID_A));
        assertTrue(bedrockFile().exists(), "the recovery completed the blank A profile creation");
        assertTrue(journal.readAll().isEmpty());
        assertTrue(DefaultIdentityLinkService.linkAllowedNow(links, journal, XUID_A),
                "after the recovery finished, the XUID's link/unlink operations open again");
    }

    @Test
    void aSessionWithoutALeaseOrAnUnverifiedIdentityIsStale() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = new IdentityLinkStore(playersDir());
        final PlayerSession session = new PlayerSession(WIRE_A, "BedrockA");
        session.connectionId = UUID.randomUUID();
        session.c2pChannel = new EmbeddedChannel();
        //No lease, no profileKey, no identity: nothing verified to link from.
        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .link(session, accountFor(JAVA_B)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(IdentityLinkService.Result.STALE, result);
    }
}
