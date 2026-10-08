package dev.connectplus.config;

import net.lenni0451.optconfig.ConfigLoader;
import net.lenni0451.optconfig.provider.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M7 manual-verification finding: optconfig 1.1.1's DiffMerger crashes when an
 * older config.yml holds fewer options than the current config class (the normal
 * plugin upgrade path). The loader throws IndexOutOfBoundsException, which
 * CoreMain catches, backs the file up and regenerates defaults. This test pins
 * the crash precondition (so an optconfig upgrade that fixes the merger shows up
 * here) and the recovery contract CoreMain relies on.
 */
class ConfigUpgradeTest {

    @Test
    void addingMotdPreservesThePreviousConfigValues(@TempDir final Path dir) throws IOException {
        final File file = dir.resolve("config.yml").toFile();
        new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));
        final String current = Files.readString(file.toPath());
        final String previous = current.replaceAll("(?m)^# Message shown in the multiplayer.*\\R?", "")
                .replaceAll("(?m)^motd:.*\\R?", "")
                .replace("language: en", "language: zh")
                .replace("switchTimeoutSeconds: 15", "switchTimeoutSeconds: 27")
                .replace("blockLocalTargets: true", "blockLocalTargets: false");
        Files.writeString(file.toPath(), previous);
        try {
            new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));
            assertEquals("zh", CPConfig.language);
            assertEquals(27, CPConfig.switchTimeoutSeconds);
            assertEquals(false, CPConfig.blockLocalTargets);
            assertTrue(Files.readString(file.toPath()).contains("motd:"));
        } finally {
            CPConfig.language = "en";
            CPConfig.switchTimeoutSeconds = 15;
            CPConfig.blockLocalTargets = true;
        }
    }

    private static final String OLD_CONFIG = """
            # Plugin mode: lobby (route players into the built-in lobby, default) or proxy (lobby disabled, plain ViaProxy behaviour)
            mode: lobby

            # Maximum seconds a seamless server switch may take before the player is switched back to the lobby
            switchTimeoutSeconds: 15

            # Rate limit: maximum connect attempts per player per minute
            maxConnectAttemptsPerMinute: 30

            # Maximum number of saved bookmarks per player
            maxBookmarksPerPlayer: 50

            # How to react when a target server sends a transfer packet: confirm (back to lobby for confirmation), follow or ignore
            transferPolicy: confirm
            """;

    @Test
    void outdatedConfigCrashesTheMergerSoCoreMainMustRecover(@TempDir final Path dir) throws IOException {
        final File configFile = dir.resolve("config.yml").toFile();
        Files.writeString(configFile.toPath(), OLD_CONFIG);

        //Precondition: the optconfig merger really throws for this file (IndexOutOfBounds
        //on 1.1.1). If a future optconfig version merges cleanly, this test fails and
        //CoreMain's recovery becomes dead code that can be removed.
        assertThrows(RuntimeException.class,
                () -> new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(configFile)),
                "optconfig must still crash on outdated configs for the CoreMain recovery to matter");

        //Recovery contract: with the broken file moved away, a fresh load regenerates
        //defaults — this is what CoreMain does after backing the file up.
        assertTrue(configFile.renameTo(dir.resolve("config.yml.backup").toFile()),
                "the broken config must be movable (backup step)");
        assertDoesNotThrow(() -> new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(configFile)));
        assertEquals("lobby", CPConfig.mode, "defaults must be restored after recovery");
        final String regenerated = Files.readString(configFile.toPath());
        assertTrue(regenerated.contains("kickPolicy"), "the regenerated config must hold the new options");
    }

    private static void assertDoesNotThrow(final ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (final Exception e) {
            throw new AssertionError("Expected the load to succeed, got: " + e, e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
