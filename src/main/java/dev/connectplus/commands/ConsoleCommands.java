package dev.connectplus.commands;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure parser for the console root command. The roots are {@code cp} and
 * {@code connectplus} with the optional leading slash the console user may
 * type ({@code /cp}, {@code /connectplus}). Parsing never touches a store or
 * the host: the result carries the subcommand kind and at most one raw string
 * argument (the info name with its spaces, or the debug switch value); the
 * service supplies all behavior.
 *
 * <p>An empty root shows the help; an unknown subcommand or a wrong argument
 * count/value parses to USAGE so the caller prints the usage lines. Nothing
 * here can execute anything by itself.</p>
 */
public final class ConsoleCommands {

    public enum Type {
        STATUS, LIST, LINKS, INFO, VERSION, DEBUG, ACCOUNTS, HELP, USAGE,
        /** The whitelist/blacklist management subtree ({@code cp whitelist ...}). */
        ACCESS_LIST,
        /** The read-only membership query ({@code cp access check <target>}). */
        ACCESS_CHECK
    }

    /**
     * @param type     the subcommand kind (or USAGE)
     * @param argument the raw info name (remaining arguments joined with single
     *                 spaces) or the debug value ("on"/"off", lowercased); null otherwise
     * @param access   the structured access-list parameters for ACCESS_LIST; null otherwise
     */
    public record Command(Type type, @Nullable String argument, @Nullable AccessCommand access) {

        /** The original two-argument form; access commands always use the three-argument one. */
        public Command(final Type type, final @Nullable String argument) {
            this(type, argument, null);
        }
    }

    private ConsoleCommands() {
    }

    /**
     * Whether the console token is a ConnectPlus root command. The optional
     * leading slash is accepted; matching is case-sensitive (console commands
     * are lowercase, mirroring the in-game command style).
     */
    public static boolean isRoot(final String token) {
        return token != null && matchesRoot(token);
    }

    private static boolean matchesRoot(final String token) {
        final String withoutSlash = token.startsWith("/") && token.length() > 1 ? token.substring(1) : token;
        return "cp".equals(withoutSlash) || "connectplus".equals(withoutSlash);
    }

    /**
     * Parses the arguments after the (already root-matched) command token.
     * Empty segments (the console split may produce them) are ignored.
     */
    public static Command parse(final String rootToken, final String[] rawArgs) {
        if (!matchesRoot(rootToken)) {
            return new Command(Type.USAGE, null);
        }
        final List<String> args = new ArrayList<>();
        if (rawArgs != null) {
            for (final String arg : rawArgs) {
                if (arg != null && !arg.isEmpty()) {
                    args.add(arg);
                }
            }
        }
        if (args.isEmpty()) {
            return new Command(Type.HELP, null);
        }
        final String sub = args.get(0).toLowerCase(Locale.ROOT);
        final List<String> rest = args.subList(1, args.size());
        return switch (sub) {
            case "status" -> noArgs(rest, Type.STATUS);
            case "list" -> noArgs(rest, Type.LIST);
            case "links" -> noArgs(rest, Type.LINKS);
            case "version" -> noArgs(rest, Type.VERSION);
            case "accounts" -> noArgs(rest, Type.ACCOUNTS);
            case "help" -> noArgs(rest, Type.HELP);
            case "info" -> rest.isEmpty() ? new Command(Type.USAGE, null)
                    : new Command(Type.INFO, String.join(" ", rest));
            case "debug" -> parseDebug(rest);
            case "whitelist", "wl" -> parseAccessList(AccessCommand.kindOfToken("whitelist"), rest);
            case "blacklist", "bl" -> parseAccessList(AccessCommand.kindOfToken("blacklist"), rest);
            case "access" -> parseAccessCheck(rest);
            default -> new Command(Type.USAGE, null);
        };
    }

    private static Command parseAccessList(final AccessCommand.Kind kind, final List<String> rest) {
        if (kind == null) {
            return new Command(Type.USAGE, null);
        }
        // rest already excludes the list token; AccessCommand parses the action subtree.
        final AccessCommand access = AccessCommand.parse(kind, rest);
        return access != null ? new Command(Type.ACCESS_LIST, null, access) : new Command(Type.USAGE, null);
    }

    private static Command parseAccessCheck(final List<String> rest) {
        if (rest.size() < 2 || !"check".equals(rest.get(0).toLowerCase(Locale.ROOT))) {
            return new Command(Type.USAGE, null);
        }
        return new Command(Type.ACCESS_CHECK, String.join(" ", rest.subList(1, rest.size())));
    }

    private static Command noArgs(final List<String> rest, final Type type) {
        return rest.isEmpty() ? new Command(type, null) : new Command(Type.USAGE, null);
    }

    private static Command parseDebug(final List<String> rest) {
        if (rest.size() != 1) {
            return new Command(Type.USAGE, null);
        }
        final String value = rest.get(0).toLowerCase(Locale.ROOT);
        if (!"on".equals(value) && !"off".equals(value)) {
            return new Command(Type.USAGE, null);
        }
        return new Command(Type.DEBUG, value);
    }

}
