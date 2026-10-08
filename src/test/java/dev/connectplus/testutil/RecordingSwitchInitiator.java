package dev.connectplus.testutil;

import dev.connectplus.accounts.CPAccount;
import dev.connectplus.session.ConnectionInfo;
import dev.connectplus.switching.SwitchInitiator;
import io.netty.channel.Channel;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Records the switch handoffs the lobby makes, standing in for the hot switch
 * engine in lobby-side integration tests (no ViaProxy runtime involved).
 */
public final class RecordingSwitchInitiator implements SwitchInitiator {

    public record Request(ConnectionInfo target, @Nullable CPAccount account, String playerName) {
    }

    private final List<Request> requests = new CopyOnWriteArrayList<>();

    /**
     * Whether a switch would be initiated; false mirrors an unavailable engine
     * (no proxy connection linked), true hands off successfully. May be set to
     * {@code null}-like presets via {@link #resultOverride} for rejection UX tests.
     */
    public boolean available = true;

    /**
     * When non-null, forces this result instead of the {@link #available} mapping
     * (lets UX tests exercise REJECTED_RATE_LIMITED / REJECTED_BUSY without a real engine).
     */
    public SwitchInitiator.StartResult resultOverride = null;

    public List<Request> requests() {
        return this.requests;
    }

    /**
     * Waits until at least {@code count} handoffs were recorded.
     */
    public List<Request> awaitRequests(final int count) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (this.requests.size() >= count) {
                return this.requests;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + count + " switch handoffs, got: " + this.requests);
    }

    /**
     * Asserts that no further handoff arrives within a short window (used to prove
     * that rejected inputs never reach the engine).
     */
    public void assertNoFurtherRequests(final Supplier<Integer> currentCount) throws InterruptedException {
        final int before = currentCount.get();
        Thread.sleep(300);
        assertFalse(this.requests.size() > before, "No further switch may be handed off");
    }

    @Override
    public SwitchInitiator.StartResult startSwitchFromLobby(final Channel lobbyChannel, final ConnectionInfo target, @Nullable final CPAccount account, final String playerName) {
        this.requests.add(new Request(target, account, playerName));
        if (this.resultOverride != null) {
            return this.resultOverride;
        }
        return this.available ? SwitchInitiator.StartResult.STARTED : SwitchInitiator.StartResult.NOT_AVAILABLE;
    }

}
