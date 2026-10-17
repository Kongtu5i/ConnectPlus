package dev.connectplus.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GeyserClientVersionTest {
    @TempDir Path directory;
    private static final UUID WIRE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** An isolated optional plugin API, exercising reflection without bundling Geyser classes in the test runtime. */
    private URLClassLoader geyserApi() throws Exception {
        final Path root = directory.resolve("org/geysermc/geyser/api");
        Files.createDirectories(root.resolve("connection"));
        final Path connection = root.resolve("connection/GeyserConnection.java");
        Files.writeString(connection, """
                package org.geysermc.geyser.api.connection;
                public interface GeyserConnection {
                    java.util.UUID javaUuid();
                    String version();
                }
                """);
        final Path api = root.resolve("GeyserApi.java");
        Files.writeString(api, """
                package org.geysermc.geyser.api;
                import java.util.UUID;
                import org.geysermc.geyser.api.connection.GeyserConnection;
                public interface GeyserApi {
                    static GeyserApi api() {
                        return new GeyserApi() {
                            public GeyserConnection connectionByXuid(String xuid) {
                                return "2535429616021489".equals(xuid) ? player() : null;
                            }
                            public GeyserConnection connectionByUuid(UUID uuid) {
                                return player().javaUuid().equals(uuid) ? player() : null;
                            }
                        };
                    }
                    GeyserConnection connectionByXuid(String xuid);
                    GeyserConnection connectionByUuid(UUID uuid);
                    static GeyserConnection player() {
                        return new GeyserConnection() {
                            public UUID javaUuid() { return UUID.fromString("00000000-0000-0000-0000-000000000001"); }
                            public String version() { return "1.21.50"; }
                        };
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", directory.toString(), api.toString(), connection.toString()));
        return new URLClassLoader(new URL[]{directory.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    @Test
    void readsOriginalVersionThroughTheOptionalPluginClassLoader() throws Exception {
        try (final URLClassLoader loader = this.geyserApi()) {
            assertEquals("1.21.50", ViaProxyCompat.bedrockClientVersion(loader, WIRE_UUID, "2535429616021489"));
            assertEquals("1.21.50", ViaProxyCompat.bedrockClientVersion(loader, WIRE_UUID, null));
            assertNull(ViaProxyCompat.bedrockClientVersion(loader, UUID.randomUUID(), "2535429616021489"),
                    "A connection with the same XUID but different wire UUID must not supply its version");
            assertNull(ViaProxyCompat.bedrockClientVersion(loader, WIRE_UUID, "1111111111111111"));
        }
    }

    @Test
    void missingGeyserApiDoesNotFailAnOnlinePlayerQuery() {
        assertNull(ViaProxyCompat.bedrockClientVersion(ClassLoader.getPlatformClassLoader(), WIRE_UUID, null));
    }
}
