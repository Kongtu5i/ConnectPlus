package dev.connectplus.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for the pure lobby command parser. Players keep exactly two
 * commands: {@code /disconnect} and {@code /dc}; anything else parses to NONE
 * so the target server (or plain chat) sees it untouched.
 */
class LobbyCommandsTest {

    @Test
    void disconnectParses() {
        assertSame(LobbyCommands.Type.DISCONNECT, LobbyCommands.parse("/disconnect").type());
    }

    @Test
    void dcAliasParses() {
        assertSame(LobbyCommands.Type.DISCONNECT, LobbyCommands.parse("/dc").type());
    }

    @Test
    void disconnectIgnoresSurroundingWhitespace() {
        assertSame(LobbyCommands.Type.DISCONNECT, LobbyCommands.parse("  /disconnect  ").type());
        assertSame(LobbyCommands.Type.DISCONNECT, LobbyCommands.parse(" /dc ").type());
    }

    @Test
    void disconnectWithExtraArgumentsIsAUsageError() {
        assertSame(LobbyCommands.Type.USAGE, LobbyCommands.parse("/disconnect now").type());
    }

    @Test
    void dcWithExtraArgumentsIsAUsageError() {
        assertSame(LobbyCommands.Type.USAGE, LobbyCommands.parse("/dc extra").type());
    }

    @Test
    void removedCommandsParseToNone() {
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/connect a.com").type());
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/connect a.com 25565 1.12.2").type());
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/connect").type());
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/cphelp").type());
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/bookmarks").type());
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/bm list").type());
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/bm add myserver").type());
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/bm del myserver").type());
    }

    @Test
    void caseSensitiveCommandsAreNotRecognized() {
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/Disconnect").type());
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/DC").type());
    }

    @Test
    void plainChatIsIgnored() {
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("hello").type());
    }

    @Test
    void unknownCommandIsIgnored() {
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/unknown").type());
    }

    @Test
    void nullAndEmptyInputAreIgnored() {
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse(null).type());
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("").type());
        assertSame(LobbyCommands.Type.NONE, LobbyCommands.parse("/").type());
    }

    @Test
    void commandCarriesOnlyItsType() {
        final LobbyCommands.Command command = LobbyCommands.parse("/disconnect");
        assertEquals(LobbyCommands.Type.DISCONNECT, command.type());
        assertEquals(1, LobbyCommands.Command.class.getRecordComponents().length, "The record must carry only the type");
    }

}
