package dev.connectplus.compat;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViaProxyCompatTest {

    /** The optional JAR must be an unmodified official host, never a generated host stub. */
    private static URLClassLoader runtimeLoader() throws Exception {
        final List<URL> urls = new ArrayList<>();
        final String runtimeJar = System.getProperty("connectplus.test.viaproxyJar");
        if (runtimeJar != null) {
            urls.add(Path.of(runtimeJar).toUri().toURL());
        } else {
            urls.add(net.raphimc.viaproxy.ViaProxy.class.getProtectionDomain().getCodeSource().getLocation());
        }
        urls.add(ViaProxyCompat.class.getProtectionDomain().getCodeSource().getLocation());
        for (ClassLoader loader = ViaProxyCompatTest.class.getClassLoader();
             loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urlLoader) {
                urls.addAll(List.of(urlLoader.getURLs()));
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    @Test
    void readsVersionFromTheOfficialHostLoadedAtRuntime() throws Exception {
        try (URLClassLoader loader = runtimeLoader()) {
            final String actualHostVersion = (String) loader.loadClass("net.raphimc.viaproxy.ViaProxy")
                    .getField("VERSION").get(null);
            final Object reportedVersion = loader.loadClass(ViaProxyCompat.class.getName())
                    .getMethod("runningVersion").invoke(null);
            assertEquals(actualHostVersion, reportedVersion,
                    "the running host version must not be the inlined compile dependency version");
        }
    }

    @Test
    void registersTheActualHostVersionAndRejectsADifferentVersion() throws Exception {
        try (URLClassLoader loader = runtimeLoader()) {
            final String actualHostVersion = (String) loader.loadClass("net.raphimc.viaproxy.ViaProxy")
                    .getField("VERSION").get(null);
            final Class<?> compat = loader.loadClass(ViaProxyCompat.class.getName());
            final Supplier<String> versionSupplier = () -> {
                try {
                    return (String) compat.getMethod("runningVersion").invoke(null);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            };
            loader.loadClass("dev.connectplus.config.CPConfig$GeyserSupport")
                    .getField("enabled").setBoolean(null, true);
            final Class<?> endpointType = loader.loadClass("dev.connectplus.bridge.BedrockBridgeEndpoint");
            final Object endpoint = endpointType.getConstructor(Supplier.class).newInstance(versionSupplier);
            final var register = endpointType.getMethod("register", Map.class, Function.class);
            final Function<Map<String, Object>, CompletionStage<Map<String, Object>>> handler =
                    request -> CompletableFuture.completedFuture(Map.of());
            final Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("protocolVersion", 1);
            descriptor.put("providerId", "connectplus-geyser-bridge");
            descriptor.put("providerEpoch", UUID.randomUUID().toString());
            descriptor.put("bridgeVersion", "1.0.1");
            descriptor.put("geyserVersion", "2.11.3");
            descriptor.put("capabilities", List.of("verified-xuid", "exact-channel-binding", "targeted-disconnect"));
            descriptor.put("viaproxyVersion", "0.0.0");
            final Map<?, ?> rejected = (Map<?, ?>) register.invoke(endpoint, descriptor, handler);
            assertEquals("REJECTED", rejected.get("status"));
            assertEquals("UNSUPPORTED_RUNTIME", rejected.get("reasonCode"));

            descriptor.put("viaproxyVersion", actualHostVersion);
            final Map<?, ?> accepted = (Map<?, ?>) register.invoke(endpoint, descriptor, handler);
            assertEquals("REGISTERED", accepted.get("status"), () -> "registration result: " + accepted);
        }
    }
}
