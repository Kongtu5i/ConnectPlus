package dev.connectplus.config;

import net.lenni0451.optconfig.ConfigLoader;
import net.lenni0451.optconfig.provider.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AccessConfigDefaultsTest {

    @TempDir
    Path dir;

    /**
     * Builds a "previous release" config.yml: everything the current class
     * writes except the two access-list switches, with the user's old values
     * applied. This is the exact upgrade scenario the targeted migration is
     * for (any older, smaller file still takes CoreMain's backup-and-regenerate
     * recovery path — pinned by ConfigUpgradeTest).
     */
    private Path previousReleaseConfig() throws IOException {
        final Path full = this.dir.resolve("full-current.yml");
        new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(full.toFile()));
        final String current = Files.readString(full, StandardCharsets.UTF_8);
        final String stripped = current
                .replaceAll("(?m)^# Whether the whitelist is enabled.*\\R?", "")
                .replaceAll("(?m)^# Whether the blacklist is enabled.*\\R?", "")
                .replaceAll("(?m)^whitelist:.*\\R?", "")
                .replaceAll("(?m)^blacklist:.*\\R?", "");
        final Path config = this.dir.resolve("config.yml");
        Files.writeString(config, stripped
                .replace("mode: lobby", "mode: proxy")
                .replace("language: en", "language: zh")
                .replace("switchTimeoutSeconds: 15", "switchTimeoutSeconds: 27"), StandardCharsets.UTF_8);
        CPConfig.mode = "lobby";
        CPConfig.language = "en";
        CPConfig.switchTimeoutSeconds = 15;
        CPConfig.whitelist = false;
        CPConfig.blacklist = false;
        return config;
    }

    /** loadStatic writes CPConfig's static fields; reset them so other tests see defaults. */
    private static void resetStaticConfig() {
        CPConfig.mode = "lobby";
        CPConfig.language = "en";
        CPConfig.switchTimeoutSeconds = 15;
        CPConfig.whitelist = false;
        CPConfig.blacklist = false;
    }

    @Test
    void addsMissingSwitchesAndPreservesOldValues() throws IOException {
        final Path config = this.previousReleaseConfig();

        assertTrue(AccessConfigDefaults.addMissingOptions(config), "the switches were actually added");

        // The old values and existing text survive; the two switches are present.
        final String updated = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(updated.contains("mode: proxy"));
        assertTrue(updated.contains("language: zh"));
        assertTrue(updated.contains("switchTimeoutSeconds: 27"));
        assertTrue(updated.contains("whitelist: false"));
        assertTrue(updated.contains("blacklist: false"));

        // The upgraded file loads with the user's values intact and the new switches present.
        try {
            new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(config.toFile()));
            assertEquals("proxy", CPConfig.mode);
            assertEquals("zh", CPConfig.language);
            assertEquals(27, CPConfig.switchTimeoutSeconds);
            assertFalse(CPConfig.whitelist);
            assertFalse(CPConfig.blacklist);
        } finally {
            resetStaticConfig();
        }
    }

    @Test
    void secondRunFindsNothingToAdd() throws IOException {
        final Path config = this.previousReleaseConfig();
        AccessConfigDefaults.addMissingOptions(config);
        final byte[] afterFirst = Files.readAllBytes(config);

        assertFalse(AccessConfigDefaults.addMissingOptions(config), "an upgraded config is left untouched");
        assertArrayEquals(afterFirst, Files.readAllBytes(config));
    }

    @Test
    void existingSwitchValuesAreNeverOverwritten() throws IOException {
        final Path config = this.previousReleaseConfig();
        Files.writeString(config, Files.readString(config, StandardCharsets.UTF_8)
                .replace("mode: proxy", "mode: proxy\nwhitelist: true"), StandardCharsets.UTF_8);

        assertTrue(AccessConfigDefaults.addMissingOptions(config), "only the missing blacklist is added");
        final String updated = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(updated.contains("whitelist: true"));
        assertTrue(updated.contains("blacklist: false"));
        try {
            new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(config.toFile()));
            assertTrue(CPConfig.whitelist);
        } finally {
            resetStaticConfig();
        }
    }

    @Test
    void missingConfigFileIsNotCreated() throws IOException {
        final Path config = this.dir.resolve("config.yml");
        assertFalse(AccessConfigDefaults.addMissingOptions(config));
        assertFalse(Files.exists(config), "first boot belongs to optconfig, not the migration");
    }

    @Test
    void oldConfigPlusMigrationLoadsWithoutTheMergerCrash() throws IOException {
        // Pins the task-3 fix: with the migration run before loadStatic, the
        // previous-release config must load cleanly instead of crashing the
        // DiffMerger and forcing the whole-file default regeneration.
        final Path config = this.previousReleaseConfig();
        AccessConfigDefaults.addMissingOptions(config);
        try {
            assertDoesNotThrow(() -> new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(config.toFile())));
            assertEquals("proxy", CPConfig.mode);
        } finally {
            resetStaticConfig();
        }
    }
}
