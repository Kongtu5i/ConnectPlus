package dev.connectplus.lobby;

import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Messages;
import org.junit.jupiter.api.Test;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LobbyNoticesTest {

    @Test
    void noticeResolvesTemplateByLanguage() {
        final LobbyNotices.Notice notice = LobbyNotices.Notice.of(Messages.Commands.SwitchBusy);

        //The built-in packs resolve the real texts (CPConfig.language defaults to en,
        //so Lang.of pins the code independently of the client locale)
        assertEquals(Languages.text("en", Messages.Commands.SwitchBusy), notice.text("en"));
        assertEquals(Languages.text("zh", Messages.Commands.SwitchBusy), notice.text("zh"));
        assertEquals(Messages.Zh.Commands.SwitchBusy, notice.text("zh"));
    }

    @Test
    void rawNoticeIsSentVerbatim() {
        //Raw (pre-formatted) notices have no template and must never break
        assertEquals("raw line", LobbyNotices.Notice.raw("raw line").text("zh"));
    }

    @Test
    void noticeArgumentsStayUntouchedUntilFormatting() {
        final LobbyNotices.Notice notice = LobbyNotices.Notice.of(Messages.Commands.SwitchFailed, "addr", "reason");
        assertEquals("addr", notice.args()[0]);
        assertEquals("reason", notice.args()[1]);
    }

    @Test
    void customLanguagePackServesTheNotice() {
        Languages.resetForTests();
        final Map<String, String> pack = new HashMap<>();
        pack.put(Messages.Commands.SwitchBusy.key(), "§cin progress");
        Languages.putPackForTests("xx", pack);

        try {
            assertEquals("§cin progress", LobbyNotices.Notice.of(Messages.Commands.SwitchBusy).text("xx"));
        } finally {
            Languages.resetForTests();
        }
    }

    @Test
    void postConsumeAndReplaceByUuid() {
        LobbyNotices.consumeAll();
        final UUID player = UUID.randomUUID();

        LobbyNotices.postRaw(player, List.of("one"));
        assertEquals(1, LobbyNotices.size());
        assertEquals("one", LobbyNotices.consume(player).get(0).text(Lang.EN.code()));
        assertNull(LobbyNotices.consume(player), "The notice must be consumed once");

        LobbyNotices.post(player, List.of(LobbyNotices.Notice.of(Messages.Commands.BmDeleted)));
        LobbyNotices.post(player, List.of(LobbyNotices.Notice.of(Messages.Commands.BmNotFound)));
        final List<LobbyNotices.Notice> pending = LobbyNotices.consume(player);
        assertEquals(1, pending.size(), "A second post replaces the pending notice");
        assertEquals(Messages.Zh.Commands.BmNotFound, pending.get(0).text(Lang.ZH.code()));
        assertEquals(0, LobbyNotices.size());
    }

    @Test
    void connectionBoundNoticeIsAvailableOnceWhileItsClientRemainsConnected() {
        final UUID player = UUID.randomUUID();
        final EmbeddedChannel client = new EmbeddedChannel();
        try {
            LobbyNotices.post(player, List.of(LobbyNotices.Notice.raw("backend died")), client);
            assertEquals("backend died", LobbyNotices.consume(player).get(0).text("en"));
            assertNull(LobbyNotices.consume(player));
        } finally {
            client.finishAndReleaseAll();
            LobbyNotices.consume(player);
        }
    }

    @Test
    void aClosedConnectionCannotCarryANoticeIntoAFreshLogin() {
        final UUID player = UUID.randomUUID();
        final EmbeddedChannel client = new EmbeddedChannel();
        try {
            client.close();
            LobbyNotices.post(player, List.of(LobbyNotices.Notice.raw("old backend died")), client);
            assertNull(LobbyNotices.consume(player));
        } finally {
            client.finishAndReleaseAll();
            LobbyNotices.consume(player);
        }
    }

    @Test
    void cancelingAnOldPostCannotRemoveAnIdenticalReplacement() {
        final UUID player = UUID.randomUUID();
        final EmbeddedChannel client = new EmbeddedChannel();
        final List<LobbyNotices.Notice> notices = List.of(LobbyNotices.Notice.raw("backend died"));
        try {
            final Runnable cancelOld = LobbyNotices.post(player, notices, client);
            LobbyNotices.post(player, notices, client);
            cancelOld.run();
            final List<LobbyNotices.Notice> replacement = LobbyNotices.consume(player);
            assertNotNull(replacement, "Even equal message contents belong to distinct recovery posts");
            assertEquals("backend died", replacement.get(0).text("en"));
        } finally {
            client.finishAndReleaseAll();
            LobbyNotices.consume(player);
        }
    }
}
