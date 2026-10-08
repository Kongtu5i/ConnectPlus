package dev.connectplus.commands;

import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.viaproxy.plugins.events.ConsoleCommandEvent;

import java.util.function.Function;

/**
 * The official console-event entry point. It only matches the four CP root
 * aliases, cancels exactly those, hands the parsed command to the
 * {@link ConsoleCommandService} and prints the returned lines. It holds no
 * stores itself — every query lives in the service.
 */
public final class ConsoleCommandListener {

    private final Function<ConsoleCommands.Command, java.util.concurrent.CompletionStage<java.util.List<String>>> service;

    public ConsoleCommandListener(final Function<ConsoleCommands.Command, java.util.concurrent.CompletionStage<java.util.List<String>>> service) {
        this.service = service;
    }

    @EventHandler
    public void onConsoleCommand(final ConsoleCommandEvent event) {
        if (!ConsoleCommands.isRoot(event.getCommand())) {
            return; //not ours: the host keeps its normal unknown-command handling
        }
        final ConsoleCommands.Command command = ConsoleCommands.parse(event.getCommand(), event.getArgs());
        event.setCancelled(true); //consumed: the host must not print its own unknown-command notice
        try {
            this.service.apply(command).whenComplete((lines, error) -> {
                if (error != null) {
                    dev.connectplus.CoreMain.logger().error("Console command failed: {}", error.toString());
                } else if (lines != null) {
                    for (final String line : lines) {
                        dev.connectplus.CoreMain.logger().info(line);
                    }
                }
            });
        } catch (final RuntimeException e) {
            dev.connectplus.CoreMain.logger().error("Console command failed", e);
        }
    }

}
