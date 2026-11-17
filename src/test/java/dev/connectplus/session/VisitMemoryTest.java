package dev.connectplus.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VisitMemoryTest {
    @TempDir Path dir;
    @Test void unchangedKnownNameDoesNotWriteAndSnapshotsAreReused() throws Exception {
        Path index = dir.resolve("visits-index.json");
        PlayerVisitStore store = new PlayerVisitStore(index.toFile());
        UUID id = UUID.randomUUID();
        store.recordKnownJavaName(id, "Name");
        var snapshot = store.snapshot();
        assertSame(snapshot, store.snapshot(), "Repeated reads should reuse the immutable committed view");
        // A redundant update must also succeed when writes have become unavailable.
        Files.delete(index); Files.createDirectory(index);
        store.recordKnownJavaName(id, "Name");
        assertSame(snapshot, store.snapshot());
        assertThrows(java.io.IOException.class, () -> store.recordKnownJavaName(id, "Changed"));
        assertEquals("Name", store.snapshot().knownJavaNames().get(id.toString()));
    }
}
