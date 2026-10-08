package dev.connectplus.access;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The rejection and disconnect log of the access lists: name, client type, the
 * available primary identifier, the entry/operation source and the exact
 * reason. A blacklist hit carries an explicit {@code [BLACKLIST]} warning
 * marker and is logged at ERROR level — the official ViaProxy console renders
 * ERROR messages in red through its own pattern layout, without any host log
 * configuration change and without embedding ANSI escapes into log files.
 *
 * <p>Names are sanitized: control characters (newlines, ANSI escapes) in a
 * display name can never fake a log line or a colour.</p>
 */
public final class AccessRejectionLog {

    /** The level of one log record (mirrors the logger levels the tests may observe). */
    public enum Severity { INFO, ERROR }

    private static volatile Consumer<LogRecord> sinkForTests;

    /** One formatted log record; the tests observe these instead of the real logger. */
    public record LogRecord(Severity severity, String line) {
    }

    private AccessRejectionLog() {
    }

    /** Records one rejection; BLACKLISTED goes out at ERROR (red on the official console). */
    public static void write(@javax.annotation.Nullable final AccessSubject subject,
                             final AccessPolicy.Decision decision,
                             final String source) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(source, "source");
        final Severity severity = decision.reason() == AccessPolicy.Reason.BLACKLISTED
                ? Severity.ERROR : Severity.INFO;
        final LogRecord record = new LogRecord(severity, format(subject, decision, source));
        emit(record);
    }

    public static void writeIdentifierLimitation(final AccessSubject subject) {
        emit(new LogRecord(Severity.INFO, "Access list check limited: source=connect, type=bedrock, name="
                + sanitize(subject.name()) + ", XUID unavailable; blacklist check skipped (whitelist off)"));
    }

    private static void emit(final LogRecord record) {
        final Consumer<LogRecord> sink = sinkForTests;
        if (sink != null) {
            sink.accept(record);
            return;
        }
        final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("ConnectPlus-Access");
        if (record.severity() == Severity.ERROR) {
            logger.error(record.line());
        } else {
            logger.info(record.line());
        }
    }

    /** The formatted log line (also the unit-test surface). */
    public static String format(final AccessSubject subject, final AccessPolicy.Decision decision,
                                final String source) {
        final String marker = decision.reason() == AccessPolicy.Reason.BLACKLISTED ? "[BLACKLIST] " : "";
        final String identifier = subject == null ? "pending (identity not resolved yet)"
                : subject.key() != null ? subject.key().id()
                : subject.clientType() == null ? "unavailable (platform unknown)"
                : "unavailable (no XUID for the confirmed platform)";
        final String type = subject == null ? "pending"
                : subject.clientType() == null ? "unknown"
                : subject.clientType().name().toLowerCase(java.util.Locale.ROOT);
        final String name = subject == null ? null : subject.name();
        return marker + "Access list rejected a connection: source=" + sanitize(source)
                + ", reason=" + decision.reason()
                + ", type=" + type
                + ", name=" + sanitize(String.valueOf(name))
                + ", id=" + sanitize(identifier);
    }

    /**
     * Neutralizes control characters (newline, carriage return, ANSI escapes and
     * friends) so a crafted display name can never forge log lines or colours.
     */
    static String sanitize(final String raw) {
        if (raw == null) {
            return "null";
        }
        final StringBuilder clean = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            clean.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        return clean.toString();
    }

    /** Installs a recording sink instead of the real logger; tests only. */
    public static void putSinkForTests(final Consumer<LogRecord> sink) {
        sinkForTests = Objects.requireNonNull(sink, "sink");
    }

    public static void resetForTests() {
        sinkForTests = null;
    }

}
