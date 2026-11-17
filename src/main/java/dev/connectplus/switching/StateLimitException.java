package dev.connectplus.switching;

/** A valid stream exhausted the state budget; it must not be forwarded untracked. */
final class StateLimitException extends RuntimeException {
    StateLimitException() { super("Target exceeds CP client-state limit"); }
}
