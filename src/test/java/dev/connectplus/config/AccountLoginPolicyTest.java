package dev.connectplus.config;

import dev.connectplus.testutil.ViaProxyTestConfig;
import dev.connectplus.compat.AccountLoginPolicy;
import net.lenni0451.optconfig.ConfigContext;
import net.lenni0451.optconfig.ConfigLoader;
import net.lenni0451.optconfig.provider.ConfigProvider;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.events.ViaProxyLoadedEvent;
import net.raphimc.viaproxy.plugins.events.ProxyStartEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountLoginPolicyTest {
    @TempDir File dataDir;

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void offlineProxyWithGeyserKeepsTheAdminAccountLoginSetting(final boolean configuredLogin) throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final boolean oldGeyser = CPConfig.GeyserSupport.enabled;
        try {
            ViaProxy.getConfig().setProxyOnlineMode(false);
            CPConfig.allowAccountLogin = configuredLogin;
            CPConfig.GeyserSupport.enabled = true;
            final File file = new File(this.dataDir, "config.yml");
            final var context = new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));
            final var policy = new AccountLoginPolicy(context);
            policy.onViaProxyLoaded(new ViaProxyLoadedEvent());
            policy.onProxyStart(new ProxyStartEvent());
            assertEquals(configuredLogin, CPConfig.allowAccountLogin);
            final Map<?, ?> yaml = new Yaml().load(Files.readString(file.toPath()));
            assertEquals(configuredLogin, yaml.get("allowAccountLogin"));
        } finally {
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
            CPConfig.GeyserSupport.enabled = oldGeyser;
        }
    }

    @Test
    void guiModeChangeBeforeProxyStartAlsoPersistsDisabledLogin() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        try {
            CPConfig.allowAccountLogin = true;
            final File file = new File(this.dataDir, "config.yml");
            final var context = new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));
            final var policy = new AccountLoginPolicy(context);
            ViaProxy.getConfig().setProxyOnlineMode(true);
            policy.onViaProxyLoaded(new ViaProxyLoadedEvent());
            assertEquals(true, CPConfig.allowAccountLogin);
            ViaProxy.getConfig().setProxyOnlineMode(false);
            policy.onProxyStart(new ProxyStartEvent());
            assertEquals(false, CPConfig.allowAccountLogin);
            final Map<?, ?> yaml = new Yaml().load(Files.readString(file.toPath()));
            assertEquals(false, yaml.get("allowAccountLogin"));
        } finally {
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    @ParameterizedTest
    @CsvSource({"false,true,false", "false,false,false", "true,true,true", "true,false,false"})
    void proxyModeForcesLoginOffWithoutEnablingItOrChangingOtherOptions(
            final boolean proxyOnline, final boolean configuredLogin, final boolean expected) throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        try {
            CPConfig.allowAccountLogin = configuredLogin;
            final File file = new File(this.dataDir, "config.yml");
            final ConfigContext<CPConfig> context = new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));
            final String motd = CPConfig.motd;
            ViaProxy.getConfig().setProxyOnlineMode(proxyOnline);
            new AccountLoginPolicy(context).onViaProxyLoaded(new ViaProxyLoadedEvent());
            assertEquals(expected, CPConfig.allowAccountLogin);
            final Map<?, ?> yaml = new Yaml().load(Files.readString(file.toPath()));
            assertEquals(expected, yaml.get("allowAccountLogin"), "The enforced false must be written to config.yml");
            assertEquals(motd, yaml.get("motd"));
            assertEquals(expected, AccountLoginPolicy.isEnabled());
        } finally {
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
        }
    }

    @Test
    void accountOperationsFollowThePerConnectionTrustedIdentity() {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldGeyser = CPConfig.GeyserSupport.enabled;
        final var verifiedJava = dev.connectplus.identity.ClientIdentity.verifiedJava(
                UUID.randomUUID(), "Premium", UUID.randomUUID());
        final var verifiedBedrock = dev.connectplus.identity.ClientIdentity.verifiedBedrock(
                UUID.randomUUID(), "Bedrock", "4242424242424", UUID.randomUUID(), UUID.randomUUID().toString());
        final var unverified = dev.connectplus.identity.ClientIdentity.unverified(UUID.randomUUID(), "Nobody");
        final boolean oldLogin = CPConfig.allowAccountLogin;
        try {
            ViaProxy.getConfig().setProxyOnlineMode(true);
            CPConfig.GeyserSupport.enabled = true;
            CPConfig.allowAccountLogin = true;
            assertTrue(AccountLoginPolicy.isAllowedFor(verifiedJava),
                    "a really verified Java login may use account operations");
            assertTrue(AccountLoginPolicy.isAllowedFor(verifiedBedrock),
                    "a bridge-verified bedrock identity is trusted regardless of the global proxy mode");
            assertFalse(AccountLoginPolicy.isAllowedFor(unverified),
                    "an unverified connection must never reach account operations");
            assertFalse(AccountLoginPolicy.isAllowedFor(null), "no identity, no account operations");

            CPConfig.allowAccountLogin = false;
            assertFalse(AccountLoginPolicy.isAllowedFor(verifiedJava),
                    "the admin disable wins over every identity");
            assertFalse(AccountLoginPolicy.isAllowedFor(verifiedBedrock));
        } finally {
            CPConfig.allowAccountLogin = oldLogin;
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.GeyserSupport.enabled = oldGeyser;
        }
    }

    @Test
    void enablingGeyserSupportDoesNotShieldAccountLoginFromTheAdminDisable() throws Exception {
        ViaProxyTestConfig.init();
        final boolean oldProxy = ViaProxy.getConfig().isProxyOnlineMode();
        final boolean oldLogin = CPConfig.allowAccountLogin;
        final boolean oldGeyser = CPConfig.GeyserSupport.enabled;
        try {
            CPConfig.allowAccountLogin = false;
            CPConfig.GeyserSupport.enabled = true;
            final File file = new File(this.dataDir, "config.yml");
            final var context = new ConfigLoader<>(CPConfig.class).loadStatic(ConfigProvider.file(file));
            ViaProxy.getConfig().setProxyOnlineMode(false);
            new AccountLoginPolicy(context).onViaProxyLoaded(new ViaProxyLoadedEvent());
            assertEquals(false, CPConfig.allowAccountLogin,
                    "enabling bridge support must preserve the explicit admin disable");
            final Map<?, ?> yaml = new Yaml().load(Files.readString(file.toPath()));
            assertEquals(false, yaml.get("allowAccountLogin"));
        } finally {
            ViaProxy.getConfig().setProxyOnlineMode(oldProxy);
            CPConfig.allowAccountLogin = oldLogin;
            CPConfig.GeyserSupport.enabled = oldGeyser;
        }
    }
}
