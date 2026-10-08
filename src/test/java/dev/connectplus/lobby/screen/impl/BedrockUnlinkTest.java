package dev.connectplus.lobby.screen.impl;

import com.viaversion.viaversion.api.minecraft.item.Item;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.identity.ClientIdentity;
import dev.connectplus.identity.DefaultIdentityLinkService;
import dev.connectplus.identity.IdentityLinkService;
import dev.connectplus.identity.IdentityLinkStore;
import dev.connectplus.identity.LinkTransactionJournal;
import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.screen.ItemList;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.lobby.states.StateHandler;
import dev.connectplus.session.AccountSessionCoordinator;
import dev.connectplus.session.Bookmark;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionLease;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import dev.connectplus.testutil.StubAccount;
import io.netty.channel.embedded.EmbeddedChannel;
import net.lenni0451.mcstructs.text.TextComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 7: the bedrock-only unlink GUI (§1.3) and the §4.2 unlink transaction.
 * Pinned rules: only a linked trusted bedrock player sees the option; the
 * unlink removes the mapping and gives A a blank profile while B's bookmarks,
 * settings and stored login survive byte-identically and A keeps its c2p
 * connection; a pre-commit failure keeps the association; a post-commit blank
 * -profile failure stays unlinked (restricted blank state, bounded retry,
 * startup recovery completes it); a stale unlink after a displacement never
 * commits and never releases the later holder's lease (Review Focus R5); and
 * repeat GUI clicks while the transaction is in flight are ignored.
 */
@org.junit.jupiter.api.extension.ExtendWith(dev.connectplus.testutil.AccountPolicyExtension.class)
class BedrockUnlinkTest {

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

