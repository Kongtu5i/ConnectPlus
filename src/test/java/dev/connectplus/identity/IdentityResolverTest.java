package dev.connectplus.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IdentityResolverTest {

    private static final String XUID = "2533274790000001";
    private static final UUID JAVA = UUID.fromString("12345678-1234-4234-8234-123456789abc");

    @TempDir
    File tempDir;

    private final IdentityResolver resolver = new IdentityResolver();

    @Test
    void verifiedJavaResolvesToItsOwnJavaProfile() {
        final ClientIdentity identity = ClientIdentity.verifiedJava(JAVA, "JavaPlayer", JAVA);

        assertEquals(ProfileKey.javaProfile(JAVA), resolver.resolve(identity, new IdentityLinkStore(tempDir).snapshot()));
    }

    @Test
    void unlinkedVerifiedBedrockResolvesToItsOwnBedrockProfile() {
        final ClientIdentity identity = ClientIdentity.verifiedBedrock(
                UUID.nameUUIDFromBytes("bedrock".getBytes()), "BedrockPlayer", XUID, UUID.randomUUID(), "bridge-session-1");

        assertEquals(ProfileKey.bedrockProfile(XUID),
                resolver.resolve(identity, new IdentityLinkStore(tempDir).snapshot()));
    }

    @Test
    void linkedVerifiedBedrockResolvesToTheJavaProfileOfTheLinkedAccount() throws IOException {
        final IdentityLinkStore store = new IdentityLinkStore(tempDir);
        store.replace(0, Map.of(XUID, JAVA));
        final ClientIdentity identity = ClientIdentity.verifiedBedrock(
                UUID.nameUUIDFromBytes("bedrock".getBytes()), "BedrockPlayer", XUID, UUID.randomUUID(), "bridge-session-1");

        assertEquals(ProfileKey.javaProfile(JAVA), resolver.resolve(identity, store.snapshot()),
                "after linking, the bedrock identity uses the Java account's profile");
    }

    @Test
    void unverifiedIdentitiesAreRejectedWithoutAProtectedProfileKey() {
        final ClientIdentity unverified = ClientIdentity.unverified(JAVA, "Anyone");

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(unverified, new IdentityLinkStore(tempDir).snapshot()),
                "an unverified identity must never produce a protected ProfileKey");
    }

}
