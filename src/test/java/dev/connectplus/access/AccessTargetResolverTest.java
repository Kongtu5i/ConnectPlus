package dev.connectplus.access;

import dev.connectplus.session.PlayerVisitStore;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccessTargetResolverTest {

    private static final UUID JAVA_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID JAVA_UUID_2 = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final String XUID = "2530000000000001";
    private static final String XUID_2 = "2530000000000002";

    @TempDir
    Path dir;

    private AccessService serviceWithLists() throws IOException {
        java.nio.file.Files.writeString(this.dir.resolve("whitelist.json"),
                "{\"schemaVersion\": 1, \"players\": []}");
        java.nio.file.Files.writeString(this.dir.resolve("blacklist.json"),
                "{\"schemaVersion\": 1, \"players\": []}");
        final AccessService service = new AccessService(new AccessListStore(this.dir.resolve("whitelist.json")),
                new AccessListStore(this.dir.resolve("blacklist.json")),
                new AccessService.ConfiguredFlags(false, false), Runnable::run);
        service.initialize().toCompletableFuture().join();
        return service;
    }

    private AccessTargetResolver resolver(final AccessService service, final AccessConnectionRegistry registry) {
        return new AccessTargetResolver(service, () -> registry,
                () -> new PlayerVisitStore(new File(this.dir.toFile(), "visits.json")));
    }

    @Test
    void canonicalUuidResolvesToAJavaTarget() throws IOException {
        final AccessService service = this.serviceWithLists();
        final List<AccessEntry> targets = this.resolver(service, new AccessConnectionRegistry())
                .resolve(AccessKey.ClientType.JAVA, JAVA_UUID.toString().toUpperCase(java.util.Locale.ROOT));

        assertEquals(1, targets.size());
        assertEquals(AccessKey.javaUuid(JAVA_UUID), targets.get(0).key());
        assertNull(targets.get(0).name(), "an unknown player resolves without a guessed name");
    }

    @Test
    void identifiersFindHistoricalNamesEvenWhenAnExistingListEntryHasNoName() throws IOException {
        final AccessService service = this.serviceWithLists();
        final PlayerVisitStore visits = new PlayerVisitStore(this.dir.resolve("visits.json").toFile());
        visits.recordKnownJavaName(JAVA_UUID, "JavaA");
        visits.recordVisit(JAVA_UUID, "BedrockB", dev.connectplus.identity.ClientIdentity.verifiedBedrock(
                JAVA_UUID, "BedrockB", XUID, UUID.randomUUID(), "history-session"));
        final AccessTargetResolver resolver = new AccessTargetResolver(service, AccessConnectionRegistry::new, () -> visits);
        assertEquals("JavaA", resolver.resolve(AccessKey.ClientType.JAVA, JAVA_UUID.toString()).get(0).name());
        assertEquals("BedrockB", resolver.resolve(AccessKey.ClientType.BEDROCK, XUID).get(0).name());
        service.mutateNow(AccessPolicy.Kind.WHITELIST,
                List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, null, JAVA_UUID, XUID)), Set.of());
        assertEquals("BedrockB", resolver.resolve(AccessKey.ClientType.BEDROCK, XUID).get(0).name());
    }

    @Test
    void namelessListEntryFallsThroughToTheExactOnlineSubject() throws IOException {
        final AccessService service = this.serviceWithLists();
        service.mutateNow(AccessPolicy.Kind.WHITELIST,
                List.of(new AccessEntry(AccessKey.ClientType.BEDROCK, null, null, XUID)), Set.of());
        final AccessConnectionRegistry registry = new AccessConnectionRegistry();
        final var channel = new EmbeddedChannel();
        final UUID connection = UUID.randomUUID();
        registry.register(connection, channel);
        registry.update(connection, new AccessSubject(AccessKey.ClientType.BEDROCK, "OnlineB", JAVA_UUID, XUID));
        try {
            assertEquals("OnlineB", this.resolver(service, registry)
                    .resolve(AccessKey.ClientType.BEDROCK, XUID).get(0).name());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void wireOnlyAndOtherPlatformHistoryNeverSupplyAJavaName() throws IOException {
        final AccessService service = this.serviceWithLists();
        final PlayerVisitStore visits = new PlayerVisitStore(this.dir.resolve("visits.json").toFile());
        visits.recordVisit(JAVA_UUID, "Temporary", null);
        visits.recordVisit(JAVA_UUID_2, "BedrockB", dev.connectplus.identity.ClientIdentity.verifiedBedrock(
                JAVA_UUID_2, "BedrockB", XUID, UUID.randomUUID(), "history-session"));
        final AccessTargetResolver resolver = new AccessTargetResolver(service, AccessConnectionRegistry::new, () -> visits);
        assertNull(resolver.resolve(AccessKey.ClientType.JAVA, JAVA_UUID.toString()).get(0).name());
        assertNull(resolver.resolve(AccessKey.ClientType.JAVA, JAVA_UUID_2.toString()).get(0).name());
    }

    @Test
    void exactIdentifiersStillResolveWhenOptionalHistoryIsCorrupt() throws IOException {
        final AccessService service = this.serviceWithLists();
        final Path history = this.dir.resolve("visits.json");
        java.nio.file.Files.writeString(history, "{broken");
        final byte[] before = java.nio.file.Files.readAllBytes(history);
        final AccessTargetResolver resolver = this.resolver(service, new AccessConnectionRegistry());
        assertNull(resolver.resolve(AccessKey.ClientType.JAVA, JAVA_UUID.toString()).get(0).name());
        assertNull(resolver.resolve(AccessKey.ClientType.BEDROCK, XUID).get(0).name());
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(history));
        assertThrows(PlayerVisitStore.CorruptIndexException.class,
                () -> resolver.resolve(AccessKey.ClientType.BEDROCK, "UnknownName"));
    }

    @Test
    void canonicalXuidResolvesToABedrockTarget() throws IOException {
        final AccessService service = this.serviceWithLists();
        final List<AccessEntry> targets = this.resolver(service, new AccessConnectionRegistry())
                .resolve(AccessKey.ClientType.BEDROCK, XUID);

        assertEquals(1, targets.size());
        assertEquals(AccessKey.bedrockXuid(XUID), targets.get(0).key());
    }

    @Test
    void nonCanonicalTokenResolvesToNothing() throws IOException {
        final AccessService service = this.serviceWithLists();
        final AccessTargetResolver resolver = this.resolver(service, new AccessConnectionRegistry());

        assertTrue(resolver.resolve(AccessKey.ClientType.BEDROCK, "0123").isEmpty(),
                "leading zeros are not canonical identifiers");
        assertTrue(resolver.resolve(AccessKey.ClientType.JAVA, "not-a-uuid").isEmpty());
        assertTrue(resolver.resolve(AccessKey.ClientType.JAVA, "").isEmpty());
    }

    @Test
    void namesResolveFromListEntriesAndTheRegistryCaseInsensitively() throws IOException {
        final AccessService service = this.serviceWithLists();
        try {
            service.mutateNow(AccessPolicy.Kind.WHITELIST, List.of(
                    new AccessEntry(AccessKey.ClientType.JAVA, "JavaPlayer", JAVA_UUID, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        final AccessConnectionRegistry registry = new AccessConnectionRegistry();
        final UUID connectionId = UUID.randomUUID();
        registry.register(connectionId, new EmbeddedChannel());
        registry.update(connectionId, new AccessSubject(AccessKey.ClientType.BEDROCK, "BedrockPlayer",
                UUID.randomUUID(), XUID));

        final AccessTargetResolver resolver = this.resolver(service, registry);
        final List<AccessEntry> java = resolver.resolve(AccessKey.ClientType.JAVA, "javaplayer");
        assertEquals(1, java.size());
        assertEquals(AccessKey.javaUuid(JAVA_UUID), java.get(0).key());
        assertEquals("JavaPlayer", java.get(0).name());

        final List<AccessEntry> bedrock = resolver.resolve(AccessKey.ClientType.BEDROCK, "BEDROCKPLAYER");
        assertEquals(1, bedrock.size());
        assertEquals(AccessKey.bedrockXuid(XUID), bedrock.get(0).key());
    }

    @Test
    void sameNameDifferentAccountsStaySeparateCandidates() throws IOException {
        final AccessService service = this.serviceWithLists();
        try {
            service.mutateNow(AccessPolicy.Kind.WHITELIST, List.of(
                    new AccessEntry(AccessKey.ClientType.JAVA, "SameName", JAVA_UUID, null),
                    new AccessEntry(AccessKey.ClientType.JAVA, "SameName", JAVA_UUID_2, null)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        final List<AccessEntry> targets = this.resolver(service, new AccessConnectionRegistry())
                .resolve(AccessKey.ClientType.JAVA, "samename");

        assertEquals(2, targets.size(), "same-name accounts are all candidates, never one");
    }

    @Test
    void forcedPlatformExcludesOtherPlatformMatches() throws IOException {
        final AccessService service = this.serviceWithLists();
        try {
            service.mutateNow(AccessPolicy.Kind.WHITELIST, List.of(
                    new AccessEntry(AccessKey.ClientType.JAVA, "Mixed", JAVA_UUID, null),
                    new AccessEntry(AccessKey.ClientType.BEDROCK, "Mixed", null, XUID)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        final AccessTargetResolver resolver = this.resolver(service, new AccessConnectionRegistry());

        assertEquals(1, resolver.resolve(AccessKey.ClientType.JAVA, "Mixed").size());
        assertEquals(1, resolver.resolve(AccessKey.ClientType.BEDROCK, "Mixed").size());
        assertEquals(2, resolver.resolveAny("Mixed").size(), "any-platform lookups see both");
    }

    @Test
    void visitStoreNamesResolveWithoutAddingVisits() throws IOException {
        final AccessService service = this.serviceWithLists();
        final PlayerVisitStore visits = new PlayerVisitStore(new File(this.dir.toFile(), "visits.json"));
        visits.recordKnownJavaName(JAVA_UUID, "KnownJava");

        final AccessTargetResolver resolver = new AccessTargetResolver(service,
                AccessConnectionRegistry::new, () -> visits);
        final List<AccessEntry> targets = resolver.resolve(AccessKey.ClientType.JAVA, "knownjava");

        assertEquals(1, targets.size());
        assertEquals(AccessKey.javaUuid(JAVA_UUID), targets.get(0).key());
        assertEquals("KnownJava", targets.get(0).name());
        assertEquals(0, visits.snapshot().visits().size(),
                "a direct connection's name metadata must not add a lobby visit");
    }

    @Test
    void resolveAnyMatchesByFormatFirst() throws IOException {
        final AccessService service = this.serviceWithLists();
        try {
            service.mutateNow(AccessPolicy.Kind.BLACKLIST, List.of(
                    new AccessEntry(AccessKey.ClientType.BEDROCK, "BR", null, XUID_2)), Set.of());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        final AccessTargetResolver resolver = this.resolver(service, new AccessConnectionRegistry());

        final List<AccessEntry> byXuid = resolver.resolveAny(XUID_2);
        assertEquals(1, byXuid.size());
        assertEquals(AccessKey.bedrockXuid(XUID_2), byXuid.get(0).key());
    }
}
