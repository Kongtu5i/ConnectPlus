package dev.connectplus.session;

import java.util.UUID;

/**
 * The original wire (protocol) identity of the entry connection, captured
 * before any backend account can rewrite it: the uuid/name the login protocol
 * presented and the only identity allowed into outgoing protocol packets.
 * Never replaced by linking, and never used to select a profile file — the
 * profile owner is the {@link dev.connectplus.identity.ProfileKey} resolved
 * from the verified ClientIdentity (plan §2).
 */
public record PlayerIdentity(UUID uuid, String name) {
}
