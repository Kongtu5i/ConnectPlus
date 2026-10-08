package dev.connectplus.lobby;

import dev.connectplus.accounts.TokenStore;
import dev.connectplus.config.CPConfig;
import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import dev.connectplus.session.PlayerStore;
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
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: a client that sends its settings with a Chinese locale
 * receives the Chinese texts (M6 F1.4). The English default is anchored by the
 * existing GUI ITs (their clients never send client information).
 */
class LobbyLocalizationIT {

    private LobbyServer server;
    private SessionRegistry sessionRegistry;

    @TempDir
    File dataDir;

    @org.junit.jupiter.api.BeforeEach
    void useAutoLanguage() {
        //This IT exercises the per-locale resolution (F1.4); the production
        //default is the forced English config value
        CPConfig.language = "auto";
    }

    private LobbyServer startServer() {
        this.sessionRegistry = new SessionRegistry();
        final LobbyServer server = new LobbyServer(this.sessionRegistry, new TokenStore(this.dataDir),
                new PlayerStore(new File(this.dataDir, "players")), new RecordingSwitchInitiator());
        server.start();
        this.server = server;
        return server;
    }

    @AfterEach
    void stopServer() {
        CPConfig.language = "en";
        if (this.server != null) {
            this.server.stop();
            this.server = null;
        }
    }

    private LobbyTestClient join(final String name, final String locale) throws InterruptedException {
        final LobbyTestClient client = new LobbyTestClient();
        client.connect((InetSocketAddress) this.server.localAddress());
        client.login(name, UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
        client.await(S2CLoginGameProfilePacket.class);
        client.write(new C2SLoginAcknowledgedPacket());
        if (locale != null) {
            client.sendClientInformation(locale);
        }
        return client;
    }

    @Test
    @Timeout(15)
    void chineseClientReceivesChineseWelcomeAndCommandFeedback() throws Exception {
        startServer();
        final LobbyTestClient client = this.join("ZhPlayer", "zh_cn");

        final S2CSystemChatPacket welcome = client.await(S2CSystemChatPacket.class);
        assertTrue(welcome.message.asUnformattedString().contains("ConnectPlus 大厅"),
                "The Chinese welcome must be sent, got: " + welcome.message.asUnformattedString());

        //The locale was captured into the session
        assertEquals("zh_cn", this.sessionRegistry.get(UUID.nameUUIDFromBytes("ZhPlayer".getBytes(StandardCharsets.UTF_8))).locale,
                "The client settings locale must be stored on the session");

        //Command feedback is Chinese too (exit-command usage line)
        client.sendChat("/disconnect extra");
        S2CSystemChatPacket feedback = client.await(S2CSystemChatPacket.class, 3);
        assertTrue(feedback.message.asUnformattedString().contains("用法"),
                "The Chinese command feedback must be sent, got: " + feedback.message.asUnformattedString());
        assertTrue(client.isActive());
    }

    @Test
    @Timeout(15)
    void englishDefaultWithoutClientInformation() throws Exception {
        startServer();
        final LobbyTestClient client = this.join("EnPlayer", null);

        final S2CSystemChatPacket welcome = client.await(S2CSystemChatPacket.class);
        assertTrue(welcome.message.asUnformattedString().contains("Welcome to the ConnectPlus lobby"),
                "The English default must stay, got: " + welcome.message.asUnformattedString());
    }

    @Test
    @Timeout(15)
    void playStateClientInformationUpdatesTheLocale() throws Exception {
        //A client that changes its language inside the play state (rare but legal)
        //updates the session locale: the next texts (command feedback, reopened
        //screens) follow the new language, and the connection stays alive (M7).
        startServer();
        final LobbyTestClient client = this.join("SwitchPlayer", "en_us");
        client.await(S2CSystemChatPacket.class); //English welcome (first line)

        client.sendClientInformationInPlay("zh_cn");
        client.sendChat("/disconnect extra");
        //Welcome line 1 + line 2 arrive first; the next system chat is the feedback
        S2CSystemChatPacket feedback = client.await(S2CSystemChatPacket.class, 3);
        assertTrue(feedback.message.asUnformattedString().contains("用法"),
                "The play-state settings must switch the language, got: "
                        + feedback.message.asUnformattedString());
        assertEquals("zh_cn", this.sessionRegistry.get(UUID.nameUUIDFromBytes("SwitchPlayer".getBytes(StandardCharsets.UTF_8))).locale,
                "The play-state locale must be stored on the session");
        assertTrue(client.isActive());
    }
}
