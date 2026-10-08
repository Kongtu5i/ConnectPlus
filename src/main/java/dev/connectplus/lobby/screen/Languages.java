package dev.connectplus.lobby.screen;

import dev.connectplus.CoreMain;
import dev.connectplus.config.CPConfig;
import dev.connectplus.session.PlayerSession;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The language pack registry (F1.4 rework). Built-in texts live in code — the
 * English defaults on the {@link Msg} fields and the Chinese mirror on
 * {@link Messages.Zh} — and both are additionally written to
 * {@code plugins/ConnectPlus/languages/en.yml} / {@code zh.yml} on first start
 * so they can be edited without recompiling. Every other {@code <code>.yml} in
 * that folder is picked up as a custom language: setting
 * {@code language: <code>} in config.yml serves it to every player, with
 * missing lines falling back to the built-in English default (Minecraft-style).
 *
 * <p>The legacy {@code messages.yml} English override file is migrated to
 * {@code languages/en.yml} once, when no en.yml exists yet.</p>
 *
 * <p>{@link CPConfig#language} decides who gets which language: a concrete
 * code ("en", "zh" or a custom one) serves that language to every player;
 * "auto" lets each client's locale pick among the available packs (exact code
 * match first, then the part before {@code _}), falling back to English.</p>
 *
 * <p>Unit tests run without init: {@link #init} with a null folder resets the
 * loaded packs and resolution falls back to the built-in texts only.</p>
 */
public final class Languages {

    private static volatile Map<String, Map<String, String>> packs = Map.of();
    private static volatile Map<String, String> builtInChinese;

    private Languages() {
    }

    /**
     * Loads every language file and writes the built-in packs that are missing
     * on disk. A null data folder (unit tests) just resets the loaded packs;
     * resolution then serves the built-in texts only.
     */
    public static synchronized void init(@Nullable final File dataFolder) {
        if (dataFolder == null) {
            packs = Map.of();
            return;
        }
        final File dir = new File(dataFolder, "languages");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            final File enFile = new File(dir, "en.yml");
            final File legacy = new File(dataFolder, "messages.yml");
            if (legacy.isFile() && !enFile.exists()) {
                Files.copy(legacy.toPath(), enFile.toPath());
                CoreMain.logger().info("Migrated legacy messages.yml to languages/en.yml; edit languages/en.yml to override English texts from now on.");
            }
            writeIfAbsent(enFile, nest(builtInEnglish()));
            writeIfAbsent(new File(dir, "zh.yml"), nest(builtInChinese()));
            // Task 7 upgrade: an EXISTING pack gains only the keys it is missing
            // (new built-ins), never overwriting a line the user already has.
            upgradeBuiltinPack(enFile, builtInEnglish());
            upgradeBuiltinPack(new File(dir, "zh.yml"), builtInChinese());

            final Map<String, Map<String, String>> loaded = new HashMap<>();
            final File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
            if (files != null) {
                for (final File file : files) {
                    final String code = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
                    loaded.put(code, loadPack(file));
                }
            }
            packs = Map.copyOf(loaded);
            warnIfConfiguredLanguageMissing();
        } catch (final Throwable t) {
            CoreMain.logger().error("Failed to load the language files; falling back to the built-in texts.", t);
            packs = Map.of();
        }
    }

    /**
     * The available language codes: every loaded pack plus the two built-ins.
     */
    public static java.util.Set<String> available() {
        final java.util.Set<String> codes = new java.util.TreeSet<>(packs.keySet());
        codes.add("en");
        codes.add("zh");
        return codes;
    }

    /**
     * Resolves a text for the given language code: the pack's line for the key
     * if the pack defines one, otherwise the built-in text (for zh the built-in
     * Chinese mirror sits between pack and English fallback).
     */
    public static String text(final String code, final Msg msg) {
        final Map<String, String> pack = code == null ? null : packs.get(code);
        if (pack != null) {
            final String line = pack.get(msg.key());
            if (line != null && !line.isBlank()) {
                return line;
            }
        }
        if ("zh".equals(code)) {
            final String zh = builtInChinese().get(msg.key());
            if (zh != null && !zh.isBlank()) {
                return zh;
            }
        }
        return msg.english();
    }

    /**
     * Convenience overload for call sites that carry a {@link Lang}.
     */
    public static String text(final Lang lang, final Msg msg) {
        return text(lang == null ? null : lang.code(), msg);
    }

    /**
     * The language of a session under the configured {@link CPConfig#language}:
     * a concrete code is served to everyone, "auto" follows the client locale.
     */
    public static Lang forSession(@Nullable final PlayerSession session) {
        return forLocale(session == null ? null : session.locale);
    }

    /**
     * The language for a client settings locale under the configured
     * {@link CPConfig#language} (see {@link #forSession}).
     */
    public static Lang forLocale(@Nullable final String locale) {
        final String configured = CPConfig.language == null || CPConfig.language.isBlank()
                ? "en" : CPConfig.language.trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(configured)) {
            if ("en".equals(configured)) {
                return Lang.EN;
            }
            if ("zh".equals(configured)) {
                return Lang.ZH;
            }
            if (packs.containsKey(configured)) {
                return Lang.ofCode(configured);
            }
            return Lang.EN; //an unknown code falls back to English (warned at init)
        }
        if (locale != null) {
            final String norm = locale.trim().toLowerCase(Locale.ROOT);
            if (hasCode(norm)) {
                return Lang.ofCode(norm);
            }
            final int underscore = norm.indexOf('_');
            if (underscore > 0 && hasCode(norm.substring(0, underscore))) {
                return Lang.ofCode(norm.substring(0, underscore));
            }
        }
        return Lang.EN;
    }

    private static boolean hasCode(final String code) {
        return packs.containsKey(code) || "en".equals(code) || "zh".equals(code);
    }

    private static void warnIfConfiguredLanguageMissing() {
        final String configured = CPConfig.language == null || CPConfig.language.isBlank()
                ? "en" : CPConfig.language.trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(configured) && !hasCode(configured)) {
            CoreMain.logger().warn("Configured language '{}' has no languages/{}.yml - players will see English. Available languages: {}",
                    configured, configured, available());
        }
    }

    /**
     * Reads one language file into a flat key -> text map. Both the nested
     * section structure (like messages.yml) and pre-flattened dotted keys are
     * accepted; an empty or unparseable file yields an empty pack.
     */
    private static Map<String, String> loadPack(final File file) throws IOException {
        final Map<String, String> flat = new HashMap<>();
        final Object root;
        try (final InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        } catch (final RuntimeException e) {
            CoreMain.logger().error("Language file {} is not valid YAML - its lines are ignored.", file.getName(), e);
            return flat;
        }
        if (root instanceof final Map<?, ?> map) {
            flatten(map, "", flat);
        }
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(final Map<?, ?> node, final String prefix, final Map<String, String> into) {
        for (final Map.Entry<?, ?> entry : node.entrySet()) {
            final String name = String.valueOf(entry.getKey());
            final String key = prefix.isEmpty() ? name : prefix + "." + name;
            final Object value = entry.getValue();
            if (value instanceof final Map<?, ?> nested && !nested.isEmpty()) {
                flatten(nested, key, into);
            } else if (value != null) {
                into.put(key, String.valueOf(value));
            }
        }
    }

    /**
     * Adds the built-in lines missing from an existing pack file (a plugin
     * upgrade introduced new keys). User-edited values stay byte-identical;
     * when nothing is missing the file is not rewritten.
     */
    private static void upgradeBuiltinPack(final File file, final Map<String, String> builtIn) throws IOException {
        if (!file.isFile()) {
            return;
        }
        final byte[] original = Files.readAllBytes(file.toPath());
        final Map<String, String> existing = new HashMap<>();
        try {
            final Object root = new Yaml().load(new String(original, StandardCharsets.UTF_8));
            if (root instanceof Map<?, ?> map) {
                flatten(map, "", existing);
            } else if (root != null) {
                return; // An invalid root must never be replaced during upgrade.
            }
        } catch (final RuntimeException invalidYaml) {
            return; // Runtime loading reports it; leave the user's repairable bytes intact.
        }
        final Map<String, String> missing = new TreeMap<>();
        for (final Map.Entry<String, String> entry : builtIn.entrySet()) {
            final String line = existing.get(entry.getKey());
            if (line == null) {
                missing.put(entry.getKey(), entry.getValue());
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        final File temp = new File(file.getParentFile(), file.getName() + ".tmp-" + System.nanoTime());
        // Dotted keys are already supported by loadPack. Append only missing keys,
        // preserving comments, quoting, line endings and translations in the prefix.
        final byte[] suffix = ("\n# Added ConnectPlus language defaults\n" + new Yaml(options).dump(missing))
                .getBytes(StandardCharsets.UTF_8);
        try {
            if (!(new Yaml().load(new String(original, StandardCharsets.UTF_8)
                    + new String(suffix, StandardCharsets.UTF_8)) instanceof Map<?, ?>)) return;
        } catch (final RuntimeException incompatibleDocument) {
            // Flow roots / explicit document endings cannot safely accept appended
            // keys. Runtime defaults still fill missing keys without rewriting it.
            return;
        }
        Files.write(temp.toPath(), original);
        Files.write(temp.toPath(), suffix, java.nio.file.StandardOpenOption.APPEND);
        // Files.move with REPLACE_EXISTING: File.renameTo does not overwrite an
        // existing target on Windows, which would fail every upgrade.
        try {
            java.nio.file.Files.move(temp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            Files.deleteIfExists(temp.toPath());
            throw new IOException("cannot replace " + file, e);
        }
        CoreMain.logger().info("Language pack {} was upgraded with {} missing built-in line(s); existing lines kept.",
                file.getName(), missing.size());
    }

    private static void writeIfAbsent(final File file, final Map<String, Object> content) throws IOException {
        if (file.exists()) {
            return;
        }
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try (final Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            new Yaml(options).dump(content, writer);
        }
    }

    /**
     * The built-in English texts, keyed by dotted key (a reflection walk over
     * the {@link Msg} fields).
     */
    static Map<String, String> builtInEnglish() {
        final Map<String, String> flat = new TreeMap<>();
        collectMsgFields(Messages.class, "", flat);
        return flat;
    }

    /**
     * The built-in Chinese mirror, keyed by dotted key (a reflection walk over
     * {@link Messages.Zh}); computed once.
     */
    static Map<String, String> builtInChinese() {
        Map<String, String> mirror = builtInChinese;
        if (mirror == null) {
            synchronized (Languages.class) {
                mirror = builtInChinese;
                if (mirror == null) {
                    final Map<String, String> flat = new TreeMap<>();
                    collectStringFields(Messages.Zh.class, "", flat);
                    builtInChinese = mirror = Map.copyOf(flat);
                }
            }
        }
        return mirror;
    }

    private static void collectMsgFields(final Class<?> holder, final String prefix, final Map<String, String> into) {
        for (final Class<?> nested : holder.getDeclaredClasses()) {
            collectMsgFields(nested, prefix + nested.getSimpleName() + ".", into);
        }
        for (final java.lang.reflect.Field field : holder.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == Msg.class) {
                try {
                    final Msg msg = (Msg) field.get(null);
                    into.put(msg.key(), msg.english());
                } catch (final IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static void collectStringFields(final Class<?> holder, final String prefix, final Map<String, String> into) {
        for (final Class<?> nested : holder.getDeclaredClasses()) {
            collectStringFields(nested, prefix + nested.getSimpleName() + ".", into);
        }
        for (final java.lang.reflect.Field field : holder.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    final String text = (String) field.get(null);
                    if (text != null) {
                        into.put(prefix + field.getName(), text);
                    }
                } catch (final IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    /**
     * Builds the nested section map for a language file out of flat dotted
     * keys ("a.b.c" becomes a -> b -> c).
     */
    static Map<String, Object> nest(final Map<String, String> flat) {
        final Map<String, Object> root = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : flat.entrySet()) {
            Map<String, Object> node = root;
            final String key = entry.getKey();
            int dot;
            int offset = 0;
            while ((dot = key.indexOf('.', offset)) >= 0) {
                node = (Map<String, Object>) node.computeIfAbsent(key.substring(offset, dot), k -> new LinkedHashMap<String, Object>());
                offset = dot + 1;
            }
            node.put(key.substring(offset), entry.getValue());
        }
        return root;
    }

    /**
     * Stores a pack for a code without touching disk; used by tests.
     */
    public static void putPackForTests(final String code, final Map<String, String> flat) {
        final Map<String, Map<String, String>> extended = new HashMap<>(packs);
        extended.put(code, Map.copyOf(flat));
        packs = Map.copyOf(extended);
    }

    public static void resetForTests() {
        packs = Map.of();
    }

}
