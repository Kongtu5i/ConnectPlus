package dev.connectplus.session;

import java.util.concurrent.TimeUnit;

public final class CoordinatorTestSupport {
    private CoordinatorTestSupport() { }

    public static void awaitIdle(AccountSessionCoordinator coordinator) throws Exception {
        coordinator.awaitIdle().get(3, TimeUnit.SECONDS);
    }
}
