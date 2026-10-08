package dev.connectplus.config;

import net.lenni0451.optconfig.ConfigLoader;
import net.lenni0451.optconfig.provider.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DebugConfigTest {
    @TempDir Path directory;

    @Test
    void aNewConfigDisablesDebugByDefault() throws Exception {
        final Path file = directory.resolve("config.yml");
        new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file.toFile()));
        assertTrue(Files.readString(file).matches("(?s).*\\bdebug: false\\b.*"),
                "A fresh config must expose the debug switch with a false default");
    }

    @Test
    void anUpgradeAddsDebugWithoutLosingTheOperatorsExistingSettings() throws Exception {
        final boolean oldDebug = CPConfig.debug;
        final String oldLanguage = CPConfig.language;
        final int oldTimeout = CPConfig.switchTimeoutSeconds;
        final boolean oldBlockLocal = CPConfig.blockLocalTargets;
        final Path file = directory.resolve("config.yml");
        try {
            CPConfig.debug = false;
            new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file.toFile()));
            String previous = Files.readString(file)
                    .replaceAll("(?m)^# Enable ConnectPlus DEBUG.*\\R?", "")
                    .replaceAll("(?m)^debug:.*\\R?", "")
                    .replace("language: en", "language: zh")
                    .replace("switchTimeoutSeconds: 15", "switchTimeoutSeconds: 27")
                    .replace("blockLocalTargets: true", "blockLocalTargets: false");
            Files.writeString(file, previous);
            new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file.toFile()));
            assertFalse(CPConfig.debug);
            assertEquals("zh", CPConfig.language);
            assertEquals(27, CPConfig.switchTimeoutSeconds);
            assertFalse(CPConfig.blockLocalTargets);
            assertTrue(Files.readString(file).contains("debug: false"));
        } finally {
            CPConfig.debug = oldDebug;
            CPConfig.language = oldLanguage;
            CPConfig.switchTimeoutSeconds = oldTimeout;
            CPConfig.blockLocalTargets = oldBlockLocal;
        }
    }

    @Test
    void bothBooleanValuesLoadFromConfig() throws Exception {
        final boolean oldDebug = CPConfig.debug;
        final Path file = directory.resolve("config.yml");
        try {
            CPConfig.debug = false;
            new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file.toFile()));
            Files.writeString(file, Files.readString(file).replace("debug: false", "debug: true"));
            new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file.toFile()));
            assertTrue(CPConfig.debug);
            Files.writeString(file, Files.readString(file).replace("debug: true", "debug: false"));
            new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file.toFile()));
            assertFalse(CPConfig.debug);
        } finally {
            CPConfig.debug = oldDebug;
        }
    }
}
