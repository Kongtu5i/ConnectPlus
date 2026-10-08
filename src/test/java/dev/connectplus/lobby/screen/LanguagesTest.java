package dev.connectplus.lobby.screen;

import dev.connectplus.config.CPConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguagesTest {

    @TempDir
    File dataDir;

    @AfterEach
    void reset() {
        Languages.resetForTests();
    }

    @Test
    void initWritesBuiltInPacksAndLoadsThemBack() throws IOException {
        Languages.init(this.dataDir);

        final File en = new File(this.dataDir, "languages/en.yml");
        final File zh = new File(this.dataDir, "languages/zh.yml");
        assertTrue(en.isFile(), "en.yml must be written for user editing");
        assertTrue(zh.isFile(), "zh.yml must be written for user editing");
        //The written packs load back and resolve to the built-in texts
        assertEquals(Messages.Zh.Commands.SwitchBusy, Languages.text("zh", Messages.Commands.SwitchBusy));
        assertEquals(Messages.Commands.SwitchBusy.english(), Languages.text("en", Messages.Commands.SwitchBusy));
        assertTrue(Languages.available().contains("en"));
        assertTrue(Languages.available().contains("zh"));
    }

    @Test
    void editedPackLineWinsOverTheBuiltIn() throws IOException {
        final File dir = new File(this.dataDir, "languages");
        assertTrue(dir.mkdirs());
        Files.writeString(new File(dir, "en.yml").toPath(),
                "Commands:\n  SwitchBusy: \"§coverridden line\"\n", StandardCharsets.UTF_8);
        Languages.init(this.dataDir);

        assertEquals("§coverridden line", Languages.text("en", Messages.Commands.SwitchBusy));
        //Untouched lines still resolve from the built-in defaults
        assertEquals(Messages.Commands.SwitchUnavailable.english(), Languages.text("en", Messages.Commands.SwitchUnavailable));
    }

    @Test
    void customLanguageFileIsPickedUp() throws IOException {
        final File dir = new File(this.dataDir, "languages");
        assertTrue(dir.mkdirs());
        Files.writeString(new File(dir, "fr.yml").toPath(),
                "Commands:\n  SwitchBusy: \"§cun basculement est en cours\"\n", StandardCharsets.UTF_8);
        Languages.init(this.dataDir);

        assertTrue(Languages.available().contains("fr"));
        assertEquals("§cun basculement est en cours", Languages.text("fr", Messages.Commands.SwitchBusy));
        //Missing lines fall back to the built-in English default
        assertEquals(Messages.Commands.ConnectRateLimited.english(), Languages.text("fr", Messages.Commands.ConnectRateLimited));
        withLanguage("fr", () -> {
            assertEquals(Lang.ofCode("fr"), Lang.of("en_us"), "a forced custom code serves every player");
            assertEquals("§cun basculement est en cours", Languages.text(Lang.of("en_us"), Messages.Commands.SwitchBusy));
        });
    }

    @Test
    void autoModeMatchesCustomPackByLocale() throws IOException {
        final File dir = new File(this.dataDir, "languages");
        assertTrue(dir.mkdirs());
        Files.writeString(new File(dir, "de.yml").toPath(), "Commands:\n  SwitchBusy: \"wechsel läuft\"\n", StandardCharsets.UTF_8);
        Languages.init(this.dataDir);

        withLanguage("auto", () -> {
            assertEquals(Lang.ofCode("de"), Lang.of("de_de"), "auto picks a custom pack by the locale prefix");
            assertEquals(Lang.ZH, Lang.of("zh_CN"));
            assertEquals(Lang.EN, Lang.of("es_es"), "locales without a pack stay English");
            assertEquals("wechsel läuft", Languages.text(Lang.of("de_de"), Messages.Commands.SwitchBusy));
        });
    }

    @Test
    void legacyMessagesYmlMigratesToEnPack() throws IOException {
        Files.writeString(new File(this.dataDir, "messages.yml").toPath(),
                "MainScreen:\n  Title: \"§amigrated title\"\n", StandardCharsets.UTF_8);
        Languages.init(this.dataDir);

        assertTrue(new File(this.dataDir, "languages/en.yml").isFile(), "messages.yml must be migrated to languages/en.yml");
        assertEquals("§amigrated title", Languages.text("en", Messages.MainScreen.Title));
        //Lines the legacy file did not contain fall back to the built-ins
        assertEquals(Messages.Commands.SwitchBusy.english(), Languages.text("en", Messages.Commands.SwitchBusy));
    }

    @Test
    void malformedLanguageFileIsIgnored() throws IOException {
        final File dir = new File(this.dataDir, "languages");
        assertTrue(dir.mkdirs());
        Files.writeString(new File(dir, "broken.yml").toPath(), "Commands: [unclosed\n", StandardCharsets.UTF_8);
        Languages.init(this.dataDir);

        assertEquals(Messages.Commands.SwitchBusy.english(), Languages.text("broken", Messages.Commands.SwitchBusy),
                "an unparseable pack falls back to the built-in English default");
    }

    private static void withLanguage(final String language, final Runnable body) {
        final String original = CPConfig.language;
        try {
            CPConfig.language = language;
            body.run();
        } finally {
            CPConfig.language = original;
        }
    }

    @Test
    void languageUpgradePreservesExistingTranslations() throws IOException {
        Languages.init(this.dataDir);
        // Simulate an OLD en.yml: no AccessControl keys, one edited value.
        final File enFile = new File(new File(this.dataDir, "languages"), "en.yml");
        final String edited = "# Keep this operator comment\nHotbar:\n  MenuName: '§aMy custom menu'\n";
        java.nio.file.Files.writeString(enFile.toPath(), edited);

        Languages.init(this.dataDir);

        final String upgraded = java.nio.file.Files.readString(enFile.toPath());
        assertTrue(upgraded.startsWith(edited), "the original text and comments must survive byte-for-byte");
        assertTrue(upgraded.contains("My custom menu"), "the user value survives the upgrade");
        assertTrue(upgraded.contains("AccessControl"), "the missing keys are added");
        assertTrue(upgraded.contains("You are not on the whitelist."),
                "the added key carries the built-in English text");
        // Runtime resolution: the edited line wins, the added key resolves.
        // The replace kept the built-in colour prefix in front of the new text.
        assertEquals("§aMy custom menu", Languages.text("en", Messages.Hotbar.MenuName));
        assertEquals("You are not on the whitelist.",
                Languages.text("en", Messages.AccessControl.NotWhitelisted));
    }

    @Test
    void malformedBuiltinLanguagePacksRemainUntouched() throws IOException {
        final File dir = new File(this.dataDir, "languages");
        assertTrue(dir.mkdirs());
        final String original = "# operator translation, repair manually\nCommands: [unclosed\n";
        for (final String name : java.util.List.of("en.yml", "zh.yml")) {
            Files.writeString(new File(dir, name).toPath(), original, StandardCharsets.UTF_8);
        }
        Languages.init(this.dataDir);
        for (final String name : java.util.List.of("en.yml", "zh.yml")) {
            assertEquals(original, Files.readString(new File(dir, name).toPath()));
        }
        assertEquals(Messages.AccessControl.Blacklisted.english(),
                Languages.text("en", Messages.AccessControl.Blacklisted));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{Hotbar.MenuName: 'Custom menu'}\n",
            "Hotbar.MenuName: 'Custom menu'\n...\n"})
    void validYamlRootsThatCannotBeAppendedKeepTheirTranslations(String original) throws IOException {
        final File dir = new File(this.dataDir, "languages");
        assertTrue(dir.mkdirs());
        final var file = new File(dir, "en.yml").toPath();
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Languages.init(this.dataDir);
        assertEquals(original, Files.readString(file));
        assertEquals("Custom menu", Languages.text("en", Messages.Hotbar.MenuName));
        assertEquals(Messages.AccessControl.Blacklisted.english(),
                Languages.text("en", Messages.AccessControl.Blacklisted));
    }

    @Test
    void preLoginAutoWithoutLocaleFallsBackToEnglish() {
        withLanguage("auto", () -> {
            final Lang lang = Languages.forLocale(null);
            assertEquals(Lang.EN, lang);
            assertEquals("You are not on the whitelist.",
                    Languages.text(lang, Messages.AccessControl.NotWhitelisted));
        });
    }
}
