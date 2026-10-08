package dev.connectplus.session;

/** Width normalization for server addresses; ordinary international names remain intact. */
public final class ServerAddress {
    private ServerAddress() { }
    public static String normalize(final String input) {
        if (input == null) return null;
        final StringBuilder result = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            result.append(c >= '\uFF01' && c <= '\uFF5E' ? (char) (c - 0xFEE0)
                    : c == '\u3000' ? ' ' : c == '\u3002' ? '.' : c);
        }
        return result.toString().trim();
    }
}
