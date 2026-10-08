package dev.connectplus.compat;

import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LobbyLinkTest {

    private ProxyConnection connection;
    private UUID linkId;

    @BeforeEach
    void setUp() {
        this.connection = new ProxyConnection(new MinecraftChannelInitializer(() -> null), new EmbeddedChannel());
        this.linkId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        LobbyLink.unregister(this.linkId);
    }

    @Test
    void resolvesByLinkId() {
        LobbyLink.register(this.linkId, this.connection);
        assertSame(this.connection, LobbyLink.resolve(this.linkId),
                "The lobby channel's link id attribute resolves the p2s proxy connection");
    }

    @Test
    void unknownIdResolvesToNull() {
        assertNull(LobbyLink.resolve(UUID.randomUUID()));
    }

    @Test
    void laterRegistrationOverwritesAndUnregisterRemoves() {
        final ProxyConnection second = new ProxyConnection(new MinecraftChannelInitializer(() -> null), new EmbeddedChannel());
        LobbyLink.register(this.linkId, this.connection);
        LobbyLink.register(this.linkId, second);
        assertSame(second, LobbyLink.resolve(this.linkId));
        LobbyLink.unregister(this.linkId);
        assertNull(LobbyLink.resolve(this.linkId), "The closed p2s must not stay registered");
    }

    @Test
    void entriesAreIsolatedPerId() {
        LobbyLink.register(this.linkId, this.connection);
        final UUID other = UUID.randomUUID();
        assertNull(LobbyLink.resolve(other), "Another player's connection must not leak");
        assertEquals(this.connection, LobbyLink.resolve(this.linkId));
        LobbyLink.unregister(other);
    }

}
