package dev.connectplus.lobby;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PendingMessagesTest {
    @Test void differentLiveConnectionsWithTheSamePlayerCannotReplaceEachOthersMessages() {
        var old = new EmbeddedChannel(); var current = new EmbeddedChannel();
        var messages = new PendingMessages<String>(); UUID id = UUID.randomUUID();
        try {
            messages.post(id,"new",current);
            messages.post(id,"late-old",old);
            assertEquals("new",messages.consume(id,current));
            assertEquals("late-old",messages.consume(id,old));
        } finally { old.finishAndReleaseAll(); current.finishAndReleaseAll(); }
    }
    @Test void rejectedClosedPostCannotEraseTheCurrentOwnersMessage() {
        var old = new EmbeddedChannel(); var current = new EmbeddedChannel();
        var messages = new PendingMessages<String>(); UUID id = UUID.randomUUID();
        try {
            messages.post(id, "new", current); old.close();
            messages.post(id, "stale", old);
            assertEquals("new", messages.consume(id, current));
        } finally { old.finishAndReleaseAll(); current.finishAndReleaseAll(); }
    }
    @Test void replacementSurvivesOldCancellationAndCloseAndExpires() {
        var old = new EmbeddedChannel(); var current = new EmbeddedChannel();
        var messages = new PendingMessages<String>(); UUID id = UUID.randomUUID();
        try {
            Runnable cancel = messages.post(id, "old", old);
            messages.post(id, "new", current);
            cancel.run(); old.close();
            assertNull(messages.consume(id, old));
            assertEquals("new", messages.consume(id, current));
            messages.post(id, "expires", current);
            current.advanceTimeBy(2, TimeUnit.MINUTES); current.runScheduledPendingTasks();
            assertEquals(0, messages.size());
        } finally { old.finishAndReleaseAll(); current.finishAndReleaseAll(); }
    }
}
