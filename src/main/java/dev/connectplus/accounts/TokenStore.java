package dev.connectplus.accounts;

import dev.connectplus.CoreMain;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Encrypts and decrypts account token blobs with AES-GCM. The raw 256-bit key
 * lives in {@code <dataFolder>/secret.key} (created with 32 random bytes on
 * first use); losing or rotating the key file makes previously stored blobs
 * undecryptable, which callers treat as "no account stored".
 */
public class TokenStore {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public TokenStore(final File dataFolder) {
        this.key = new SecretKeySpec(loadOrCreateKey(new File(dataFolder, "secret.key").toPath()), "AES");
    }

    /**
     * Encrypts the plaintext into {@code Base64(IV || ciphertext)}.
     */
    public String encrypt(final String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            final byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, iv));
            final byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts a {@link #encrypt(String)} output; null on any failure (format,
     * authentication tag, null/empty input).
     */
    public String decrypt(final String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return null;
        }
        final byte[] data;
        try {
            data = Base64.getDecoder().decode(ciphertext);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        if (data.length <= IV_LENGTH) {
            return null;
        }
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            return new String(cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH), java.nio.charset.StandardCharsets.UTF_8);
        } catch (final GeneralSecurityException e) {
            return null;
        }
    }

    private static byte[] loadOrCreateKey(final Path keyFile) {
        if (Files.exists(keyFile)) {
            try {
                final byte[] key = Files.readAllBytes(keyFile);
                if (key.length == 32) {
                    return key;
                }
                CoreMain.logger().error("secret.key has an unexpected size of {} bytes, generating a new key (previously stored account tokens become undecryptable)", key.length);
            } catch (final IOException e) {
                CoreMain.logger().error("Failed to read secret.key, generating a new key", e);
            }
        }
        final byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        try {
            Files.write(keyFile, key);
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (final UnsupportedOperationException ignored) {
                //Non-POSIX filesystems (e.g. Windows) keep default permissions
            }
            CoreMain.logger().info("Generated account token encryption key at {}", keyFile.toAbsolutePath());
        } catch (final IOException e) {
            CoreMain.logger().error("Failed to write secret.key", e);
        }
        return key;
    }

}
