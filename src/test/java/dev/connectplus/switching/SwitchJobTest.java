package dev.connectplus.switching;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchJobTest {

    private static SwitchJob.Target target() {
        return new SwitchJob.Target("example.net:25565", ProtocolVersion.v1_21_4, UUID.randomUUID(), "Player", false, 1);
    }

    @Test
    void newJobIsSwitching() {
        final SwitchJob job = SwitchJob.starting(target());
        assertTrue(job.isSwitching());
        assertNull(job.failureReason());
    }

    @Test
    void markForwardTransitionsOnce() {
        final SwitchJob job = SwitchJob.starting(target());
        assertTrue(job.markForward());
        assertFalse(job.isSwitching());
        assertFalse(job.markForward(), "FORWARD is terminal for markForward");
        assertFalse(job.fail("late"), "FORWARD cannot fail afterwards");
    }

    @Test
    void failRecordsReasonOnce() {
        final SwitchJob job = SwitchJob.starting(target());
        assertTrue(job.fail("boom"));
        assertEquals("boom", job.failureReason());
        assertFalse(job.fail("again"), "FAILED is terminal");
        assertFalse(job.markForward(), "FAILED cannot forward");
    }

    @Test
    void failWithoutReasonKeepsNullReason() {
        final SwitchJob job = SwitchJob.starting(target());
        assertTrue(job.fail(null));
        assertNull(job.failureReason());
    }

    @Test
    void elapsedMillisIsMonotonic() throws InterruptedException {
        final SwitchJob job = SwitchJob.starting(target());
        final long first = job.elapsedMillis();
        Thread.sleep(5);
        assertTrue(job.elapsedMillis() >= first);
    }

    @Test
    void targetCarriesConnectionFacts() {
        final UUID playerId = UUID.randomUUID();
        final SwitchJob.Target target = new SwitchJob.Target("example.net:25565", null, playerId, "Player", false, 1);
        assertEquals("example.net:25565", target.address());
        assertNull(target.version(), "null version = auto detect");
        assertEquals(playerId, target.playerId());
        assertEquals("Player", target.playerName());
        assertFalse(target.lobby());
    }

    @Test
    void lobbyTargetIsMarked() {
        final SwitchJob.Target target = new SwitchJob.Target("127.0.0.1:12345", ProtocolVersion.v1_21_4, UUID.randomUUID(), "Player", true, 1);
        assertTrue(target.lobby());
    }

    @Test
    void reconnectRetainsOfflineSelectionAndOwner() {
        final UUID uuid = UUID.randomUUID();
        final var target = new SwitchJob.Target("example.net", null, uuid, "Player", false, 1, true);
        final var retry = target.nextAttempt().nextAttempt();
        assertTrue(retry.offlineMode());
        assertEquals(3, retry.attempt());
        assertEquals(uuid, retry.playerId());
        assertEquals("Player", retry.playerName());
        assertEquals(target.address(), retry.address());
    }

}
