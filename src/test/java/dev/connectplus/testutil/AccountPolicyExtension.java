package dev.connectplus.testutil;

import dev.connectplus.config.CPConfig;
import net.raphimc.viaproxy.ViaProxy;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Gives account-flow tests explicit policy opt-in and restores every global switch. */
public final class AccountPolicyExtension implements BeforeEachCallback, AfterEachCallback {
    private boolean online, login, geyser;

    @Override public void beforeEach(ExtensionContext context) {
        ViaProxyTestConfig.init();
        online = ViaProxy.getConfig().isProxyOnlineMode();
        login = CPConfig.allowAccountLogin;
        geyser = CPConfig.GeyserSupport.enabled;
        ViaProxy.getConfig().setProxyOnlineMode(true);
        CPConfig.allowAccountLogin = true;
        CPConfig.GeyserSupport.enabled = true;
    }

    @Override public void afterEach(ExtensionContext context) {
        ViaProxy.getConfig().setProxyOnlineMode(online);
        CPConfig.allowAccountLogin = login;
        CPConfig.GeyserSupport.enabled = geyser;
    }
}
