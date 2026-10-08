package dev.connectplus.access;

import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.LinkTransactionJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccessBindingCoordinatorTest {

    @Test
    void failedStartupReplayRestoresPendingKeysUntilReplayAndCleanupFinish() throws Exception {
        final AccessService service = this.service(false, true, (temp, target) -> {
            throw new IOException("replay disk failure");
        });
        final AccessBindingCoordinator coordinator = this.coordinator(service, this.linkStore());
        final AccessEntry inherited = new AccessEntry(AccessKey.ClientType.BEDROCK, "A", null, XUID_A);
        final LinkTransactionJournal.Entry entry = new LinkTransactionJournal.Entry("recovery-test",
                LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.ACCESS_PENDING, List.of(inherited));
        assertThrows(IOException.class, () -> coordinator.recoverCommittedLink(entry));
        assertTrue(service.state().pendingInheritance().contains(inherited.key()));
        assertEquals(AccessPolicy.Reason.LIST_UNAVAILABLE, AccessPolicy.evaluate(service.state(),
                new AccessSubject(AccessKey.ClientType.BEDROCK, "A", null, XUID_A)).reason());
    }

    private static final UUID JAVA_B = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private static final String XUID_A = "2533274790000001";
    private static final String XUID_OTHER = "2533274790000002";

    @TempDir
    Path dir;

    private Path whitelistFile() {
        return this.dir.resolve("whitelist.json");
    }

    private Path blacklistFile() {
        return this.dir.resolve("blacklist.json");
    }

    private AccessService service(final boolean whitelistOn, final boolean blacklistOn) throws IOException {
        return this.service(whitelistOn, blacklistOn, null);
    }

    private AccessService service(final boolean whitelistOn, final boolean blacklistOn,
                                  final AccessListStore.FileCommitter blacklistCommitter) throws IOException {
        Files.writeString(this.whitelistFile(), "{\"schemaVersion\": 1, \"players\": []}");
        Files.writeString(this.blacklistFile(), "{\"schemaVersion\": 1, \"players\": []}");
        // ONE service instance per test: two stores over the same file would fight
        // over the byte fingerprint (external-edit protection).
        final AccessListStore blacklist = blacklistCommitter == null
                ? new AccessListStore(this.blacklistFile())
                : new AccessListStore(this.blacklistFile(), blacklistCommitter);
        final AccessService service = new AccessService(new AccessListStore(this.whitelistFile()), blacklist,
                new AccessService.ConfiguredFlags(whitelistOn, blacklistOn), Runnable::run);
        service.initialize().toCompletableFuture().join();
        return service;
    }

    private IdentityLinkStore linkStore() {
        return new IdentityLinkStore(this.dir.resolve("players").toFile());
    }

    private AccessBindingCoordinator coordinator(final AccessService service, final IdentityLinkStore links) {
        return new AccessBindingCoordinator(service, new AccessMutationCoordinator(), () -> links, null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"WHITELIST,false", "WHITELIST,true", "BLACKLIST,false", "BLACKLIST,true"})
    void consoleAddPersistsHistoricalCounterpartNameAndRepairsOnlyMissingNames(
            AccessPolicy.Kind kind, boolean legacyNamelessEntry) throws Exception {
        final AccessService service = this.service(false, false);
        final IdentityLinkStore links = this.linkStore();
        links.replace(0, java.util.Map.of(XUID_A, JAVA_B));
        final var visits = new dev.connectplus.session.PlayerVisitStore(this.dir.resolve("visits.json").toFile());
        visits.recordKnownJavaName(JAVA_B, "JavaA");
        final UUID wire = UUID.randomUUID();
        visits.recordVisit(wire, "BedrockB", dev.connectplus.identity.ClientIdentity.verifiedBedrock(
                wire, "BedrockB", XUID_A, UUID.randomUUID(), "history-session"));
        if (legacyNamelessEntry) service.mutateNow(kind,
                List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, null, wire, XUID_A)), Set.of());
        final AccessTargetResolver resolver = new AccessTargetResolver(service, AccessConnectionRegistry::new, () -> visits);
        final AccessBindingCoordinator coordinator = new AccessBindingCoordinator(service,
                new AccessMutationCoordinator(), () -> links, resolver);
        coordinator.change(kind, true, resolver.resolve(AccessKey.ClientType.JAVA, "JavaA").get(0))
                .toCompletableFuture().join();
        final var state = kind == AccessPolicy.Kind.WHITELIST ? service.state().whitelist() : service.state().blacklist();
        assertEquals("BedrockB", state.entries().get(AccessKey.bedrockXuid(XUID_A)).name());
        assertEquals("JavaA", state.entries().get(AccessKey.javaUuid(JAVA_B)).name());
        if (legacyNamelessEntry) assertEquals(wire, state.entries().get(AccessKey.bedrockXuid(XUID_A)).uuid());
        final Path file = kind == AccessPolicy.Kind.WHITELIST ? this.whitelistFile() : this.blacklistFile();
        final var players = com.google.gson.JsonParser.parseString(Files.readString(file))
                .getAsJsonObject().getAsJsonArray("players");
        assertEquals(2, players.size());
        assertTrue(java.util.stream.StreamSupport.stream(players.spliterator(), false)
                .map(item -> item.getAsJsonObject()).anyMatch(item -> item.has("XUID")
                        && item.get("XUID").getAsString().equals(XUID_A)
                        && item.get("name").getAsString().equals("BedrockB")));
        coordinator.change(kind, true, new AccessEntry(AccessKey.ClientType.JAVA, "Replacement", JAVA_B, null))
                .toCompletableFuture().join();
        final var after = kind == AccessPolicy.Kind.WHITELIST ? service.state().whitelist() : service.state().blacklist();
        assertEquals("JavaA", after.entries().get(AccessKey.javaUuid(JAVA_B)).name());
    }

    @Test
    void changeAddsBothLinkedAccountsInOneCommit() throws Exception {
        final AccessService service = this.service(false, false);
        final IdentityLinkStore links = this.linkStore();
        links.replace(links.snapshot().revision(), java.util.Map.of(XUID_A, JAVA_B));
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);

        final AccessPolicy.State state = coordinator.change(AccessPolicy.Kind.BLACKLIST, true,
                new AccessEntry(AccessKey.ClientType.BEDROCK, "A", null, XUID_A))
                .toCompletableFuture().join();

        assertNotNull(state.blacklist().entries().get(AccessKey.bedrockXuid(XUID_A)), "the start entry");
        assertNotNull(state.blacklist().entries().get(AccessKey.javaUuid(JAVA_B)), "the linked counterpart");
        assertEquals(2, state.blacklist().entries().size());
        assertTrue(state.revision() > 1, "both accounts landed in one published change");
        assertTrue(Files.readString(this.blacklistFile()).contains(XUID_A));
        assertTrue(Files.readString(this.blacklistFile()).contains(JAVA_B.toString()));
    }

    @Test
    void changeRemovesBothLinkedAccountsEvenWhenTheStartHasNoRecord() throws Exception {
        final AccessService service = this.service(false, false);
        final IdentityLinkStore links = this.linkStore();
        links.replace(links.snapshot().revision(), java.util.Map.of(XUID_A, JAVA_B));
        service.mutateNow(AccessPolicy.Kind.BLACKLIST,
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "B", JAVA_B, null)), Set.of());
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);

        coordinator.change(AccessPolicy.Kind.BLACKLIST, false,
                new AccessEntry(AccessKey.ClientType.BEDROCK, "A", null, XUID_A))
                .toCompletableFuture().join();

        assertTrue(service.state().blacklist().entries().isEmpty(),
                "the counterpart is removed even though the start never had a record");
    }

    @Test
    void anUnlinkedAccountOperatesOnTheStartOnly() throws Exception {
        final AccessService service = this.service(false, false);
        final IdentityLinkStore links = this.linkStore();
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);

        coordinator.change(AccessPolicy.Kind.BLACKLIST, true,
                new AccessEntry(AccessKey.ClientType.JAVA, "B", JAVA_B, null)).toCompletableFuture().join();

        assertEquals(1, service.state().blacklist().entries().size());
        assertNotNull(service.state().blacklist().entries().get(AccessKey.javaUuid(JAVA_B)));
    }

    @Test
    void afterAnUnlinkOnlyTheCurrentRelationIsTouched() throws Exception {
        final AccessService service = this.service(false, false);
        final IdentityLinkStore links = this.linkStore();
        links.replace(links.snapshot().revision(), java.util.Map.of(XUID_OTHER, JAVA_B));
        service.mutateNow(AccessPolicy.Kind.BLACKLIST,
                List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, "A", null, XUID_A),
                        new AccessEntry(AccessKey.ClientType.JAVA, "B", JAVA_B, null)), Set.of());
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);

        coordinator.change(AccessPolicy.Kind.BLACKLIST, false,
                new AccessEntry(AccessKey.ClientType.BEDROCK, "A", null, XUID_A)).toCompletableFuture().join();

        assertNull(service.state().blacklist().entries().get(AccessKey.bedrockXuid(XUID_A)));
        assertNotNull(service.state().blacklist().entries().get(AccessKey.javaUuid(JAVA_B)),
                "unlink keeps records: the formerly linked account stays on the list");
    }

    @Test
    void aCorruptLinkIndexReportsInsteadOfGuessing() throws Exception {
        final AccessService service = this.service(false, false);
        service.mutateNow(AccessPolicy.Kind.BLACKLIST,
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "B", JAVA_B, null)), Set.of());
        this.dir.resolve("players").toFile().mkdirs();
        Files.writeString(this.dir.resolve("players").resolve("identity-links.json"), "{ broken");
        final AccessBindingCoordinator coordinator = this.coordinator(service, this.linkStore());

        assertThrows(Exception.class, () -> coordinator.change(AccessPolicy.Kind.BLACKLIST, true,
                new AccessEntry(AccessKey.ClientType.JAVA, "B", JAVA_B, null)).toCompletableFuture().join(),
                "an unreadable link index never silently degrades to start-only sync");
        assertEquals(1, service.state().blacklist().entries().size(), "the list is unchanged");
    }

    @Test
    void failedSyncLeavesBothListEntriesUnchanged() throws Exception {
        // Fail from the SECOND commit on: the seed write lands, the sync fails.
        final java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
        final AccessService service = this.service(false, false,
                (target, content) -> {
                    if (commits.incrementAndGet() > 1) {
                        throw new IOException("simulated commit failure");
                    }
                    AccessListStore.atomicCommitForTests(target, content);
                });
        final IdentityLinkStore links = this.linkStore();
        links.replace(links.snapshot().revision(), java.util.Map.of(XUID_A, JAVA_B));
        service.mutateNow(AccessPolicy.Kind.BLACKLIST,
                List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, "A", null, XUID_A)), Set.of());
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);

        assertThrows(Exception.class, () -> coordinator.change(AccessPolicy.Kind.BLACKLIST, true,
                new AccessEntry(AccessKey.ClientType.JAVA, "B", JAVA_B, null)).toCompletableFuture().join());

        assertEquals(1, service.state().blacklist().entries().size(),
                "a failed commit never publishes a half-applied sync");
        assertNotNull(service.state().blacklist().entries().get(AccessKey.bedrockXuid(XUID_A)));
    }

    @Test
    void commitLinkAppliesDeltaButPreservesJournalUntilOriginalCleanup() throws Exception {
        final AccessService service = this.service(false, false); // blacklist OFF: still saves
        final IdentityLinkStore links = this.linkStore();
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);
        final LinkTransactionJournal journal = new LinkTransactionJournal(this.dir.resolve("players").toFile());
        final List<AccessEntry> delta = List.of(
                new AccessEntry(AccessKey.ClientType.BEDROCK, "A", null, XUID_A),
                new AccessEntry(AccessKey.ClientType.JAVA, null, JAVA_B, null));
        final LinkTransactionJournal.Entry prepared = new LinkTransactionJournal.Entry(
                UUID.randomUUID().toString(), LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.PREPARED, delta);
        journal.write(prepared);

        final AccessBindingCoordinator.LinkCommitResult outcome = coordinator.withCommitLock(() ->
                coordinator.commitLink(journal, prepared, () -> {
                    links.replace(links.snapshot().revision(), java.util.Map.of(XUID_A, JAVA_B));
                    return null;
                }));

        assertEquals(AccessBindingCoordinator.LinkCommitResult.COMPLETE, outcome);
        assertNotNull(journal.read(prepared.operationId()), "profile cleanup still needs its recovery intent");
        assertEquals(2, service.state().blacklist().entries().size(),
                "the inheritance applies even with the blacklist switch off");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A));
    }

    @Test
    void emptyInheritanceNeverClearsTheCommittedTransactionsJournal() throws Exception {
        final AccessService service = this.service(false, false);
        final IdentityLinkStore links = this.linkStore();
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);
        final LinkTransactionJournal journal = new LinkTransactionJournal(this.dir.resolve("players").toFile()) {
            @Override public void clear(String operationId) throws IOException {
                throw new IOException("cleanup deletion failed");
            }
        };
        final LinkTransactionJournal.Entry prepared = new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.PREPARED, List.of());
        journal.write(prepared);
        assertEquals(AccessBindingCoordinator.LinkCommitResult.COMPLETE,
                coordinator.withCommitLock(() -> coordinator.commitLink(journal, prepared, () -> {
                    links.replace(0, java.util.Map.of(XUID_A, JAVA_B));
                    return null;
                })));
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A));
        assertNotNull(journal.read(prepared.operationId()));
    }

    @Test
    void unavailableBlacklistCannotBeTreatedAsNoInheritance() throws Exception {
        final AccessService service = this.service(false, false);
        Files.writeString(this.blacklistFile(), "{broken");
        final AccessService restarted = new AccessService(new AccessListStore(this.whitelistFile()),
                new AccessListStore(this.blacklistFile()), new AccessService.ConfiguredFlags(false, false), Runnable::run);
        restarted.initialize().toCompletableFuture().join();
        assertThrows(IOException.class, () -> this.coordinator(restarted, this.linkStore())
                .blacklistInheritanceDelta(XUID_A, JAVA_B));
    }

    @Test
    void pendingJournalRuntimeFailureCannotAbortACommittedBinding() throws Exception {
        final AccessService service = this.service(false, false, (temp, target) -> {
            throw new IOException("blacklist write failed");
        });
        final IdentityLinkStore links = this.linkStore();
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);
        final LinkTransactionJournal journal = new LinkTransactionJournal(this.dir.resolve("players").toFile()) {
            @Override public void write(Entry entry) throws IOException {
                if (entry.phase() == Phase.ACCESS_PENDING) throw new IllegalStateException("journal writer failed");
                super.write(entry);
            }
        };
        final LinkTransactionJournal.Entry prepared = new LinkTransactionJournal.Entry(UUID.randomUUID().toString(),
                LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.PREPARED,
                List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, "A", null, XUID_A)));
        journal.write(prepared);
        assertEquals(AccessBindingCoordinator.LinkCommitResult.ACCESS_PENDING,
                coordinator.withCommitLock(() -> coordinator.commitLink(journal, prepared, () -> {
                    links.replace(0, java.util.Map.of(XUID_A, JAVA_B));
                    return null;
                })));
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A));
        assertEquals(prepared.blacklistAdds(), journal.read(prepared.operationId()).blacklistAdds());
        assertTrue(service.state().pendingInheritance().contains(AccessKey.bedrockXuid(XUID_A)));
    }

    @Test
    void reloadWaitsForTheSameCommitLockAsBindingInheritance() throws Exception {
        final var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        final var release = new java.util.concurrent.CountDownLatch(1);
        try {
            final AccessService service = new AccessService(new AccessListStore(this.whitelistFile()),
                    new AccessListStore(this.blacklistFile()),
                    new AccessService.ConfiguredFlags(false, false), workers);
            service.initialize().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);
            final AccessBindingCoordinator coordinator = this.coordinator(service, this.linkStore());
            final var held = new java.util.concurrent.CountDownLatch(1);
            final var binding = workers.submit(() -> coordinator.withCommitLock(() -> {
                held.countDown();
                release.await(3, java.util.concurrent.TimeUnit.SECONDS);
                return null;
            }));
            assertTrue(held.await(3, java.util.concurrent.TimeUnit.SECONDS));
            final var reload = service.reload(AccessPolicy.Kind.BLACKLIST).toCompletableFuture();
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> reload.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            binding.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(reload.get(3, java.util.concurrent.TimeUnit.SECONDS).blacklist().available());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void commitLinkWithAFailedBlacklistWriteReportsAccessPending() throws Exception {
        final AccessService service = this.service(false, false,
                (target, content) -> { throw new IOException("simulated blacklist write failure"); });
        final IdentityLinkStore links = this.linkStore();
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);
        final LinkTransactionJournal journal = new LinkTransactionJournal(this.dir.resolve("players").toFile());
        final List<AccessEntry> delta = List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, null, null, XUID_A));
        final LinkTransactionJournal.Entry prepared = new LinkTransactionJournal.Entry(
                UUID.randomUUID().toString(), LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.PREPARED, delta);
        journal.write(prepared);

        final AccessBindingCoordinator.LinkCommitResult outcome = coordinator.withCommitLock(() ->
                coordinator.commitLink(journal, prepared, () -> {
                    links.replace(links.snapshot().revision(), java.util.Map.of(XUID_A, JAVA_B));
                    return null;
                }));

        assertEquals(AccessBindingCoordinator.LinkCommitResult.ACCESS_PENDING, outcome);
        assertEquals(LinkTransactionJournal.Phase.ACCESS_PENDING, journal.read(prepared.operationId()).phase(),
                "the committed binding keeps its pending delta in the journal");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "the binding stays committed");
        assertTrue(service.state().pendingInheritance().contains(AccessKey.bedrockXuid(XUID_A)));
    }

    @Test
    void recoveryReplaysOnlyTheJournalDeltaAndIsIdempotent() throws Exception {
        final AccessService service = this.service(false, false);
        final IdentityLinkStore links = this.linkStore();
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);
        service.mutateNow(AccessPolicy.Kind.BLACKLIST,
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "OperatorName", JAVA_B, null)), Set.of());
        final LinkTransactionJournal.Entry committed = new LinkTransactionJournal.Entry(
                UUID.randomUUID().toString(), LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.ACCESS_PENDING,
                List.of(new AccessEntry(AccessKey.ClientType.JAVA, "JournalName", JAVA_B, null),
                        new AccessEntry(AccessKey.ClientType.BEDROCK, null, null, XUID_A)));

        coordinator.recoverCommittedLink(committed);
        coordinator.recoverCommittedLink(committed);

        final AccessEntry java = service.state().blacklist().entries().get(AccessKey.javaUuid(JAVA_B));
        assertEquals("OperatorName", java.name(), "an existing entry is never overwritten by the replay");
        assertNotNull(service.state().blacklist().entries().get(AccessKey.bedrockXuid(XUID_A)));
    }

    @Test
    void recoveryCompletionClearsThePendingKeys() throws Exception {
        final AccessService service = this.service(false, false);
        final IdentityLinkStore links = this.linkStore();
        final AccessBindingCoordinator coordinator = this.coordinator(service, links);
        final LinkTransactionJournal.Entry committed = new LinkTransactionJournal.Entry(
                UUID.randomUUID().toString(), LinkTransactionJournal.Operation.LINK, XUID_A, JAVA_B, 0, 1,
                LinkTransactionJournal.Phase.ACCESS_PENDING,
                List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, null, null, XUID_A)));
        coordinator.recoverCommittedLink(committed);

        coordinator.pendingRecovered(Set.of(AccessKey.bedrockXuid(XUID_A), AccessKey.javaUuid(JAVA_B)));

        assertTrue(service.state().pendingInheritance().isEmpty(),
                "after the recovery completed the pending state is gone");
    }
}
