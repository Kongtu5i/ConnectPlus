package dev.connectplus.commands;

import net.raphimc.viaproxy.plugins.events.ConsoleCommandEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.connectplus.access.AccessKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the official-event entry point: exactly the four CP root
 * aliases are consumed and cancelled, every other root passes through to the
 * host untouched, and the service output is printed.
 */
class ConsoleCommandListenerTest {

    @Test
    void onlyTheCpRootsAreConsumed() {
        for (final String root : new String[]{"cp", "connectplus", "/cp", "/connectplus"}) {
            final AtomicReference<ConsoleCommands.Command> executed = new AtomicReference<>();
            final ConsoleCommandListener listener = new ConsoleCommandListener(
                    command -> {
                        executed.set(command);
                        return CompletableFuture.completedFuture(List.of("done"));
                    });

            final ConsoleCommandEvent event = new ConsoleCommandEvent(root, new String[]{"status"});
            listener.onConsoleCommand(event);

            assertTrue(event.isCancelled(), "The CP root '" + root + "' must be consumed (cancelled)");
            assertEquals(ConsoleCommands.Type.STATUS, executed.get().type(), "The service must receive the parsed command");
        }
    }

    @Test
    void otherRootsAreNeverConsumed() {
        for (final String root : new String[]{"help", "connect", "disconnect", "", "via", "cpx"}) {
            final AtomicInteger calls = new AtomicInteger();
            final ConsoleCommandListener listener = new ConsoleCommandListener(
                    command -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture(List.of());
                    });

            final ConsoleCommandEvent event = new ConsoleCommandEvent(root, new String[]{"anything"});
            listener.onConsoleCommand(event);

            assertFalse(event.isCancelled(), "The root '" + root + "' must pass through to the host");
            assertEquals(0, calls.get(), "The service must not run for a foreign root");
        }
    }

    @Test
    void theParsedArgumentsReachTheService() {
        final AtomicReference<ConsoleCommands.Command> executed = new AtomicReference<>();
        final ConsoleCommandListener listener = new ConsoleCommandListener(
                command -> {
                    executed.set(command);
                    return CompletableFuture.completedFuture(List.of());
                });

        listener.onConsoleCommand(new ConsoleCommandEvent("cp", new String[]{"debug", "on"}));
        assertEquals(ConsoleCommands.Type.DEBUG, executed.get().type());
        assertEquals("on", executed.get().argument());

        listener.onConsoleCommand(new ConsoleCommandEvent("/connectplus", new String[]{"info", "Bed", "rock"}));
        assertEquals("Bed rock", executed.get().argument());
    }

    @Test
    void failedServiceOutputIsLoggedNotSwallowed() {
        final ConsoleCommandListener listener = new ConsoleCommandListener(
                command -> CompletableFuture.failedFuture(new IllegalStateException("boom-test")));
        final ConsoleCommandEvent event = new ConsoleCommandEvent("cp", new String[]{"accounts"});
        listener.onConsoleCommand(event);
        assertTrue(event.isCancelled(), "Even a failing service consumed the command (no host fallback message)");
    }

    @Test
    void accessCommandsReachTheServiceWithTheirStructure() {
        final AtomicReference<ConsoleCommands.Command> executed = new AtomicReference<>();
        final ConsoleCommandListener listener = new ConsoleCommandListener(
                command -> {
                    executed.set(command);
                    return CompletableFuture.completedFuture(List.of("done"));
                });

        final ConsoleCommandEvent event = new ConsoleCommandEvent("cp",
                new String[]{"wl", "add", "java", "00000000-0000-4000-8000-000000000001"});
        listener.onConsoleCommand(event);

        assertTrue(event.isCancelled());
        assertEquals(ConsoleCommands.Type.ACCESS_LIST, executed.get().type());
        assertNotNull(executed.get().access());
        assertEquals(AccessCommand.Action.ADD, executed.get().access().action());
        assertEquals(AccessKey.ClientType.JAVA, executed.get().access().clientType());
    }
}
