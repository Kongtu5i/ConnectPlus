package dev.connectplus.lobby;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.lobby.protocol.packets.play.s2c.S2COpenScreenPacket;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.PlayerVisitStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: the console-query visit index records exactly the
 * connections that actually entered the lobby — a handshake/login that never
 * reaches the play state records nothing, a repeat visit never adds a visitor,
 * and the registry snapshot mirrors the joined sessions.
 */
class LobbyVisitRecordingTest {

    @TempDir
    File dataDir;

    private LobbyServer server;

    private LobbyServer startServer() {
        final SessionRegistry registry = new SessionRegistry();
        final PlayerStore playerStore = new PlayerStore(new File(this.dataDir, "players"));
        final LobbyServer server = new LobbyServer(registry, new TokenStore(this.dataDir), playerStore,
                new RecordingSwitchInitiator());
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

    private LobbyTestClient join(final String name, final UUID uuid) throws InterruptedException {
        final LobbyTestClient client = new LobbyTestClient();
        client.connect((InetSocketAddress) this.server.localAddress());
        client.login(name, uuid);
        client.await(S2CLoginGameProfilePacket.class);
        client.write(new C2SLoginAcknowledgedPacket());
        return client;
    }

    private PlayerVisitStore.Snapshot awaitVisits(final int expectedVisitors) throws InterruptedException {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        PlayerVisitStore.Snapshot snapshot = this.server.visitStore().snapshot();
        while (snapshot.uniqueVisitorCount(Map.of()) < expectedVisitors && System.nanoTime() < deadline) {
            Thread.sleep(25);
            snapshot = this.server.visitStore().snapshot();
        }
        return snapshot;
    }

    @Test
    @Timeout(15)
    void successfulLobbyJoinRecordsOneVisitAndRepeatsNeverAddVisitors() throws Exception {
        startServer();
        final UUID uuid = UUID.nameUUIDFromBytes("visit-player".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        final LobbyTestClient client = this.join("VisitPlayer", uuid);
        final PlayerVisitStore.Snapshot snapshot = this.awaitVisits(1);
        assertEquals(1, snapshot.visits().size());
        final PlayerVisitStore.VisitEntry entry = snapshot.visits().get(0);
        assertEquals("VisitPlayer", entry.name(), "The entry protocol name is recorded");
        assertEquals(uuid, entry.wireUuid(), "The original protocol UUID is recorded");
        assertTrue(entry.temporary(), "A bare diagnostic join is an unconfirmed (temporary) identity");

        //A second visit of the same identity (same wire UUID) folds into the record
        final LobbyTestClient second = this.join("VisitPlayer", uuid);
        Thread.sleep(300);
        assertEquals(1, this.server.visitStore().snapshot().uniqueVisitorCount(Map.of()),
                "A repeated visit must not add a second visitor");
        client.await(S2COpenScreenPacket.class, 1);
        second.await(S2COpenScreenPacket.class, 1);
        //Both connections carry the same protocol uuid, so they share one registry
        //slot (the registry keys on the connection id, not the wire identity)
        assertEquals(1, this.server.sessionRegistry().snapshot().size(),
                "The registry snapshot lists the joined session");
    }

    @Test
    @Timeout(15)
    void connectionsThatNeverReachThePlayStateRecordNothing() throws Exception {
        startServer();
        //A connection that opens and closes without completing a login
        final LobbyTestClient abandoned = new LobbyTestClient();
        abandoned.connect((InetSocketAddress) this.server.localAddress());
        abandoned.close();

        //A connection sending garbage instead of a handshake
        final LobbyTestClient garbage = new LobbyTestClient();
        garbage.connect((InetSocketAddress) this.server.localAddress());
        garbage.sendRaw(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        Thread.sleep(500);
        assertEquals(0, this.server.visitStore().snapshot().uniqueVisitorCount(Map.of()),
                "Failed or abandoned connections must never record a visit");
        assertEquals(0, this.server.sessionRegistry().snapshot().size());
    }

}
