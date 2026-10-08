package dev.connectplus.config;

import net.lenni0451.optconfig.ConfigLoader;
import net.lenni0451.optconfig.provider.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CPConfigTest {

    @TempDir
    File tempDir;

    @Test
    void loadsMultilineCustomMotdFromYaml() throws IOException {
        final File file = new File(tempDir, "config.yml");
        new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));
        final String original = CPConfig.motd;
        try {
            final String yaml = Files.readString(file.toPath()).replaceAll("(?m)^motd:.*$",
                    java.util.regex.Matcher.quoteReplacement("motd: \"§a我的大厅\\n§b欢迎连接\""));
            Files.writeString(file.toPath(), yaml);
            new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));
            assertEquals("§a我的大厅\n§b欢迎连接", CPConfig.motd);
        } finally {
            CPConfig.motd = original;
        }
    }

    @Test
    void loadsDefaultValuesFromEmptyFile() throws IOException {
        File file = new File(tempDir, "config.yml");
        new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));

        assertEquals("lobby", CPConfig.mode);
        assertEquals("§bConnectPlus Lobby", CPConfig.motd);
        assertEquals(15, CPConfig.switchTimeoutSeconds);
        assertEquals(30, CPConfig.maxConnectAttemptsPerMinute);
        assertEquals(50, CPConfig.maxBookmarksPerPlayer);
        assertFalse(CPConfig.whitelist, "the whitelist switch defaults to off");
        assertFalse(CPConfig.blacklist, "the blacklist switch defaults to off");
        assertEquals("confirm", CPConfig.transferPolicy);
        assertEquals("lobby", CPConfig.backendDownPolicy);
        assertEquals(3, CPConfig.reconnectAttempts);
        assertEquals(5, CPConfig.reconnectDelaySeconds);
        assertEquals("lobby", CPConfig.kickPolicy);
        assertTrue(CPConfig.accountLoginAllowlist.isEmpty(), "The account allowlist must default to empty (everyone)");
        assertFalse(CPConfig.GeyserSupport.enabled, "Geyser bridge support is opt-in and defaults to disabled");
    }

    @Test
    void geyserSupportOptionRoundTripsThroughTheConfigFile() throws IOException, IllegalAccessException {
        final File file = new File(tempDir, "config.yml");
        final var context = new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));
        final String original = Files.readString(file.toPath());
        assertTrue(original.contains("geyser-support:"), "the geyser-support section must be written to config.yml\n"
                + original);
        assertTrue(original.replaceAll("\\s", "").contains("enabled:false"),
                "the enabled option must live inside the geyser-support section\n" + original);

        CPConfig.GeyserSupport.enabled = true;
        try {
            context.save();
            new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));
            assertTrue(CPConfig.GeyserSupport.enabled, "a user-enabled bridge support must survive the round trip");
        } finally {
            CPConfig.GeyserSupport.enabled = false;
        }
    }
}
