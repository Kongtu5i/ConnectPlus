package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The state machine of one seamless server switch on a single {@code ProxyConnection}:
 * SWITCHING (old p2s torn down, new backend logging in, packets suppressed) until the
 * translated JoinGame arrives (FORWARD) or the switch fails (FAILED, reason recorded).
 * Transitions are one-shot and CAS-based, so concurrent completion/failure is safe.
 */
public final class SwitchJob {

    /**
     * What to connect to and on whose behalf; version null = auto detect. The lobby flag
     * marks switches back into the built-in lobby: after those the suppression returns to
     * idle (the lobby owns the commands), while on a server the FORWARD state keeps
     * intercepting the on-target commands. The attempt number is 1 for a first-hand
     * connect and grows inside the M5 automatic reconnect chain. The connectionId is the
     * session's c2p-channel id (the session-registry and lease key): the §6 switch-cache
     * lease validation keys on it; null only for callers without a ConnectPlus session
     * identity (and never when an account rides the job).
     */
    public record Target(String address, @Nullable ProtocolVersion version, UUID playerId, String playerName, boolean lobby, int attempt, boolean offlineMode, @Nullable UUID connectionId) {
        public Target(final String address, @Nullable final ProtocolVersion version, final UUID playerId,
                      final String playerName, final boolean lobby, final int attempt) {
            this(address, version, playerId, playerName, lobby, attempt, false, null);
        }

        public Target(final String address, @Nullable final ProtocolVersion version, final UUID playerId,
                      final String playerName, final boolean lobby, final int attempt, final boolean offlineMode) {
            this(address, version, playerId, playerName, lobby, attempt, offlineMode, null);
        }

        /**
         * The same target, one reconnect attempt further (M5 reconnect chain).
         */
        public Target nextAttempt() {
            return new Target(this.address, this.version, this.playerId, this.playerName, this.lobby, this.attempt + 1, this.offlineMode, this.connectionId);
        }
    }

    private enum Phase {
        SWITCHING, FORWARD, FAILED
    }

    private final Target target;
    private final long startedAtNanos;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.SWITCHING);
    private volatile @Nullable String failureReason;
    private volatile boolean authenticated;

    private SwitchJob(final Target target, final long startedAtNanos) {
        this.target = target;
        this.startedAtNanos = startedAtNanos;
    }

    public static SwitchJob starting(final Target target) {
        return new SwitchJob(target, System.nanoTime());
    }

    /**
     * Whether a Microsoft account rides on this job (set by the engine at switch
     * start). The suppression handler consults it when the backend sends an
     * authenticated encryption request: without an account ViaProxy's joinServer
     * would kick-close the client mid-switch.
     */
    public void markAuthenticated(final boolean authenticated) {
        this.authenticated = authenticated;
    }

    public boolean authenticated() {
        return this.authenticated;
    }

    /**
     * The connection facts this switch is heading for.
     */
    public Target target() {
        return this.target;
    }

    /**
     * Whether the switch is still in progress (packets suppressed).
     */
    public boolean isSwitching() {
        return this.phase.get() == Phase.SWITCHING;
    }

    public boolean isFailed() { return this.phase.get() == Phase.FAILED; }

    /**
     * Transitions SWITCHING to FORWARD exactly when the new backend's JoinGame arrived.
     */
    public boolean markForward() {
        return this.phase.compareAndSet(Phase.SWITCHING, Phase.FORWARD);
    }

    /**
     * Transitions SWITCHING to FAILED, recording the reason (null = no specific reason,
     * e.g. a plain connect timeout on our side).
     */
    public boolean fail(@Nullable String reason) {
        if (this.phase.compareAndSet(Phase.SWITCHING, Phase.FAILED)) {
            this.failureReason = reason;
            return true;
        }
        return false;
    }

    @Nullable
    public String failureReason() {
        return this.failureReason;
    }

    /**
     * Milliseconds since the switch started; for structured logging (design F8.3).
     */
    public long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.startedAtNanos);
    }
}
