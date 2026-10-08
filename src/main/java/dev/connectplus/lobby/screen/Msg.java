package dev.connectplus.lobby.screen;

/**
 * One user-facing text: its language-file key plus the built-in English
 * default. The key is the dotted path of the field ("Section.Sub.Name") and
 * mirrors the nested structure of the language files in
 * {@code plugins/ConnectPlus/languages/}; MessagesTest validates that every
 * key matches its field's actual class path, so a hand-written key cannot
 * drift. Resolution happens through {@link Languages#text}.
 */
public final class Msg {

    private final String key;
    private final String english;

    public Msg(final String key, final String english) {
        this.key = key;
        this.english = english;
    }

    public String key() {
        return this.key;
    }

    /**
     * The built-in English default; the last link of every resolution chain.
     */
    public String english() {
        return this.english;
    }

    @Override
    public String toString() {
        return this.english;
    }

}
