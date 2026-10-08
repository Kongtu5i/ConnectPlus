package dev.connectplus.commands;

import dev.connectplus.access.AccessKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure console command parser: the four root aliases are
 * equivalent, every subcommand parses, arguments are validated and usage errors
 * never change any state.
 */
class ConsoleCommandsTest {

    @Test
    void fourRootAliasesAreEquivalent() {
        for (final String root : new String[]{"cp", "connectplus", "/cp", "/connectplus"}) {
            assertEquals(ConsoleCommands.Type.STATUS, ConsoleCommands.parse(root, new String[]{"status"}).type(),
                    "The root " + root + " must behave like every other alias");
        }
    }

    @Test
    void onlyTheCpRootsMatch() {
        for (final String token : new String[]{"help", "connect", "", "cp1", "connectplus2", "Cp", "CP", "/CP"}) {
            assertFalse(ConsoleCommands.isRoot(token), "'" + token + "' must not be a CP root");
        }
        for (final String token : new String[]{"cp", "connectplus", "/cp", "/connectplus"}) {
            assertTrue(ConsoleCommands.isRoot(token), "'" + token + "' must be a CP root");
        }
    }

    @Test
    void emptyRootShowsHelp() {
        assertEquals(ConsoleCommands.Type.HELP, ConsoleCommands.parse("cp", new String[]{}).type());
        assertEquals(ConsoleCommands.Type.HELP, ConsoleCommands.parse("/cp", new String[]{}).type());
        assertEquals(ConsoleCommands.Type.HELP, ConsoleCommands.parse("cp", new String[]{""}).type());
    }

    @Test
    void allEightSubcommandsParse() {
        assertEquals(ConsoleCommands.Type.STATUS, ConsoleCommands.parse("cp", new String[]{"status"}).type());
        assertEquals(ConsoleCommands.Type.LIST, ConsoleCommands.parse("cp", new String[]{"list"}).type());
        assertEquals(ConsoleCommands.Type.LINKS, ConsoleCommands.parse("cp", new String[]{"links"}).type());
        assertEquals(ConsoleCommands.Type.INFO, ConsoleCommands.parse("cp", new String[]{"info", "Steve"}).type());
        assertEquals(ConsoleCommands.Type.VERSION, ConsoleCommands.parse("cp", new String[]{"version"}).type());
        assertEquals(ConsoleCommands.Type.DEBUG, ConsoleCommands.parse("cp", new String[]{"debug", "on"}).type());
        assertEquals(ConsoleCommands.Type.ACCOUNTS, ConsoleCommands.parse("cp", new String[]{"accounts"}).type());
        assertEquals(ConsoleCommands.Type.HELP, ConsoleCommands.parse("cp", new String[]{"help"}).type());
    }

