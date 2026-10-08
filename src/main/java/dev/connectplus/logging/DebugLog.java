package dev.connectplus.logging;

import dev.connectplus.CoreMain;
import dev.connectplus.config.CPConfig;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/** Opt-in plugin diagnostics without changing any host logger configuration. */
public final class DebugLog {
    private static final Logger LOGGER = CoreMain.logger();

    /**
     * The console's runtime switch (`cp debug on|off`): overrides the config
     * value until restart, never written back to config.yml. Null = follow the
     * configuration.
     */
    private static volatile Boolean runtimeOverride;

    private DebugLog() {
    }

    /**
     * Flips the runtime switch; only meaningful for this run (no persistence,
     * no host logger configuration change).
     */
    public static void setRuntimeEnabled(@Nullable Boolean value) {
        runtimeOverride = value;
    }

    /** Check before building packet dumps or other expensive diagnostic values. */
    public static boolean enabled() {
        final Boolean override = runtimeOverride;
        return (override != null ? override : CPConfig.debug) && LOGGER.isDebugEnabled();
    }

    public static void log(final String message, final Object... arguments) {
        if (enabled()) LOGGER.debug(message, arguments);
    }
}
