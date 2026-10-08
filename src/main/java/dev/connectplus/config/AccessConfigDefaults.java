package dev.connectplus.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The targeted upgrade for the two access-list switches: appends only the
 * missing {@code whitelist}/{@code blacklist} options to an existing config.yml
 * before optconfig loads it, so an older config never trips the optconfig 1.1.1
 * DiffMerger crash and the whole file is not regenerated over the user's values.
 * A config that already carries both keys is left byte-identical, and a missing
 * file belongs to optconfig's first boot, not to this migration.
 */
public final class AccessConfigDefaults {

    private static final String WHITELIST_BLOCK = """
            \n# Whether the whitelist is enabled at startup (console cp whitelist on/off changes the runtime state only; a restart restores this value; entries live in plugins/ConnectPlus/whitelist.json)
            whitelist: false
            """;

    private static final String BLACKLIST_BLOCK = """
            \n# Whether the blacklist is enabled at startup (console cp blacklist on/off changes the runtime state only; a restart restores this value; entries live in plugins/ConnectPlus/blacklist.json)
            blacklist: false
            """;

    private AccessConfigDefaults() {
    }

    /**
     * Adds the two access switches when missing; existing values and text stay
     * byte-identical. Returns whether the file was actually written.
     */
    public static boolean addMissingOptions(final Path config) throws IOException {
        if (!Files.exists(config)) {
            return false;
        }
        final String text = Files.readString(config, StandardCharsets.UTF_8);
        final boolean hasWhitelist = Pattern.compile("(?m)^whitelist\\s*:").matcher(text).find();
        final boolean hasBlacklist = Pattern.compile("(?m)^blacklist\\s*:").matcher(text).find();
        if (hasWhitelist && hasBlacklist) {
            return false;
        }
        final StringBuilder block = new StringBuilder();
        if (!hasWhitelist) {
            block.append(WHITELIST_BLOCK);
        }
        if (!hasBlacklist) {
            block.append(BLACKLIST_BLOCK);
        }
        // optconfig 1.1.1's DiffMerger matches options in class-declaration order,
        // so the new keys must go before the next declared option (debug) —
        // including its description comment — and not simply at the file end.
        final var insertion = Pattern.compile("(?m)^(?:#[^\\n]*\\n)*debug\\s*:").matcher(text);
        final String updated;
        if (insertion.find()) {
            updated = text.substring(0, insertion.start()) + block + text.substring(insertion.start());
        } else {
            final StringBuilder appended = new StringBuilder(text);
            if (!appended.toString().endsWith("\n")) {
                appended.append('\n');
            }
            updated = appended.append(block).toString();
        }
        Files.writeString(config, updated, StandardCharsets.UTF_8);
        return true;
    }
}
