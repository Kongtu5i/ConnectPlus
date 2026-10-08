package dev.connectplus.commands;

import dev.connectplus.access.AccessKey;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * The structured parameters of one access-list console command (the subtree
 * after {@code cp whitelist|wl|blacklist|bl}). Fields that an action does not
 * use are null. Parsing never touches a store or the host.
 */
public record AccessCommand(@Nullable Kind list, Action action,
                            @Nullable AccessKey.ClientType clientType, @Nullable String target) {

    /** The two independent lists; every alias resolves to one of these. */
    public enum Kind { WHITELIST, BLACKLIST }

    public enum Action { HELP, ON, OFF, ADD, REMOVE, LIST, RELOAD }

    /** Maps the list token to its kind, or null when the token is not a list alias. */
    public static @Nullable Kind kindOfToken(final String token) {
        if (token == null) {
            return null;
        }
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "whitelist", "wl" -> Kind.WHITELIST;
            case "blacklist", "bl" -> Kind.BLACKLIST;
            default -> null;
        };
    }

    /**
     * Parses the arguments after the list token. Returns null for a usage error
     * (unknown action, missing platform or missing target).
     */
    public static @Nullable AccessCommand parse(final Kind kind, final List<String> rest) {
        if (rest.isEmpty() || "help".equals(rest.get(0).toLowerCase(Locale.ROOT))) {
            return new AccessCommand(kind, Action.HELP, null, null);
        }
        final String action = rest.get(0).toLowerCase(Locale.ROOT);
        final List<String> args = rest.subList(1, rest.size());
        return switch (action) {
            case "on" -> args.isEmpty() ? new AccessCommand(kind, Action.ON, null, null) : null;
            case "off" -> args.isEmpty() ? new AccessCommand(kind, Action.OFF, null, null) : null;
            case "list" -> args.isEmpty() ? new AccessCommand(kind, Action.LIST, null, null) : null;
            case "reload" -> args.isEmpty() ? new AccessCommand(kind, Action.RELOAD, null, null) : null;
            case "add", "remove" -> parseMutation(kind,
                    "add".equals(action) ? Action.ADD : Action.REMOVE, args);
            default -> null;
        };
    }

    private static @Nullable AccessCommand parseMutation(final Kind kind, final Action action, final List<String> args) {
        if (args.isEmpty()) {
            return null; // missing platform
        }
        final AccessKey.ClientType type = switch (args.get(0).toLowerCase(Locale.ROOT)) {
            case "java" -> AccessKey.ClientType.JAVA;
            case "bedrock" -> AccessKey.ClientType.BEDROCK;
            default -> null;
        };
        if (type == null || args.size() < 2) {
            return null; // unknown platform or missing target
        }
        return new AccessCommand(kind, action, type, String.join(" ", args.subList(1, args.size())));
    }
}