    private IdentityLinkStore links() {
        return new IdentityLinkStore(playersDir());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"QUEUED", "SAVING", "PROVIDER_STOPPING"})
    void revokedAccountPermissionCannotCommitAnUnlink(String revocation) throws Exception {
        AtomicBoolean saving = new AtomicBoolean();
        PlayerStore store = new PlayerStore(playersDir()) {
            @Override public void save(ProfileKey key, PlayerData data) throws IOException {
                super.save(key, data);
                if (saving.get() && key.equals(ProfileKey.javaProfile(JAVA_B))) {
                    dev.connectplus.config.CPConfig.allowAccountLogin = false;
                }
            }
        };
        AccountSessionCoordinator coordinator = coordinator(store);
        PlayerSession session = null;
        try {
            seedB(store);
            IdentityLinkStore links = links();
            session = linkedBedrockSession(store, coordinator, links);
            java.util.List<Runnable> pending = new java.util.ArrayList<>();
            IdentityLinkService service = new DefaultIdentityLinkService(store, links, coordinator,
                    new TokenStore(tempDir), pending::add);
            var result = service.unlink(session).toCompletableFuture();
            if (revocation.equals("SAVING")) saving.set(true);
            else if (revocation.equals("QUEUED")) dev.connectplus.config.CPConfig.allowAccountLogin = false;
            else dev.connectplus.testutil.TestClientIdentity.bedrock(session.c2pChannel,
                    session.c2pChannel.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).get()).stop();
            pending.remove(0).run();
            assertNotEquals(IdentityLinkService.Result.COMMITTED, result.get());
            assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A));
            assertEquals("encrypted-blob-of-B", store.load(ProfileKey.javaProfile(JAVA_B)).accountBlob);
            assertEquals(ProfileKey.javaProfile(JAVA_B), session.profileKey);
        } finally {
            if (session != null) session.c2pChannel.close();
            coordinator.shutdown();
        }
    }

    @Test
    void anAlreadyOpenUnlinkButtonCannotStartAfterTheAdminDisablesAccounts() throws Exception {
        PlayerStore store = store();
        AccountSessionCoordinator coordinator = coordinator(store);
        PlayerSession session = null;
        EmbeddedChannel lobby = new EmbeddedChannel();
        try {
            seedB(store);
            IdentityLinkStore links = links();
            session = linkedBedrockSession(store, coordinator, links);
            session.lobbyChannel = lobby;
            LobbyServerHandler handler = guiHandler(session, store, links, coordinator, service(store, coordinator, links));
            ScreenHandler screen = new ScreenHandler(new StateHandler(handler, lobby));
            ItemList items = new ItemList(36);
            new MainScreen(Lang.EN).init(screen, items);
            assertNotNull(items.getListeners()[15]);
            dev.connectplus.config.CPConfig.allowAccountLogin = false;
            items.getListeners()[15].onClick();
            assertFalse(session.loginInProgress);
            assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A));
        } finally {
            lobby.finishAndReleaseAll();
            if (session != null) session.c2pChannel.close();
            coordinator.shutdown();
        }
    }

    private IdentityLinkService service(final PlayerStore store, final AccountSessionCoordinator coordinator,
                                        final IdentityLinkStore links) {
        return new DefaultIdentityLinkService(store, links, coordinator, new TokenStore(this.tempDir), Runnable::run);
    }

    private LinkTransactionJournal journal() {
        return new LinkTransactionJournal(playersDir());
    }

    private File bedrockFileA() {
        return new File(new File(playersDir(), "bedrock"), XUID_A + ".json");
    }

    private File javaFileB() {
        return new File(new File(playersDir(), "java"), JAVA_B + ".json");
    }

    /** B's profile: bookmarks, stored login, both settings flags set. */
    private void seedB(final PlayerStore store) throws IOException {
        final PlayerData b = new PlayerData(ProfileKey.javaProfile(JAVA_B));
        b.bookmarks.add(new Bookmark("B's bookmark", "b.example", "1.21", 30, 40));
        b.bookmarks.add(new Bookmark("B's second", "b2.example", null, 50, 60));
        b.accountBlob = "encrypted-blob-of-B";
        b.saveLoginInfo = true;
        b.offlineMode = true;
        store.save(b);
    }

    /**
     * A linked trusted bedrock session: the verified bedrock identity runs the
     * linked JAVA profile of B under a current coordinator lease.
     */
    private PlayerSession linkedBedrockSession(final PlayerStore store, final AccountSessionCoordinator coordinator,
                                               final IdentityLinkStore links) throws Exception {
        links.replace(0, Map.of(XUID_A, JAVA_B));
        final PlayerSession session = new PlayerSession(WIRE_A, "BedrockA");
        session.connectionId = UUID.randomUUID();
        final EmbeddedChannel c2p = new EmbeddedChannel();
        dev.connectplus.testutil.TestClientIdentity.bedrock(c2p,
                    ClientIdentity.verifiedBedrock(WIRE_A, "BedrockA", XUID_A, UUID.randomUUID(), UUID.randomUUID().toString()));
        session.c2pChannel = c2p;
        session.lease = coordinator.claim(session, ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        session.generation = session.lease.generation();
        session.profileKey = ProfileKey.javaProfile(JAVA_B);
        session.playerData = store.load(ProfileKey.javaProfile(JAVA_B));
        session.account = new StubAccount();
        return session;
    }

    /** A verified Java player (owns its profile outright; never sees the option). */
    private PlayerSession javaSession(final PlayerStore store, final AccountSessionCoordinator coordinator) throws Exception {
        final PlayerSession session = new PlayerSession(UUID.randomUUID(), "JavaB");
        session.connectionId = UUID.randomUUID();
        final EmbeddedChannel c2p = new EmbeddedChannel();
        c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).set(
                ClientIdentity.verifiedJava(session.uuid, "JavaB", JAVA_B));
        session.c2pChannel = c2p;
        session.lease = coordinator.claim(session, ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        session.generation = session.lease.generation();
        session.profileKey = ProfileKey.javaProfile(JAVA_B);
        session.playerData = store.load(ProfileKey.javaProfile(JAVA_B));
        return session;
    }

    /** A verified but unlinked bedrock player (runs its own BEDROCK profile). */
    private PlayerSession unlinkedBedrockSession(final PlayerStore store, final AccountSessionCoordinator coordinator) throws Exception {
        final PlayerSession session = new PlayerSession(WIRE_A, "BedrockA");
        session.connectionId = UUID.randomUUID();
        final EmbeddedChannel c2p = new EmbeddedChannel();
        dev.connectplus.testutil.TestClientIdentity.bedrock(c2p,
                    ClientIdentity.verifiedBedrock(WIRE_A, "BedrockA", XUID_A, UUID.randomUUID(), UUID.randomUUID().toString()));
        session.c2pChannel = c2p;
        session.lease = coordinator.claim(session, ProfileKey.bedrockProfile(XUID_A), Set.of())
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        session.generation = session.lease.generation();
        session.profileKey = ProfileKey.bedrockProfile(XUID_A);
        session.playerData = store.load(ProfileKey.bedrockProfile(XUID_A));
        return session;
    }

    /** A LobbyServerHandler whose session is the hand-built one (no join rig needed). */
    private LobbyServerHandler guiHandler(final PlayerSession session, final PlayerStore store,
                                          final IdentityLinkStore links, final AccountSessionCoordinator coordinator,
                                          final IdentityLinkService linkService) {
        return new LobbyServerHandler(Set.of(), new SessionRegistry(), new TokenStore(this.tempDir), store,
                links, coordinator, new RecordingSwitchInitiator(),
                null,
                new AtomicInteger(), linkService) {
            @Override
            public PlayerSession getSession() {
                return session;
            }
        };
    }

    /** Renders the main screen's item list for the handler's session. */
    private ItemList mainScreenItems(final LobbyServerHandler handler) {
        final EmbeddedChannel lobby = new EmbeddedChannel();
        final ScreenHandler screenHandler = new ScreenHandler(new StateHandler(handler, lobby));
        final ItemList list = new ItemList(36);
        new MainScreen(Lang.EN).init(screenHandler, list);
        return list;
    }

    /** Polls {@code condition}, pumping the embedded channel between tries. */
    private static void until(final java.util.function.BooleanSupplier condition, final Runnable pump) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("condition not reached in time");
            }
            Thread.sleep(20);
            pump.run();
        }
    }

    // ---- §1.3: the button appears only for a linked trusted bedrock player ----

    @Test
    void theUnlinkButtonAppearsOnlyForALinkedTrustedBedrockPlayer() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final IdentityLinkService service = service(store, coordinator, links);

        final PlayerSession linked = linkedBedrockSession(store, coordinator, links);
        final Item unlinkSlot = mainScreenItems(guiHandler(linked, store, links, coordinator, service)).getItems()[15];
        assertNotNull(unlinkSlot, "a linked trusted bedrock player must see the unlink button");
        assertTrue(!unlinkSlot.isEmpty(), "the unlink slot must hold a real item");

        final PlayerSession java = javaSession(store, coordinator);
        assertTrue(mainScreenItems(guiHandler(java, store, links, coordinator, service)).getItems()[15].isEmpty(),
                "a Java player must not see the unlink option");

        final PlayerSession unlinked = unlinkedBedrockSession(store, coordinator);
        assertTrue(mainScreenItems(guiHandler(unlinked, store, links, coordinator, service)).getItems()[15].isEmpty(),
                "an unlinked bedrock player must not see the unlink option");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void revokedPermissionHidesTheUnlinkButtonAndItsJavaAccountName(boolean stoppedProvider) throws Exception {
        PlayerStore store = store();
        AccountSessionCoordinator coordinator = coordinator(store);
        PlayerSession session = null;
        try {
            seedB(store);
            IdentityLinkStore links = links();
            session = linkedBedrockSession(store, coordinator, links);
            var handler = guiHandler(session, store, links, coordinator, service(store, coordinator, links));
            assertFalse(mainScreenItems(handler).getItems()[15].isEmpty());
            if (stoppedProvider) dev.connectplus.testutil.TestClientIdentity.bedrock(session.c2pChannel,
                    session.c2pChannel.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).get()).stop();
            else dev.connectplus.config.CPConfig.allowAccountLogin = false;
            assertTrue(mainScreenItems(handler).getItems()[15].isEmpty());
        } finally {
            if (session != null) session.c2pChannel.close();
            coordinator.shutdown();
        }
    }

    /** §1.3 (binding): the button and description texts, verbatim in Chinese. */
    @Test
    void theUnlinkButtonTextsFollowTheSpec() {
        assertEquals("§c解除与 Java 账号 {0} 的绑定",
                Languages.text("zh", Messages.MainScreen.Unlink.ItemName),
                "the §1.3 button text must be the specified one");
        assertTrue(Languages.text("zh", Messages.MainScreen.Unlink.ItemLore)
                        .contains("解除后将使用基岩版独立档案，Java 账号的书签和登录信息会保留。"),
                "the §1.3 description must be verbatim");

        final TextComponent title = AccountFlow.unlinkButtonTitle(Lang.EN, new StubAccount(), ProfileKey.javaProfile(JAVA_B));
        assertTrue(title.asUnformattedString().contains("Unlink from Java account PrivateAccount"),
                "the button carries B's safely processed display name, got: " + title.asUnformattedString());
        final TextComponent zhTitle = AccountFlow.unlinkButtonTitle(Lang.ZH, new StubAccount(), ProfileKey.javaProfile(JAVA_B));
        assertTrue(zhTitle.asUnformattedString().contains("解除与 Java 账号 PrivateAccount 的绑定"),
                "the zh button formats the name in, got: " + zhTitle.asUnformattedString());
    }

    /** B's display name is sanitized before it reaches any GUI/chat component. */
    @Test
    void theDisplayedJavaNameIsSafelyProcessed() {
        final StubAccount hostile = new StubAccount() {
            @Override
            public String displayName() {
                return "§a§lEvil§r Name\u0007" + "x".repeat(60);
            }
        };
        final String cleaned = AccountFlow.safeDisplayName(hostile, "fallback");
        assertFalse(cleaned.contains("§"), "legacy formatting codes must be stripped: " + cleaned);
        assertFalse(cleaned.matches(".*[\\x00-\\x1f\\x7f].*"), "control characters must be stripped: " + cleaned);
        assertTrue(cleaned.length() <= 32, "the name must be length-capped, got " + cleaned.length());
        assertTrue(cleaned.startsWith("Evil"), "the visible text must survive: " + cleaned);
        assertEquals("fallback", AccountFlow.safeDisplayName(null, "fallback"));
        assertEquals("fallback", AccountFlow.safeDisplayName(new StubAccount() {
            @Override
            public String displayName() {
                return "§§§";
            }
        }, "fallback"));
    }

    // ---- the §4.2 transaction: happy path --------------------------------------

    @Test
    void unlinkRemovesTheMappingGivesABlankProfileAndKeepsBAndTheConnectionIntact() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession session = linkedBedrockSession(store, coordinator, links);
        final String bBefore = Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8);
        final SessionLease leaseBeforeUnlink = session.lease;

        final IdentityLinkService.Result result = service(store, coordinator, links)
                .unlink(session).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED, result);
        //the mapping is gone
        assertNull(links.snapshot().javaUuidFor(XUID_A), "the unlink must remove the index mapping");
        assertTrue(bedrockFileA().exists(), "a blank A profile file must exist after the unlink");
        final PlayerData blank = store.load(ProfileKey.bedrockProfile(XUID_A));
        assertTrue(blank.bookmarks.isEmpty(), "the new A profile is blank (no bookmark restore, no copy from B)");
        assertNull(blank.accountBlob, "the blank A profile carries no credentials");
        //B survives byte-identically (bookmarks, settings, accountBlob)
        assertEquals(bBefore, Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8),
                "B's bookmarks/settings/accountBlob must survive byte-identically");
        //the session ends on the blank bedrock profile without B's credentials
        assertEquals(ProfileKey.bedrockProfile(XUID_A), session.profileKey);
        assertEquals(ProfileKey.bedrockProfile(XUID_A), session.playerData.key);
        assertNull(session.account, "the in-memory B credentials must be cleared");
        //A keeps its c2p connection
        assertNotNull(session.c2pChannel);
        assertTrue(session.c2pChannel.isActive(), "the unlink must keep the current c2p connection");
        //the session ends with a current lease for the blank bedrock profile
        assertNotNull(session.lease);
        assertTrue(coordinator.isCurrent(session.lease), "the session must hold the new bedrock lease");
        assertFalse(coordinator.isCurrent(leaseBeforeUnlink), "the old B lease must be gone");
        assertEquals(session.lease.generation(), session.generation);
        assertFalse(session.loginInProgress,
                "the service-level transaction leaves no in-flight flag behind (the GUI flow clears its own flag on completion)");
        assertTrue(journal().readAll().isEmpty(), "a completed unlink leaves no journal entry");
    }

    // ---- pre-commit failure: the association is kept ---------------------------

    @Test
    void aFailedIndexCommitKeepsTheAssociationAndBProfile() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession session = linkedBedrockSession(store, coordinator, links);
        final String bBefore = Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8);
        final IdentityLinkStore racedStore = new IdentityLinkStore(playersDir()) {
            @Override
            public void replace(final long expectedRevision, final Map<String, UUID> linksMap) throws IOException {
                throw new IOException("identity-links revision conflict (injected)");
            }
        };

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, racedStore, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .unlink(session).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.FAILED, result, "a failed index commit must not unlink");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "the original association must be kept");
        assertEquals(bBefore, Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8),
                "B's profile must not be cleared on a pre-commit failure");
        assertFalse(bedrockFileA().exists(), "no blank A file may appear before the commit");
        //no GUI state change: the session still runs B with its account
        assertEquals(ProfileKey.javaProfile(JAVA_B), session.profileKey);
        assertNotNull(session.account, "a pre-commit failure must not clear B's credentials");
        //the session re-acquired B's usage after the abort
        assertNotNull(session.lease);
        assertTrue(coordinator.isCurrent(session.lease), "the session holds a current B lease again");
        assertTrue(journal().readAll().isEmpty(), "the aborted operation's journal must be cleaned");
    }

    // ---- post-commit failure: restricted blank state, retry, recovery ----------

    @Test
    void aPostCommitBlankProfileFailureStaysUnlinkedAndRecoveryCompletesTheBlankProfile() throws Exception {
        final PlayerStore store = new PlayerStore(playersDir()) {
            final AtomicInteger bedrockSaves = new AtomicInteger();

            @Override
            public void save(final ProfileKey key, final PlayerData data) throws IOException {
                if (key.kind() == ProfileKey.Kind.BEDROCK && this.bedrockSaves.incrementAndGet() > 0) {
                    throw new IOException("bedrock save failed (injected, every attempt)");
                }
                super.save(key, data);
            }
        };
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession session = linkedBedrockSession(store, coordinator, links);
        final String bBefore = Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8);

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .unlink(session).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED_CLEANUP_PENDING, result,
                "a post-commit blank-profile failure is a committed ending, never a rollback");
        assertNull(links.snapshot().javaUuidFor(XUID_A), "the binding stays removed");
        assertFalse(bedrockFileA().exists(), "the blank file creation failed (injected)");
        //never roll back to linked; never allow B's credentials again
        assertEquals(ProfileKey.bedrockProfile(XUID_A), session.profileKey);
        assertNull(session.account);
        //the lobby stays in the restricted blank state
        assertEquals(ProfileKey.bedrockProfile(XUID_A), session.playerData.key);
        assertTrue(session.playerData.bookmarks.isEmpty());
        assertTrue(coordinator.isCurrent(session.lease), "the session keeps its right of use on the blank profile");
        assertEquals(bBefore, Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8), "B stays intact");
        //the journal records the pending cleanup for the startup recovery
        assertEquals(1, journal().readAll().size());
        assertEquals(LinkTransactionJournal.Operation.UNLINK,
                journal().readAll().values().iterator().next().operation());
        assertFalse(DefaultIdentityLinkService.linkAllowedNow(links, journal(), XUID_A),
                "a pending unlink recovery blocks new link/unlink operations for the XUID");

        //the restart recovery completes the blank bedrock profile creation
        final DefaultIdentityLinkService.RecoveryOutcome outcome =
                DefaultIdentityLinkService.recoverAtStartup(store(), links, journal());
        assertTrue(outcome.cleanedUp().contains(XUID_A), "the recovery must finish the blank profile creation");
        assertTrue(bedrockFileA().exists(), "the recovery created the blank A profile");
        assertTrue(journal().readAll().isEmpty(), "the finished recovery cleans the journal");
        assertTrue(DefaultIdentityLinkService.linkAllowedNow(links, journal(), XUID_A));
    }

    @Test
    void theBlankProfileSaveIsRetriedOnceBeforeCleanupPending() throws Exception {
        final PlayerStore store = new PlayerStore(playersDir()) {
            final AtomicInteger bedrockSaves = new AtomicInteger();

            @Override
            public void save(final ProfileKey key, final PlayerData data) throws IOException {
                if (key.kind() == ProfileKey.Kind.BEDROCK && this.bedrockSaves.incrementAndGet() == 1) {
                    throw new IOException("bedrock save failed once (injected)");
                }
                super.save(key, data);
            }
        };
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession session = linkedBedrockSession(store, coordinator, links);

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .unlink(session).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.COMMITTED, result,
                "the bounded retry of the blank save must succeed (§4.2 重试保存)");
        assertTrue(bedrockFileA().exists(), "the retry created the blank A profile");
        assertTrue(journal().readAll().isEmpty());
    }

    // ---- R5: staleness, serialization, repeat clicks ---------------------------

    @Test
    void aStaleUnlinkAfterDisplacementNeverCommitsNorReleasesTheLaterHoldersLease() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession sessionA = linkedBedrockSession(store, coordinator, links);
        final String bBefore = Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8);

        //B's other new login arrives first: it displaces A through the same
        //serial coordinator (freeze → save → invalidate → evict → grant)
        final PlayerSession laterB = new PlayerSession(UUID.randomUUID(), "JavaB-Later");
        laterB.connectionId = UUID.randomUUID();
        laterB.c2pChannel = new EmbeddedChannel();
        final SessionLease laterLease = coordinator.claim(laterB, ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(sessionA.displaced, "the later login must have displaced the old holder");
        assertFalse(coordinator.isCurrent(sessionA.lease), "the displaced lease must be invalid");

        final IdentityLinkService.Result result = service(store, coordinator, links)
                .unlink(sessionA).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.STALE, result, "a displaced session's unlink must not commit");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "the binding must be untouched");
        assertEquals(bBefore, Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8), "B stays intact");
        //R5: the later holder's lease must survive the stale unlink
        assertTrue(coordinator.isCurrent(laterLease), "the stale unlink must not release the later holder's lease");
        assertTrue(journal().readAll().isEmpty(), "the stale unlink leaves no journal entry");
    }

    @Test
    void afterACommittedUnlinkTheLaterJavaLoginCanClaimB() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession sessionA = linkedBedrockSession(store, coordinator, links);

        assertEquals(IdentityLinkService.Result.COMMITTED, service(store, coordinator, links)
                .unlink(sessionA).toCompletableFuture().get(10, TimeUnit.SECONDS));

        //B's resources are free again: a new verified B login claims them without
        //having to displace anyone
        final PlayerSession newB = new PlayerSession(UUID.randomUUID(), "JavaB-New");
        newB.connectionId = UUID.randomUUID();
        newB.c2pChannel = new EmbeddedChannel();
        final SessionLease newLease = coordinator.claim(newB, ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(coordinator.isCurrent(newLease));
        assertFalse(newB.displaced, "the freed account must not force a displacement");
    }

    @Test
    void aSecondGuiClickWhileTheTransactionIsInFlightIsIgnored() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession session = linkedBedrockSession(store, coordinator, links);
        session.lobbyChannel = new EmbeddedChannel();
        final CompletableFuture<IdentityLinkService.Result> pending = new CompletableFuture<>();
        final AtomicInteger calls = new AtomicInteger();
        final IdentityLinkService stub = new IdentityLinkService() {
            @Override
            public CompletionStage<IdentityLinkService.Result> link(final PlayerSession s, final dev.connectplus.accounts.CPAccount a) {
                return CompletableFuture.completedFuture(IdentityLinkService.Result.FAILED);
            }

            @Override
            public CompletionStage<IdentityLinkService.Result> unlink(final PlayerSession s) {
                calls.incrementAndGet();
                return pending;
            }
        };
        final LobbyServerHandler handler = guiHandler(session, store, links, coordinator, stub);
        final EmbeddedChannel lobby = (EmbeddedChannel) session.lobbyChannel;
        final ScreenHandler screenHandler = new ScreenHandler(new StateHandler(handler, lobby));
        final ItemList list = new ItemList(36);
        new MainScreen(Lang.EN).init(screenHandler, list);

        list.getListeners()[15].onClick();
        until(() -> calls.get() == 1, lobby::runPendingTasks);
        assertTrue(session.loginInProgress, "the transaction must mark the session in flight");
        //the repeat click while the transaction is in flight: ignored, no double transaction
        list.getListeners()[15].onClick();
        assertEquals(1, calls.get(), "the second click must not start a second transaction");
        list.getListeners()[15].onClick();
        assertEquals(1, calls.get());

        pending.complete(IdentityLinkService.Result.COMMITTED);
        until(() -> !session.loginInProgress, lobby::runPendingTasks);
        assertTrue(screenHandler.getCurrentScreen() instanceof MainScreen,
                "the lobby GUI must be refreshed after the committed unlink");
    }

    @Test
    void theUnlinkRotatesLeasesWithoutLeakingTheOldBUsage() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession session = linkedBedrockSession(store, coordinator, links);
        session.lobbyChannel = new EmbeddedChannel();
        final LobbyServerHandler handler = guiHandler(session, store, links, coordinator, service(store, coordinator, links));
        final EmbeddedChannel lobby = (EmbeddedChannel) session.lobbyChannel;
        final ScreenHandler screenHandler = new ScreenHandler(new StateHandler(handler, lobby));
        final ItemList list = new ItemList(36);
        new MainScreen(Lang.EN).init(screenHandler, list);

        final SessionLease oldBLease = session.lease;
        list.getListeners()[15].onClick();
        until(() -> session.profileKey != null && session.profileKey.kind() == ProfileKey.Kind.BEDROCK
                && !session.loginInProgress, lobby::runPendingTasks);
        //the unlink swapped the leases: the new bedrock lease is current, the old
        //B lease is gone — nobody may still hold B's account through the old lease
        assertTrue(coordinator.isCurrent(session.lease), "the unlink granted a fresh bedrock lease");
        assertFalse(coordinator.isCurrent(oldBLease), "the old B lease must be released by the unlink");
        //a fresh verified B login can claim the freed account without displacement
        final PlayerSession newB = new PlayerSession(UUID.randomUUID(), "JavaB-New");
        newB.connectionId = UUID.randomUUID();
        newB.c2pChannel = new EmbeddedChannel();
        final SessionLease newLease = coordinator.claim(newB, ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(coordinator.isCurrent(newLease));
        assertFalse(newB.displaced, "the freed account must not force a displacement");
    }

    // ---- stale-callback invalidation (§6) ---------------------------------------

    @Test
    void aLateLinkCallbackAfterUnlinkCannotResurrectTheJavaAccount() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession session = linkedBedrockSession(store, coordinator, links);

        assertEquals(IdentityLinkService.Result.COMMITTED, service(store, coordinator, links)
                .unlink(session).toCompletableFuture().get(10, TimeUnit.SECONDS));

        //a device-code link flow that was in flight before the unlink completes late:
        //its committed result lands on the session that no longer runs B's profile
        AccountFlow.applyLinkResult(session, IdentityLinkService.Result.COMMITTED, new StubAccount());
        assertNull(session.account, "a late committed link callback must not reinstall B's credentials");
    }

    @Test
    void theUnlinkResultMessageMappingCoversEveryOutcome() {
        for (final IdentityLinkService.Result result : IdentityLinkService.Result.values()) {
            assertNotNull(AccountFlow.unlinkResultMessage(result), "every result needs a message: " + result);
        }
        assertEquals(Messages.MainScreen.Unlink.ChatCommitted, AccountFlow.unlinkResultMessage(IdentityLinkService.Result.COMMITTED));
        assertEquals(Messages.MainScreen.Unlink.ChatCommittedCleanupPending,
                AccountFlow.unlinkResultMessage(IdentityLinkService.Result.COMMITTED_CLEANUP_PENDING));
        assertEquals(Messages.MainScreen.Unlink.ChatFailed, AccountFlow.unlinkResultMessage(IdentityLinkService.Result.FAILED));
        assertEquals(Messages.MainScreen.Unlink.ChatStale, AccountFlow.unlinkResultMessage(IdentityLinkService.Result.STALE));
    }

    // ---- review round 1: Important findings pinned ------------------------------

    /**
     * Review Important 1 (R5): a later verified B login winning the coordinator
     * race BETWEEN the post-claim re-check and the index commit must make the
     * unlink return STALE — the mapping is kept, the later holder runs B's
     * profile untouched, nothing is committed. The displacement is injected
     * through the PREPARED journal write, which the transaction runs exactly in
     * that window (after the post-claim check, before the pre-commit re-check).
     */
    @Test
    void aDisplacementBetweenThePostClaimCheckAndTheCommitMakesTheUnlinkStale() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession sessionA = linkedBedrockSession(store, coordinator, links);
        final String bBefore = Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8);
        final AtomicBoolean displacedInjected = new AtomicBoolean();
        final LinkTransactionJournal displacingJournal = new LinkTransactionJournal(playersDir()) {
            @Override
            public void write(final Entry entry) throws IOException {
                super.write(entry);
                if (!displacedInjected.compareAndSet(false, true)) {
                    return;
                }
                //The later verified B login wins the coordinator race right here:
                //its claim freezes, saves, invalidates and evicts the unlink session.
                final PlayerSession laterB = new PlayerSession(UUID.randomUUID(), "JavaB-Later");
                laterB.connectionId = UUID.randomUUID();
                laterB.c2pChannel = new EmbeddedChannel();
                try {
                    coordinator.claim(laterB, ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B))
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, links, coordinator,
                new TokenStore(this.tempDir), Runnable::run, () -> displacingJournal)
                .unlink(sessionA).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.STALE,
                result, "a displacement landing before the commit must stop the unlink");
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "the binding must be kept");
        assertEquals(bBefore, Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8), "B stays intact");
        assertTrue(sessionA.displaced, "the unlink session stays displaced");
        assertTrue(journal().readAll().isEmpty(), "the stale unlink leaves no journal entry");
    }

    /**
     * Review Important 2: a pre-commit abort must not clear B's in-memory
     * credentials when saveLoginInfo=false — the failed unlink leaves the
     * still-linked session with its working backend account and blob.
     */
    @Test
    void aPreCommitAbortKeepsTheLiveCredentialsWhenSaveLoginIsOff() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        //B's profile with saveLoginInfo=false but a live blob on the session.
        final PlayerData b = new PlayerData(ProfileKey.javaProfile(JAVA_B));
        b.saveLoginInfo = false;
        b.accountBlob = "live-session-blob";
        store.save(b);
        final PlayerSession session = linkedBedrockSession(store, coordinator, links);
        final StubAccount liveAccount = new StubAccount();
        session.account = liveAccount;
        session.playerData.accountBlob = "live-session-blob";
        final String bBefore = Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8);
        final IdentityLinkStore racedStore = new IdentityLinkStore(playersDir()) {
            @Override
            public void replace(final long expectedRevision, final Map<String, UUID> linksMap) throws IOException {
                throw new IOException("identity-links revision conflict (injected)");
            }
        };

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, racedStore, coordinator,
                new TokenStore(this.tempDir), Runnable::run)
                .unlink(session).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.FAILED, result);
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A), "the association is kept");
        //THE PIN: the live credentials survive the abort.
        assertSame(liveAccount, session.account,
                "a pre-commit abort must not clear the session's working account");
        assertEquals("live-session-blob", session.playerData.accountBlob,
                "the in-memory blob survives the abort");
        assertEquals(bBefore, Files.readString(javaFileB().toPath(), StandardCharsets.UTF_8),
                "the persisted profile is byte-identical (the wipe happens only on committed endings)");
        //the session re-acquired B's usage after the abort
        assertNotNull(session.lease);
        assertTrue(coordinator.isCurrent(session.lease));
    }

    /**
     * Review Important 3 (顶号 inversion): a newer verified login of the same
     * identity that claimed the freed resources while the unlink aborted keeps
     * its lease — the aborted session's re-acquisition is non-displacing and
     * hands out no lease to the older connection.
     */
    @Test
    void aNewerSameIdentityLoginKeepsItsLeaseWhenAnAbortedUnlinkReAcquires() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);
        final PlayerSession sessionA = linkedBedrockSession(store, coordinator, links);
        final IdentityLinkStore racedStore = new IdentityLinkStore(playersDir()) {
            @Override
            public void replace(final long expectedRevision, final Map<String, UUID> linksMap) throws IOException {
                //The abort is triggered at the commit; a newer same-identity
                //login wins the freed resources DURING the abort (between the
                //release and the re-acquisition — represented here by winning
                //them before the re-acquire runs).
                throw new IOException("identity-links revision conflict (injected)");
            }
        };
        final AtomicBoolean newerJoined = new AtomicBoolean();
        final AtomicReference<UUID> newerHolderId = new AtomicReference<>();
        final LinkTransactionJournal hookJournal = new LinkTransactionJournal(playersDir()) {
            @Override
            public void clear(final String operationId) throws IOException {
                //Runs inside the abort path, after release(transactionLease) has
                //been ordered and before reacquireAfterAbort claims: the newer
                //verified login takes the freed resources right here.
                if (newerJoined.compareAndSet(false, true)) {
                    final PlayerSession newer = new PlayerSession(UUID.randomUUID(), "JavaB-Newer");
                    newer.connectionId = UUID.randomUUID();
                    newer.c2pChannel = new EmbeddedChannel();
                    try {
                        coordinator.claim(newer, ProfileKey.javaProfile(JAVA_B), Set.of(JAVA_B))
                                .toCompletableFuture().get(10, TimeUnit.SECONDS);
                        newerHolderId.set(newer.connectionId);
                    } catch (final Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                super.clear(operationId);
            }
        };

        final IdentityLinkService.Result result = new DefaultIdentityLinkService(store, racedStore, coordinator,
                new TokenStore(this.tempDir), Runnable::run, () -> hookJournal)
                .unlink(sessionA).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(IdentityLinkService.Result.FAILED, result);
        assertEquals(JAVA_B, links.snapshot().javaUuidFor(XUID_A));
        assertTrue(newerJoined.get(), "the newer login must have claimed during the abort");
        //THE PIN: the newer holder was NOT displaced and keeps its lease; the
        //aborted session holds no lease at all.
        final UUID newerId = newerHolderId.get();
        assertNotNull(newerId, "the newer session must be tracked");
        assertTrue(coordinator.accountMayConnect(newerId),
                "the newer holder must keep its right of use (not displaced by the abort re-acquire)");
        assertFalse(coordinator.accountMayConnect(sessionA.connectionId),
                "the aborted session must not have taken the usage back");
        assertFalse(sessionA.lease != null && coordinator.isCurrent(sessionA.lease),
                "the aborted session must hold no current lease");
    }

    // ---- service-level refusals --------------------------------------------------

    @Test
    void aJavaPlayerOrAnUnlinkedBedrockPlayerCannotUnlink() throws Exception {
        final PlayerStore store = store();
        final AccountSessionCoordinator coordinator = coordinator(store);
        final IdentityLinkStore links = links();
        seedB(store);

        //a Java session owns its profile outright: no unlink, profile untouched
        final PlayerSession java = javaSession(store, coordinator);
        assertEquals(IdentityLinkService.Result.FAILED, service(store, coordinator, links)
                .unlink(java).toCompletableFuture().get(10, TimeUnit.SECONDS));

        //an unlinked bedrock session has no mapping to remove
        final PlayerSession unlinked = unlinkedBedrockSession(store, coordinator);
        assertEquals(IdentityLinkService.Result.FAILED, service(store, coordinator, links)
                .unlink(unlinked).toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(0, new IdentityLinkStore(playersDir()).snapshot().revision(),
                "neither refusal may touch the index");
        assertTrue(journal().readAll().isEmpty());
    }
}