    @Test
    void debugRequiresOnOrOff() {
        assertEquals("on", ConsoleCommands.parse("cp", new String[]{"debug", "on"}).argument());
        assertEquals("off", ConsoleCommands.parse("cp", new String[]{"debug", "off"}).argument());
        assertEquals("on", ConsoleCommands.parse("cp", new String[]{"debug", "ON"}).argument(), "The switch value is case-insensitive");
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"debug"}).type(), "A missing value is a usage error");
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"debug", "yes"}).type());
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"debug", "on", "extra"}).type());
    }

    @Test
    void infoKeepsSpacesInTheName() {
        final ConsoleCommands.Command command = ConsoleCommands.parse("cp", new String[]{"info", "Bed", "rock", "Two"});
        assertEquals(ConsoleCommands.Type.INFO, command.type());
        assertEquals("Bed rock Two", command.argument(), "The remaining arguments form the player name");
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"info"}).type(),
                "info without a name is a usage error");
    }

    @Test
    void unknownSubcommandsAndWrongArgumentsAreUsageErrors() {
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"frobnicate"}).type());
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"status", "extra"}).type());
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"list", "extra"}).type());
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"accounts", "extra"}).type());
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"links", "extra"}).type());
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"version", "extra"}).type());
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"help", "extra"}).type());
    }

    @Test
    void subcommandAndSwitchValueCaseIsInsensitive() {
        assertEquals(ConsoleCommands.Type.STATUS, ConsoleCommands.parse("cp", new String[]{"STATUS"}).type());
        assertEquals("off", ConsoleCommands.parse("cp", new String[]{"Debug", "Off"}).argument());
    }

    @Test
    void infoCommandKeepsTheRawName() {
        final ConsoleCommands.Command command = ConsoleCommands.parse("cp", new String[]{"info", "Player 1"});
        assertEquals("Player 1", command.argument());
        assertNull(ConsoleCommands.parse("cp", new String[]{"status"}).argument(), "Commands without an argument carry null");
    }


    // ---- access list subtree (task 5) ---------------------------------------

    @Test
    void listAliasesAndRootsAllParse() {
        for (final String root : new String[]{"cp", "connectplus", "/cp"}) {
            for (final String list : new String[]{"whitelist", "wl", "blacklist", "bl"}) {
                final ConsoleCommands.Command command = ConsoleCommands.parse(root, new String[]{list});
                assertEquals(ConsoleCommands.Type.ACCESS_LIST, command.type(), list + " must parse");
                assertNotNull(command.access(), "the structured access parameters ride along");
                assertEquals(list.startsWith("blacklist") || list.equals("bl")
                                ? AccessCommand.Kind.BLACKLIST : AccessCommand.Kind.WHITELIST,
                        command.access().list());
                assertEquals(AccessCommand.Action.HELP, command.access().action(),
                        "a bare list subcommand behaves like help");
            }
        }
    }

    @Test
    void everyListActionParses() {
        assertEquals(AccessCommand.Action.ON, ConsoleCommands.parse("cp",
                new String[]{"wl", "on"}).access().action());
        assertEquals(AccessCommand.Action.OFF, ConsoleCommands.parse("cp",
                new String[]{"bl", "off"}).access().action());
        assertEquals(AccessCommand.Action.LIST, ConsoleCommands.parse("cp",
                new String[]{"whitelist", "list"}).access().action());
        assertEquals(AccessCommand.Action.RELOAD, ConsoleCommands.parse("cp",
                new String[]{"blacklist", "reload"}).access().action());
    }

    @Test
    void addAndRemoveForceThePlatformAndTakeTheRestAsTarget() {
        final ConsoleCommands.Command java = ConsoleCommands.parse("cp",
                new String[]{"wl", "add", "java", "00000000-0000-4000-8000-000000000001"});
        assertEquals(AccessCommand.Action.ADD, java.access().action());
        assertEquals(AccessKey.ClientType.JAVA, java.access().clientType());
        assertEquals("00000000-0000-4000-8000-000000000001", java.access().target());

        final ConsoleCommands.Command bedrock = ConsoleCommands.parse("cp",
                new String[]{"bl", "remove", "bedrock", "Bedrock", "Player"});
        assertEquals(AccessCommand.Action.REMOVE, bedrock.access().action());
        assertEquals(AccessKey.ClientType.BEDROCK, bedrock.access().clientType());
        assertEquals("Bedrock Player", bedrock.access().target(), "the target keeps spaces verbatim");
    }

    @Test
    void missingPlatformOrTargetParsesToUsage() {
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp",
                new String[]{"wl", "add", "java"}).type(), "a missing target is usage");
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp",
                new String[]{"wl", "add", "Bedrock Player"}).type(), "a missing platform is usage");
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp",
                new String[]{"wl", "add", "console", "x"}).type(), "an unknown platform is usage");
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp",
                new String[]{"wl", "on", "extra"}).type());
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp",
                new String[]{"wl", "nonsense"}).type());
    }

    @Test
    void accessCheckParsesWithTheRemainingTarget() {
        final ConsoleCommands.Command command = ConsoleCommands.parse("cp",
                new String[]{"access", "check", "2530000000000001"});
        assertEquals(ConsoleCommands.Type.ACCESS_CHECK, command.type());
        assertEquals("2530000000000001", command.argument());

        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp", new String[]{"access"}).type());
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp",
                new String[]{"access", "check"}).type(), "check without a target is usage");
        assertEquals(ConsoleCommands.Type.USAGE, ConsoleCommands.parse("cp",
                new String[]{"access", "nonsense"}).type());
    }

    @Test
    void legacyTwoArgumentCommandConstructorStillWorks() {
        final ConsoleCommands.Command legacy = new ConsoleCommands.Command(ConsoleCommands.Type.INFO, "Someone");
        assertEquals(ConsoleCommands.Type.INFO, legacy.type());
        assertEquals("Someone", legacy.argument());
        assertNull(legacy.access());
    }
}
