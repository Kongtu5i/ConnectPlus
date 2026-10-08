package dev.connectplus.lobby.screen;

import dev.connectplus.config.CPConfig;
import net.lenni0451.mcstructs.text.TextComponent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    @Test
    void formatSplitsLinesAndReplacesPlaceholders() {
        final TextComponent[] components = Messages.format("§aHi {0}\n§cLine2", "X");

        assertEquals(2, components.length);
        assertTrue(components[0].asUnformattedString().contains("Hi X"),
                "first line must contain the replaced argument, got: " + components[0].asUnformattedString());
        assertEquals("Line2", components[1].asUnformattedString());
    }

    @Test
    void formatKeepsUnknownPlaceholdersLiteral() {
        final TextComponent[] components = Messages.format("no args {5} here");

        assertEquals(1, components.length);
        assertEquals("no args {5} here", components[0].asUnformattedString());
    }

    @Test
    void formatKeepsArgumentClickEventsAndInheritsPlaceholderColor() {
        //The verification link arrives as a styled argument; its click event must
        //survive the placeholder replacement while the placeholder color carries over
        final TextComponent link =
                new net.lenni0451.mcstructs.text.components.StringComponent("https://login.example")
                        .setStyle(new net.lenni0451.mcstructs.text.Style()
                                .setClickEvent(new net.lenni0451.mcstructs.text.events.click.types.CopyToClipboardClickEvent("https://login.example")));
        final TextComponent[] components = Messages.format("§9Open {0} now", link);

        assertEquals(1, components.length);
        assertTrue(components[0].asUnformattedString().contains("https://login.example"));
        final TextComponent urlPart = findPart(components[0], "https://login.example");
        assertNotNull(urlPart, "The replaced URL must be a part of the component tree");
        final net.lenni0451.mcstructs.text.Style style = urlPart.getStyle();
        assertTrue(style.getClickEvent() instanceof net.lenni0451.mcstructs.text.events.click.types.CopyToClipboardClickEvent,
                "The argument's click event must not be stripped by format, got: " + style.getClickEvent());
        assertNotNull(style.getColor(), "The placeholder's color must still style the argument");
    }

    /**
     * Depth-first search for the leaf part containing the given text.
     */
    private static TextComponent findPart(final TextComponent root, final String text) {
        for (final TextComponent sibling : root.getSiblings()) {
            final TextComponent found = findPart(sibling, text);
            if (found != null) {
                return found;
            }
        }
        return root.asUnformattedString().contains(text) ? root : null;
    }

    @Test
    void autoModeResolvesFromClientLocale() {
        withLanguage("auto", () -> {
            assertEquals(Lang.ZH, Lang.of("zh_cn"));
            assertEquals(Lang.ZH, Lang.of("zh_TW"));
            assertEquals(Lang.ZH, Lang.of("ZH_CN"));
            assertEquals(Lang.EN, Lang.of("en_us"));
            assertEquals(Lang.EN, Lang.of("en_US"));
            assertEquals(Lang.EN, Lang.of("de_de"), "a locale without a pack falls back to English");
            assertEquals(Lang.EN, Lang.of((String) null));
            assertEquals(Lang.EN, Lang.of((dev.connectplus.session.PlayerSession) null));
        });
    }

    @Test
    void concreteConfiguredLanguageIsForcedForEveryLocale() {
        withLanguage("zh", () -> {
            assertEquals(Lang.ZH, Lang.of("zh_cn"));
            assertEquals(Lang.ZH, Lang.of("en_us"));
            assertEquals(Lang.ZH, Lang.of((String) null));
        });
        withLanguage("en", () -> {
            assertEquals(Lang.EN, Lang.of("zh_cn"));
            assertEquals(Lang.EN, Lang.of("en_us"));
        });
    }

    @Test
    void unknownConfiguredLanguageFallsBackToEnglish() {
        withLanguage("does_not_exist", () -> {
            assertEquals(Lang.EN, Lang.of("zh_cn"));
        });
    }

    @Test
    void customPackBecomesSelectable() {
        Languages.resetForTests();
        try {
            withLanguage("xx", () -> {
                //Without a pack the unknown code falls back to English
                assertEquals(Lang.EN, Lang.of("en_us"));
                final Map<String, String> pack = Map.of("Commands.Suggestions", "suggestions in xx");
                Languages.putPackForTests("xx", pack);
                assertEquals(Lang.ofCode("xx"), Lang.of("en_us"));
                assertEquals("suggestions in xx", Languages.text(Lang.of("en_us"), Messages.Commands.Suggestions));
                //Missing lines fall back to the built-in English default
                assertEquals(Messages.Commands.DisconnectUsage.english(), Languages.text(Lang.of("en_us"), Messages.Commands.DisconnectUsage));
            });
        } finally {
            Languages.resetForTests();
        }
    }

    @Test
    void everyMsgKeyMatchesItsFieldPath() {
        //A language file addresses a line by the dotted path of its field; a key
        //that drifted from the field path would silently never resolve
        final List<String> broken = new ArrayList<>();
        int checked = 0;
        final Set<String> seenKeys = new HashSet<>();
        for (final Map.Entry<String, Field> entry : msgFields().entrySet()) {
            final Field field = entry.getValue();
            final Msg msg;
            try {
                msg = (Msg) field.get(null);
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            checked++;
            if (!entry.getKey().equals(msg.key())) {
                broken.add(msg.key() + " should be " + entry.getKey());
            }
            if (msg.english() == null || msg.english().isBlank()) {
                broken.add(msg.key() + " (blank English default)");
            }
            if (!seenKeys.add(msg.key())) {
                broken.add(msg.key() + " (duplicate key)");
            }
        }
        assertTrue(checked >= 60, "The reflection walk must cover the whole pack, found only " + checked + " Msg fields");
        assertTrue(broken.isEmpty(), "Broken Msg keys: " + broken);
    }

    @Test
    void chineseMirrorPackCoversEveryOptionField() {
        //Walk every English Msg field and require a non-blank static String at the
        //same path in the Zh mirror pack
        final List<String> missing = new ArrayList<>();
        final Map<String, String> zh = Languages.builtInChinese();
        int checked = 0;
        for (final Map.Entry<String, Field> entry : msgFields().entrySet()) {
            final Msg msg;
            try {
                msg = (Msg) entry.getValue().get(null);
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            checked++;
            final String zhText = zh.get(msg.key());
            if (zhText == null) {
                missing.add(msg.key() + " (missing)");
            } else if (zhText.isBlank()) {
                missing.add(msg.key() + " (blank)");
            }
        }
        assertTrue(checked >= 60, "The reflection walk must cover the whole pack, found only " + checked + " Msg fields");
        assertTrue(missing.isEmpty(), "Missing Chinese translations: " + missing);
        //The built-in packs resolve the documented built-in pair
        assertEquals(Messages.Commands.SwitchFailedGeneric.english(), Languages.text("en", Messages.Commands.SwitchFailedGeneric));
        assertEquals(Messages.Zh.Commands.SwitchFailedGeneric, Languages.text("zh", Messages.Commands.SwitchFailedGeneric));
    }

    @Test
    void textResolvesLanguageFilesOverBuiltins() {
        Languages.resetForTests();
        try {
            //zh resolves the built-in mirror even without a zh pack loaded
            assertEquals(Messages.Zh.Commands.SwitchBusy, Languages.text("zh", Messages.Commands.SwitchBusy));
            //en resolves the built-in English default without a pack
            assertEquals(Messages.Commands.SwitchBusy.english(), Languages.text("en", Messages.Commands.SwitchBusy));
            //an unknown code falls back to English
            assertEquals(Messages.Commands.SwitchBusy.english(), Languages.text("yy", Messages.Commands.SwitchBusy));
            //a loaded pack line wins over the built-in
            Languages.putPackForTests("zh", Map.of(Messages.Commands.SwitchBusy.key(), "pack line"));
            assertEquals("pack line", Languages.text("zh", Messages.Commands.SwitchBusy));
            //a blank pack line falls through to the built-in
            Languages.putPackForTests("zh", Map.of(Messages.Commands.SwitchBusy.key(), " "));
            assertEquals(Messages.Zh.Commands.SwitchBusy, Languages.text("zh", Messages.Commands.SwitchBusy));
        } finally {
            Languages.resetForTests();
        }
    }

    @Test
    void connectResultMessageMapsEveryOutcome() {
        //STARTED means "nothing to say"
        assertNotNull(Messages.Commands.connectResultMessage(Lang.EN, dev.connectplus.switching.SwitchInitiator.StartResult.NOT_AVAILABLE));
        assertNotNull(Messages.Commands.connectResultMessage(Lang.EN, dev.connectplus.switching.SwitchInitiator.StartResult.REJECTED_BUSY));
        assertNotNull(Messages.Commands.connectResultMessage(Lang.EN, dev.connectplus.switching.SwitchInitiator.StartResult.REJECTED_RATE_LIMITED));
        assertEquals(Languages.text("en", Messages.Commands.SwitchUnavailable),
                Messages.Commands.connectResultMessage(Lang.EN, dev.connectplus.switching.SwitchInitiator.StartResult.NOT_AVAILABLE));
        assertEquals(Messages.Zh.Commands.SwitchBusy,
                Messages.Commands.connectResultMessage(Lang.ZH, dev.connectplus.switching.SwitchInitiator.StartResult.REJECTED_BUSY));
    }

    /**
     * All static {@link Msg} fields of {@link Messages}, keyed by their dotted
     * field path (e.g. "MainScreen.SetServerAddress.ItemName").
     */
    private static Map<String, Field> msgFields() {
        final Map<String, Field> fields = new java.util.LinkedHashMap<>();
        collect(Messages.class, "", fields);
        return fields;
    }

    private static void collect(final Class<?> holder, final String prefix, final Map<String, Field> into) {
        for (final Class<?> nested : holder.getDeclaredClasses()) {
            collect(nested, prefix + nested.getSimpleName() + ".", into);
        }
        for (final Field field : holder.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Msg.class) {
                into.put(prefix + field.getName(), field);
            }
        }
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
}
