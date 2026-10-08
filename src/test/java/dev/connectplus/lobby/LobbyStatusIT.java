package dev.connectplus.lobby;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.accounts.TokenStore;
import dev.connectplus.session.PlayerStore;
import dev.connectplus.session.SessionRegistry;
import dev.connectplus.testutil.RecordingSwitchInitiator;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.IntendedState;
import net.raphimc.netminecraft.packet.impl.handshaking.C2SHandshakingClientIntentionPacket;
import net.raphimc.netminecraft.packet.impl.status.S2CStatusResponsePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: the lobby's status (server list ping) response must echo
 * the pinging client's own protocol version (V7 manual verification finding) —
 * the lobby is version-agnostic and translated for every client version, so
 * advertising the pinned 1.21.4 protocol id made every older client's server
 * list show an "outdated client" marker.
 */
class LobbyStatusIT {

    private LobbyServer server;

    @TempDir
    File dataDir;

    private void startServer() {
        this.server = new LobbyServer(new SessionRegistry(), new TokenStore(this.dataDir),
                new PlayerStore(new File(this.dataDir, "players")), new RecordingSwitchInitiator());
        this.server.start();
    }

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop();
            this.server = null;
        }
    }

    private LobbyTestClient pingAs(final ProtocolVersion clientVersion) {
        final LobbyTestClient client = new LobbyTestClient();
        client.connect((java.net.InetSocketAddress) this.server.localAddress());
        //The HAProxy TLV carries the client's real version (a bare ping without
        //preamble has no handshake data and falls back to the pinned version)
        client.sendHaProxyPreamble("status.example.org", 25565, clientVersion);
        final java.net.InetSocketAddress remote = (java.net.InetSocketAddress) client.remoteAddressOf();
        client.write(new C2SHandshakingClientIntentionPacket(LobbyProtocol.VERSION.getVersion(), remote.getHostString(), remote.getPort(), IntendedState.STATUS));
        client.setState(ConnectionState.STATUS);
        client.write(new net.raphimc.netminecraft.packet.impl.status.C2SStatusRequestPacket());
        return client;
    }

    @Test
    @Timeout(15)
    void statusResponseEchoesTheClientVersion() throws Exception {
        startServer();
        //A 1.7.10 client (protocol 5): the response must advertise 5, not 769
        final LobbyTestClient legacy = this.pingAs(ProtocolVersion.v1_7_6);
        final S2CStatusResponsePacket legacyResponse = legacy.await(S2CStatusResponsePacket.class);
        assertTrue(legacyResponse.statusJson.contains("\"protocol\":5"),
                "A 1.7.10 ping must see its own protocol id, got: " + legacyResponse.statusJson);
    }

    @Test
    @Timeout(15)
    void statusResponseEchoesTheLobbyVersionWithoutHandshakeData() throws Exception {
        startServer();
        //A modern client pings without an HAProxy preamble: the fallback is the
        //pinned lobby version, which matches the client anyway
        final LobbyTestClient modern = new LobbyTestClient();
        modern.connect((java.net.InetSocketAddress) this.server.localAddress());
        final java.net.InetSocketAddress remote = (java.net.InetSocketAddress) modern.remoteAddressOf();
        modern.write(new C2SHandshakingClientIntentionPacket(LobbyProtocol.VERSION.getVersion(), remote.getHostString(), remote.getPort(), IntendedState.STATUS));
        modern.setState(ConnectionState.STATUS);
        modern.write(new net.raphimc.netminecraft.packet.impl.status.C2SStatusRequestPacket());
        final S2CStatusResponsePacket response = modern.await(S2CStatusResponsePacket.class);
        assertTrue(response.statusJson.contains("\"protocol\":" + LobbyProtocol.VERSION.getVersion()),
                "A bare modern ping falls back to the pinned lobby version, got: " + response.statusJson);
    }

    @Test
    @Timeout(15)
    void customMotdSurvivesQuotesUnicodeAndNewlines() throws Exception {
        startServer();
        final String motd = "§a欢迎来到 \"我的服务器\"\\test\n§b第二行";
        final String original = dev.connectplus.config.CPConfig.motd;
        try {
            dev.connectplus.config.CPConfig.motd = motd;
            final LobbyTestClient client = this.pingAs(ProtocolVersion.v1_21_4);
            final S2CStatusResponsePacket response = client.await(S2CStatusResponsePacket.class);
            final com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(response.statusJson).getAsJsonObject();
            org.junit.jupiter.api.Assertions.assertEquals(motd, json.get("description").getAsString());
            org.junit.jupiter.api.Assertions.assertEquals(769, json.getAsJsonObject("version").get("protocol").getAsInt());
        } finally {
            dev.connectplus.config.CPConfig.motd = original;
        }
    }
}
