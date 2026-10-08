package dev.connectplus.lobby;

import dev.connectplus.identity.ProfileKey;
import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayDisconnectPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: the shrunken lobby player commands. Only /disconnect and /dc
 * act (the proxy connection closes); extra arguments answer the usage line; every
 * removed command and plain chat is silently ignored — no switch handoff, no
 * bookmark change, no chat noise.
 */
class LobbyCommandsIT {

    private LobbyServer server;
    private SessionRegistry sessionRegistry;
    private PlayerStore playerStore;
    private RecordingSwitchInitiator switchInitiator;

    @TempDir
    File dataDir;

    private LobbyServer startServer() {
        this.sessionRegistry = new SessionRegistry();
        this.switchInitiator = new RecordingSwitchInitiator();
        this.playerStore = new PlayerStore(new File(this.dataDir, "players"));
        final LobbyServer server = new LobbyServer(this.sessionRegistry, new dev.connectplus.accounts.TokenStore(this.dataDir), this.playerStore, this.switchInitiator);
        server.start();
        this.server = server;
        return server;
    }

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop();
            this.server = null;
        }
    }

    private InetSocketAddress serverAddress() {
        return (InetSocketAddress) this.server.localAddress();
    }

    private LobbyTestClient joinWithPreamble(final String name, final UUID uuid) throws InterruptedException {
        final LobbyTestClient client = new LobbyTestClient();
        client.connect(this.serverAddress());
        client.sendHaProxyPreamble("cmdtest.example.org", 25565);
        client.login(name, uuid);
        client.await(S2CLoginGameProfilePacket.class);
        client.write(new C2SLoginAcknowledgedPacket());
        client.await(S2COpenScreenPacket.class, 1);
        return client;
    }

    @Test
    @Timeout(15)
    void disconnectCommandClosesTheProxyConnection() throws Exception {
        startServer();
        final LobbyTestClient client = this.joinWithPreamble("DiscPlayer", UUID.randomUUID());

        client.sendChatCommand("disconnect");

        assertNotNull(client.await(S2CPlayDisconnectPacket.class), "The lobby must send the disconnect screen text");
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (client.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertFalse(client.isActive(), "The lobby must close the proxy connection after /disconnect");
        assertEquals(0, this.switchInitiator.requests().size(), "A disconnect never hands off to the engine");
    }

    @Test
    @Timeout(15)
    void dcAliasClosesTheProxyConnection() throws Exception {
        startServer();
        final LobbyTestClient client = this.joinWithPreamble("DcPlayer", UUID.randomUUID());

        client.sendChatCommand("dc");

        assertNotNull(client.await(S2CPlayDisconnectPacket.class));
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (client.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertFalse(client.isActive(), "The /dc alias must behave exactly like /disconnect");
    }

    @Test
    @Timeout(15)
    void signedVariantDisconnectClosesToo() throws Exception {
        startServer();
        final LobbyTestClient client = this.joinWithPreamble("SignedDiscPlayer", UUID.randomUUID());

        //Pre-1.19.1 clients' slash commands arrive as the signed variant after the
        //ViaVersion translation; the lobby must run them through the same chain
        client.sendChatCommandSigned("disconnect");

        assertNotNull(client.await(S2CPlayDisconnectPacket.class));
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (client.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertFalse(client.isActive(), "The signed variant must close the connection like /disconnect");
    }

    @Test
    @Timeout(15)
    void disconnectWithArgumentsAnswersUsageAndStays() throws Exception {
        startServer();
        final LobbyTestClient client = this.joinWithPreamble("UsagePlayer", UUID.randomUUID());

        client.sendChatCommand("disconnect now");

        final S2CSystemChatPacket usage = client.await(S2CSystemChatPacket.class, 3); //occurrences 1-2 are the welcome
        assertTrue(usage.message.asUnformattedString().contains("/disconnect"),
                "The usage line must name the two exit commands, got: " + usage.message.asUnformattedString());
        assertTrue(client.isActive(), "A usage error must not close the connection");
        assertEquals(0, this.switchInitiator.requests().size(), "A usage error never hands off to the engine");
    }

    @Test
    @Timeout(15)
    void removedCommandsAreSilentlyIgnored() throws Exception {
        startServer();
        final UUID uuid = UUID.randomUUID();
        final LobbyTestClient client = this.joinWithPreamble("OldCmdPlayer", uuid);

        client.sendChatCommand("connect old.example.net 25565 1.12.2");
        client.sendChatCommand("bookmarks");
        client.sendChatCommand("bm add kept");
        client.sendChatCommand("cphelp");
        client.sendChat("hello");

        Thread.sleep(500);
        assertEquals(0, this.switchInitiator.requests().size(),
                "Removed commands must neither reach the switch initiator nor produce feedback");
        assertTrue(client.isActive(), "The lobby connection stays alive");
        assertTrue(this.sessionRegistry.get(uuid).serverAddress == null,
                "The session state must be unchanged by removed commands");
        assertEquals(0, this.playerStore.load(ProfileKey.javaProfile(uuid)).bookmarks.size(),
                "Removed bookmark commands must not change stored bookmarks");
        assertTrue(client.receivedSnapshot().stream().filter(p -> p instanceof S2CSystemChatPacket).count() <= 2,
                "No command feedback chat may appear beyond the 2-line welcome");
        assertTrue(client.receivedSnapshot().stream().filter(p -> p instanceof S2COpenScreenPacket).count() == 1,
                "Removed commands must not open any GUI");
    }

}
