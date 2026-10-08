package dev.connectplus.accounts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class TokenStoreTest {

    @TempDir
    File tempDir;

    @Test
    void encryptDecryptRoundTrip() {
        final TokenStore store = new TokenStore(tempDir);
        final String secret = "access_token 中文 with spaces and much longer content than one block";
        assertEquals(secret, store.decrypt(store.encrypt(secret)));
    }

    @Test
    void samePlaintextEncryptsDifferently() {
        final TokenStore store = new TokenStore(tempDir);
        assertNotEquals(store.encrypt("same"), store.encrypt("same"));
    }

    @Test
    void decryptGarbageReturnsNull() {
        final TokenStore store = new TokenStore(tempDir);
        assertNull(store.decrypt("garbage"));
        assertNull(store.decrypt(""));
        assertNull(store.decrypt(null));
    }

    @Test
    void decryptWithDifferentKeyReturnsNull() throws IOException {
        final File dirA = new File(tempDir, "a");
        final File dirB = new File(tempDir, "b");
        dirA.mkdirs();
        dirB.mkdirs();
        final String ciphertext = new TokenStore(dirA).encrypt("secret");
        assertNull(new TokenStore(dirB).decrypt(ciphertext));
    }

    @Test
    void keyFileIsReusedAcrossInstances() throws IOException {
        final String ciphertext = new TokenStore(tempDir).encrypt("secret");
        assertTrue(new File(tempDir, "secret.key").exists());
        assertEquals(32L, Files.size(new File(tempDir, "secret.key").toPath()));
        assertEquals("secret", new TokenStore(tempDir).decrypt(ciphertext));
    }

}
