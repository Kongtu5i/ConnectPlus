package dev.connectplus.accounts;

import java.util.UUID;

/**
 * A stored Minecraft account, wrapping ViaProxy's account model. The wrapper
 * lives in the compat layer; everything outside it only sees this interface.
 */
public interface CPAccount {

    /**
     * The human-readable display string, e.g. {@code Steve (Microsoft)}.
     */
    String displayName();

    /**
     * The account's actual Minecraft profile name (no decorative suffix), or
     * null when unknown. The default keeps old implementations (test stubs)
     * compiling; the production adapter reads the real profile name.
     */
    default String profileName() {
        return null;
    }

    /**
     * The Minecraft profile UUID.
     */
    UUID uuid();

    /**
     * Whether the cached Minecraft token is expired and the account needs a
     * (re-)login before use.
     */
    boolean isExpired();

    /**
     * Serializes the account's token chain; the only representation ever
     * written to disk (encrypted by TokenStore). Contains no password.
     */
    String toJson();

}
