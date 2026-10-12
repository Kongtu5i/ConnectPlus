package dev.connectplus.compat;

import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.exception.MsaRequestException;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import net.lenni0451.commons.httpclient.HttpResponse;
import dev.connectplus.compat.CpAccounts.RestoreResult;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 6 §6: the typed restore contract. {@code CpAccounts.restore(String)}
 * never returns null and never exposes credentials; only {@code RESTORED}
 * carries an account; the classification distinguishes a temporary refresh
 * failure (RETRYABLE_FAILURE — the ciphertext stays and a retry may succeed)
 * from dead credentials (REAUTH_REQUIRED) and malformed/undecryptable input
 * (CORRUPT). Unknown failures classify conservatively as RETRYABLE.
 */
class CpAccountsTest {

    /** Far-future tokens: the fixture deserializes without any network refresh. */
    private static String validJson() {
        final JavaAuthManager manager = JavaAuthManager.create(MinecraftAuth.createHttpClient())
                .login(new MsaToken(Long.MAX_VALUE, "test-only-access", "test-only-refresh"));
        manager.getMinecraftToken().set(new MinecraftToken(Long.MAX_VALUE, "Bearer", "test-only-minecraft-token"));
        manager.getMinecraftProfile().set(new MinecraftProfile(UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000bb"), "AccountPlayer"));
        return JavaAuthManager.toJson(manager).toString();
    }

    private static MsaRequestException msaError(final String error) {
        final HttpResponse response = new HttpResponse(url("https://login.live.com"), 400, new byte[0], java.util.Map.of());
        return new MsaRequestException(response, error, "the description");
    }

    private static URL url(final String spec) {
        try {
            return new URL(spec);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void restoreNeverReturnsNullAndCarriesNoCredentials() {
        final RestoreResult result = CpAccounts.restore(validJson());
        assertEquals(RestoreResult.Status.RESTORED, result.status());
        assertNotNull(result.account());
        assertEquals(UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000bb"), result.account().uuid());
        assertNotNull(result.reasonCode());
        assertTrue(!result.reasonCode().toLowerCase().contains("test-only"),
                "The reason code must not contain credentials");
    }

    @Test
    void malformedJsonClassifiesCorrupt() {
        assertEquals(RestoreResult.Status.CORRUPT, CpAccounts.restore(null).status());
        assertEquals(RestoreResult.Status.CORRUPT, CpAccounts.restore("").status());
        assertEquals(RestoreResult.Status.CORRUPT, CpAccounts.restore("not json").status());
        assertEquals(RestoreResult.Status.CORRUPT, CpAccounts.restore("[]").status());
        assertNull(CpAccounts.restore("not json").account(), "CORRUPT never carries an account");
    }

    @Test
    void brokenObjectShapeClassifiesCorrupt() {
        //A JSON object without the JavaAuthManager chain is permanently unreadable
        final RestoreResult result = CpAccounts.restore("{\"broken\":true}");
        assertEquals(RestoreResult.Status.CORRUPT, result.status());
        assertNull(result.account());
        assertNotNull(result.reasonCode());
    }

    @Test
    void invalidGrantRefreshFailureClassifiesReauthRequired() {
        //MinecraftAuth's MSA layer reports rejected refreshes as MsaRequestException
        //with the OAuth error code; invalid_grant means the credentials are dead.
        final RestoreResult result = CpAccounts.restoreThrowing(msaError("invalid_grant"));
        assertEquals(RestoreResult.Status.REAUTH_REQUIRED, result.status());
        assertNull(result.account(), "REAUTH_REQUIRED never installs an account");
        assertNotNull(result.reasonCode());
    }

    @Test
    void interactionRequiredRefreshFailureClassifiesReauthRequired() {
        assertEquals(RestoreResult.Status.REAUTH_REQUIRED, CpAccounts.restoreThrowing(msaError("interaction_required")).status());
    }

    @Test
    void unknownMsaErrorClassifiesRetryable() {
        //An undocumented OAuth error is NOT proof of dead credentials: keep the
        //ciphertext and let a later retry decide (conservative default).
        final RestoreResult result = CpAccounts.restoreThrowing(msaError("something_else"));
        assertEquals(RestoreResult.Status.RETRYABLE_FAILURE, result.status());
        assertNull(result.account());
    }

    @Test
    void networkFailureClassifiesRetryable() {
        final HttpResponse response = new HttpResponse(url("https://login.live.com"), 0, new byte[0], java.util.Map.of());
        final net.lenni0451.commons.httpclient.exceptions.HttpRequestException network =
                new net.lenni0451.commons.httpclient.exceptions.HttpRequestException(response, "connect timed out");
        final RestoreResult result = CpAccounts.restoreThrowing(network);
        assertEquals(RestoreResult.Status.RETRYABLE_FAILURE, result.status());
        assertNull(result.account());
    }

    @Test
    void unknownExceptionClassifiesRetryable() {
        //The conservative default (plan §6): an unclassified failure never
        //destroys the stored credentials.
        assertEquals(RestoreResult.Status.RETRYABLE_FAILURE, CpAccounts.restoreThrowing(new RuntimeException("mystery")).status());
    }

    @Test
    void missingRefreshTokenMeansReauth() {
        //MinecraftAuth throws IllegalStateException("...user has to sign in again.")
        //when the stored token was created without a refresh token.
        final IllegalStateException noRefreshToken =
                new IllegalStateException("Can't refresh MSA token, because it was created without a refresh token. The user has to sign in again.");
        assertEquals(RestoreResult.Status.REAUTH_REQUIRED, CpAccounts.restoreThrowing(noRefreshToken).status());
    }

    @Test
    void restoreOfExpiredTokenAttemptsRefreshInsteadOfReturningExpiredAccount() {
        final JavaAuthManager manager = JavaAuthManager.create(MinecraftAuth.createHttpClient())
                .login(new MsaToken(0, "test-only-access", null));
        manager.getMinecraftToken().set(new MinecraftToken(0, "Bearer", "expired"));
        manager.getMinecraftProfile().set(new MinecraftProfile(UUID.randomUUID(), "AccountPlayer"));
        final RestoreResult result = CpAccounts.restore(JavaAuthManager.toJson(manager).toString());
        assertEquals(RestoreResult.Status.REAUTH_REQUIRED, result.status());
        assertEquals("no_refresh_token", result.reasonCode());
        assertNull(result.account());
    }

    @Test
    void missingRefreshTokenWithoutCachedProfileIsNotCorruptJson() {
        final JavaAuthManager manager = JavaAuthManager.create(MinecraftAuth.createHttpClient())
                .login(new MsaToken(0, "test-only-access", null));
        final RestoreResult result = CpAccounts.restore(JavaAuthManager.toJson(manager).toString());
        assertEquals(RestoreResult.Status.REAUTH_REQUIRED, result.status());
        assertEquals("no_refresh_token", result.reasonCode());
    }

    @Test
    void restoreRefreshesExpiredMinecraftTokenUsingTheAuthenticationChain() {
        final var requests = new java.util.concurrent.atomic.AtomicInteger();
        final var client = new net.lenni0451.commons.httpclient.HttpClient() {
            @Override public HttpResponse execute(final net.lenni0451.commons.httpclient.requests.HttpRequest request) {
                requests.incrementAndGet();
                return new HttpResponse(url("https://api.minecraftservices.com/authentication/login_with_xbox"), 200,
                        "{\"access_token\":\"test-only-renewed\",\"token_type\":\"Bearer\",\"expires_in\":86400}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.util.Map.of("Content-Type", java.util.List.of("application/json")));
            }
        };
        final JavaAuthManager manager = JavaAuthManager.create(client)
                .login(new MsaToken(Long.MAX_VALUE, "test-only-access", "test-only-refresh"));
        manager.getJavaXstsToken().set(new net.raphimc.minecraftauth.xbl.model.XblXstsToken(
                Long.MAX_VALUE, "test-only-xsts", "test-only-userhash"));
        manager.getMinecraftToken().set(new MinecraftToken(0, "Bearer", "test-only-expired"));
        manager.getMinecraftProfile().set(new MinecraftProfile(UUID.randomUUID(), "AccountPlayer"));
        final RestoreResult result = CpAccounts.restore(JavaAuthManager.toJson(manager).toString(), client);
        assertEquals(RestoreResult.Status.RESTORED, result.status(), result.reasonCode());
        assertEquals(1, requests.get(), "Restoration must request a fresh token, not just parse the cache");
        assertTrue(!result.account().isExpired());
        assertTrue(result.account().toJson().contains("test-only-renewed"));
    }

    @Test
    void realRestoreClassifiesAuthenticationNetworkFailureAsRetryable() {
        final var client = new net.lenni0451.commons.httpclient.HttpClient() {
            @Override public HttpResponse execute(final net.lenni0451.commons.httpclient.requests.HttpRequest request)
                    throws java.io.IOException {
                throw new java.io.IOException("synthetic timeout");
            }
        };
        final JavaAuthManager manager = JavaAuthManager.create(client)
                .login(new MsaToken(0, "test-only-access", "test-only-refresh"));
        manager.getMinecraftToken().set(new MinecraftToken(0, "Bearer", "expired"));
        manager.getMinecraftProfile().set(new MinecraftProfile(UUID.randomUUID(), "AccountPlayer"));
        final RestoreResult result = CpAccounts.restore(JavaAuthManager.toJson(manager).toString(), client);
        assertEquals(RestoreResult.Status.RETRYABLE_FAILURE, result.status());
        assertNull(result.account());
    }

    @Test
    void realRestoreClassifiesRejectedRefreshAsReauthorization() {
        final var client = new net.lenni0451.commons.httpclient.HttpClient() {
            @Override public HttpResponse execute(final net.lenni0451.commons.httpclient.requests.HttpRequest request) {
                return new HttpResponse(url("https://login.live.com/oauth20_token.srf"), 400,
                        "{\"error\":\"invalid_grant\",\"error_description\":\"synthetic rejection\"}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.util.Map.of("Content-Type", java.util.List.of("application/json")));
            }
        };
        final JavaAuthManager manager = JavaAuthManager.create(client)
                .login(new MsaToken(0, "test-only-access", "test-only-refresh"));
        manager.getMinecraftToken().set(new MinecraftToken(0, "Bearer", "expired"));
        manager.getMinecraftProfile().set(new MinecraftProfile(UUID.randomUUID(), "AccountPlayer"));
        final RestoreResult result = CpAccounts.restore(JavaAuthManager.toJson(manager).toString(), client);
        assertEquals(RestoreResult.Status.REAUTH_REQUIRED, result.status(), result.reasonCode());
        assertEquals("refresh_rejected:invalid_grant", result.reasonCode());
    }

    @Test
    void onlyRestoredCarriesAnAccount() {
        for (final RestoreResult.Status status : RestoreResult.Status.values()) {
            if (status == RestoreResult.Status.RESTORED) {
                assertNotNull(CpAccounts.restore(validJson()).account());
            } else {
                final RestoreResult result = switch (status) {
                    case REAUTH_REQUIRED -> CpAccounts.restoreThrowing(msaError("invalid_grant"));
                    case RETRYABLE_FAILURE -> CpAccounts.restoreThrowing(new RuntimeException("mystery"));
                    case CORRUPT -> CpAccounts.restore("garbage");
                    case RESTORED -> throw new AssertionError();
                };
                assertNull(result.account(), status + " must never carry an account");
            }
        }
    }
}
