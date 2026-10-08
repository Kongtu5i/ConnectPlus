package dev.connectplus.testutil;

import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.protocoltranslator.viaproxy.ViaProxyConfig;

import java.io.File;
import java.lang.reflect.Field;

/**
 * Boots just enough ViaProxy static state for tests that run real ProxyConnection /
 * PacketHandler code outside the ViaProxy runtime: ProxyConnection.connectToServer reads
 * the connect timeout from {@code ViaProxy.getConfig()} and CompressionPacketHandler
 * reads the compression threshold. The config instance is injected into the private
 * static CONFIG field via reflection; no ViaProxy runtime (event manager, Via platform,
 * proxy server) is started and no translation happens.
 */
public final class ViaProxyTestConfig {

    private static boolean initialized;

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        try {
            final File configDir = java.nio.file.Files.createTempDirectory("connectplus-test-config").toFile();
            configDir.deleteOnExit();
            final ViaProxyConfig config = ViaProxyConfig.create(new File(configDir, "viaproxy.yml"));
            final Field field = ViaProxy.class.getDeclaredField("CONFIG");
            field.setAccessible(true);
            field.set(null, config);
            initialized = true;
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to initialize the ViaProxy test config", e);
        }
    }

    private ViaProxyTestConfig() {
    }
}
