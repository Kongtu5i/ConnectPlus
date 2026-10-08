package dev.connectplus.commands;

/**
 * Pure parser for the lobby player commands. Players keep exactly two commands:
 * {@code /disconnect} and {@code /dc}. Parsing never touches a session or a
 * channel, and the result carries only the command kind — the executor supplies
 * all behavior (disconnect flow, usage reply) itself.
 *
 * <p>Every other input (removed commands like {@code /connect}, plain chat,
 * unknown commands) parses to {@link Type#NONE} so the lobby ignores it and a
 * target server receives it untouched. Extra arguments on an exit command parse
 * to {@link Type#USAGE} so the lobby can answer with the usage line.</p>
 */
public final class LobbyCommands {

    public static final java.util.List<String> EXIT_NAMES = java.util.List.of("disconnect", "dc");

    public enum Type {
        DISCONNECT, USAGE, NONE
    }

    /**
     * @param type the command kind; the only information a command carries
     */
    public record Command(Type type) {
    }

    public static final Command NONE = new Command(Type.NONE);
    public static final Command DISCONNECT = new Command(Type.DISCONNECT);
    public static final Command USAGE = new Command(Type.USAGE);

    private LobbyCommands() {
    }

    /**
     * Parses one chat/command input line into a {@link Command}.
     */
    public static Command parse(final String input) {
        if (input == null) {
            return NONE;
        }
        final String trimmed = input.trim();
        if (!trimmed.startsWith("/")) {
            return NONE;
        }
        final String[] tokens = trimmed.substring(1).split("\\s+");
        return switch (tokens[0]) {
            case "disconnect", "dc" -> tokens.length == 1 ? DISCONNECT : USAGE;
            default -> NONE;
        };
    }

}
