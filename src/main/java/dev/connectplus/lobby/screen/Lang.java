package dev.connectplus.lobby.screen;

import dev.connectplus.session.PlayerSession;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The language of one player's lobby texts: a language code ("en", "zh", or a
 * custom pack from {@code plugins/ConnectPlus/languages/}). Resolution runs
 * through {@link Languages#forSession} against the configured
 * {@link dev.connectplus.config.CPConfig#language} — a concrete code is forced
 * for everyone, "auto" follows the client's settings locale. Instances are
 * interned per code, so equality is code equality.
 */
public final class Lang {

    public static final Lang EN = new Lang("en");
    public static final Lang ZH = new Lang("zh");

    private static final ConcurrentHashMap<String, Lang> INTERNED = new ConcurrentHashMap<>(Map.of("en", EN, "zh", ZH));

    private final String code;

    private Lang(final String code) {
        this.code = code;
    }

    public String code() {
        return this.code;
    }

    /**
     * The interned language for a code; null or blank falls back to English.
     */
    public static Lang ofCode(@Nullable final String code) {
        if (code == null || code.isBlank()) {
            return EN;
        }
        final String norm = code.trim().toLowerCase(Locale.ROOT);
        return norm.equals("en") ? EN : INTERNED.computeIfAbsent(norm, Lang::new);
    }

    /**
     * Resolves the language for a client settings locale ("en_us", "zh_cn",
     * ...) under the configured language mode; null falls back per config.
     */
    public static Lang of(@Nullable final String locale) {
        return Languages.forLocale(locale);
    }

    /**
     * Resolves the language of a player's session (see {@link #of(String)});
     * a session without a captured locale resolves per config too.
     */
    public static Lang of(@Nullable final PlayerSession session) {
        return Languages.forSession(session);
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof final Lang lang && this.code.equals(lang.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.code);
    }

    @Override
    public String toString() {
        return this.code;
    }

}
