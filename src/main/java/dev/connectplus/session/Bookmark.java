package dev.connectplus.session;

import javax.annotation.Nullable;

/**
 * One saved server bookmark. Mutable so Gson can deserialize it directly;
 * {@code versionName} is the {@link com.viaversion.viaversion.api.protocol.version.ProtocolVersion#getName()}
 * string or null for auto detect.
 */
public class Bookmark {

    public String name;
    public String address;
    @Nullable
    public String versionName;
    public long createdAt;
    public long lastConnectedAt;

    public Bookmark(final String name, final String address, @Nullable final String versionName, final long createdAt, final long lastConnectedAt) {
        this.name = name;
        this.address = address;
        this.versionName = versionName;
        this.createdAt = createdAt;
        this.lastConnectedAt = lastConnectedAt;
    }

}
