package dev.connectplus.compat;

import dev.connectplus.access.AccessKey;
import dev.connectplus.identity.ClientIdentity;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClientPlatformInspectorTest {

    private static final UUID WIRE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String XUID = "2530000000000001";

    private final ClientPlatformInspector inspector = new ClientPlatformInspector();

    @Test
    void verifiedJavaIdentityClassifiesJava() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(
                ClientIdentity.verifiedJava(WIRE, "Java", WIRE));

        assertEquals(ClientPlatformInspector.Classification.JAVA, this.inspector.inspect(channel));
    }

    @Test
    void verifiedBedrockIdentityClassifiesBedrock() throws Exception {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(
                ClientIdentity.verifiedBedrock(WIRE, "Bedrock", XUID, UUID.randomUUID(), UUID.randomUUID().toString()));

        assertEquals(ClientPlatformInspector.Classification.BEDROCK, this.inspector.inspect(channel));
    }

    @Test
    void offlineJavaDoesNotBecomeBedrockWhenBridgeUnavailable() {
        // An UNVERIFIED connection without any bridge signal (bridge disabled, timed
        // out or answered NO_MATCH) must never classify as a bedrock session.
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.unverified(WIRE, "Java"));

        assertEquals(ClientPlatformInspector.Classification.UNKNOWN, this.inspector.inspect(channel));
    }

    @Test
    void noIdentityAtAllClassifiesUnknown() {
        assertEquals(ClientPlatformInspector.Classification.UNKNOWN,
                this.inspector.inspect(new EmbeddedChannel()));
    }

    @Test
    void untrustedBedrockSessionSignalClassifiesBedrock() {
        // The bridge matched a bedrock session on the raw address pair but could not
        // hand out a trusted identity (UNTRACKED_SESSION, IDENTITY_UNTRUSTED, ...).
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(ClientIdentity.unverified(WIRE, "Bedrock"));
        channel.attr(CPAttributeKeys.PLATFORM_CLASSIFICATION)
                .set(ClientPlatformInspector.Classification.BEDROCK);

        assertEquals(ClientPlatformInspector.Classification.BEDROCK, this.inspector.inspect(channel));
        // The platform signal must never upgrade the captured identity itself.
        assertEquals(ClientIdentity.Kind.UNVERIFIED,
                channel.attr(CPAttributeKeys.CLIENT_IDENTITY).get().kind());
    }

    @Test
    void verifiedIdentityWinsOverThePlatformSignal() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(CPAttributeKeys.CLIENT_IDENTITY).set(
                ClientIdentity.verifiedJava(WIRE, "Java", WIRE));
        channel.attr(CPAttributeKeys.PLATFORM_CLASSIFICATION)
                .set(ClientPlatformInspector.Classification.BEDROCK);

        assertEquals(ClientPlatformInspector.Classification.JAVA, this.inspector.inspect(channel));
    }

    @Test
    void bedrockSubjectsKeepTheirXuidPrimaryKey() {
        // Documentation of the matching rule: a bedrock key is XUID-shaped and never
        // equal to a java key, whatever the string content.
        assertNotEquals(AccessKey.bedrockXuid(XUID), AccessKey.javaUuid(WIRE));
    }
}
