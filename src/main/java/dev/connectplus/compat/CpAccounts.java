package dev.connectplus.compat;

import com.google.gson.JsonParser;
import dev.connectplus.accounts.CPAccount;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.msa.exception.MsaRequestException;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.lenni0451.commons.httpclient.exceptions.HttpRequestException;
import net.raphimc.viaproxy.saves.impl.accounts.MicrosoftAccount;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * The compat boundary for all ViaProxy/MinecraftAuth account access: device
 * code login, JSON (de)serialization and expiry checks. Derived from
 * MiniConnect's account flow (MIT, Copyright (c) 2024 Lenni0451). No code
 * outside this package may import {@code net.raphimc.viaproxy.*} or
 * {@code net.raphimc.minecraftauth.*}.
 */
public final class CpAccounts {

    /**
     * The device code information shown to the player (clickable verification
     * URI + user code).
     */
    public record DeviceCode(String verificationUri, String userCode) {
    }

    /**
     * The typed outcome of restoring an account from its persisted JSON
     * (plan §6, task 6): exactly one status, an account only for
     * {@link Status#RESTORED}, and a credential-free diagnostic reason code.
     *
     * <p>The classification distinguishes a <b>temporary</b> refresh failure
     * (network down — the encrypted blob stays on disk and a later join may
     * retry) from <b>dead</b> credentials (the refresh was rejected, e.g.
     * {@code invalid_grant} — re-authorization is required) and <b>unreadable</b>
     * data (malformed JSON — corrupt). Unknown failures classify conservatively
     * as retryable: an unclassified error must never destroy stored credentials.</p>
     */
    public record RestoreResult(Status status, @Nullable CPAccount account, String reasonCode) {

        /**
         * Canonical constructor: the §6 invariant "only RESTORED may carry a
         * non-null account" is enforced here, so no caller can ever build a
         * non-restored result that leaks an account reference.
         */
        public RestoreResult {
            if (status != Status.RESTORED && account != null) {
                throw new IllegalArgumentException("Only RESTORED may carry an account (got " + status + ")");
            }
            if (status == Status.RESTORED && account == null) {
                throw new IllegalArgumentException("RESTORED must carry an account");
            }
        }

        /** The restore outcome categories (plan §6, binding). */
        public enum Status {
            /** The account is usable and carried in {@link RestoreResult#account}. */
            RESTORED,
            /** The credentials were rejected (e.g. invalid_grant): re-auth required. */
            REAUTH_REQUIRED,
            /** A temporary failure (network etc.): the ciphertext stays, retry later. */
            RETRYABLE_FAILURE,
            /** The blob is unreadable (malformed JSON / wrong shape): never retryable. */
            CORRUPT
        }

        /**
         * The four fixed outcomes; only {@link Status#RESTORED} carries an account.
         */
        public static RestoreResult restored(final CPAccount account) {
            return new RestoreResult(Status.RESTORED, account, "restored");
        }

        public static RestoreResult reauthRequired(final String reasonCode) {
            return new RestoreResult(Status.REAUTH_REQUIRED, null, reasonCode);
        }

        public static RestoreResult retryableFailure(final String reasonCode) {
            return new RestoreResult(Status.RETRYABLE_FAILURE, null, reasonCode);
        }

        public static RestoreResult corrupt(final String reasonCode) {
            return new RestoreResult(Status.CORRUPT, null, reasonCode);
        }
    }

    private CpAccounts() {
    }

    /**
     * Runs the Microsoft device code login to completion. Blocks (the device
     * code polling takes minutes); the caller picks the thread. {@code onCode}
     * is invoked as soon as the verification URI and user code are available.
     *
     * @return the logged in account
     * @throws Exception on cancelled login, network failure or timeout
     */
    public static CPAccount loginViaDeviceCode(final Consumer<DeviceCode> onCode) throws Exception {
        final JavaAuthManager authManager = JavaAuthManager.create(MinecraftAuth.createHttpClient())
                .login(DeviceCodeMsaAuthService::new, (Consumer<MsaDeviceCode>) code -> onCode.accept(new DeviceCode(
                        code.getDirectVerificationUri() != null ? code.getDirectVerificationUri() : code.getVerificationUri(),
                        code.getUserCode()
                )));
        return new ViaProxyAccount(new MicrosoftAccount(authManager));
    }

    /**
     * Restores an account from its {@link CPAccount#toJson()} representation
     * with a typed outcome (the §6 entry point): parsing and the (network)
     * token refresh are classified separately, so a temporary refresh failure
     * is never mistaken for dead credentials or for a logout. The reason code
     * is diagnostic only and never contains credentials.
     *
     * <p><b>Classification</b> (conservative by design, unknown → retryable):
     * malformed JSON / wrong object shape → {@link Status#CORRUPT};
     * {@code MsaRequestException} with a dead-credential OAuth error
     * ({@code invalid_grant}, {@code interaction_required}, ...) or a missing
     * refresh token → {@link Status#REAUTH_REQUIRED}; every other refresh
     * failure (HTTP I/O, timeouts) → {@link Status#RETRYABLE_FAILURE}.</p>
     */
    public static RestoreResult restore(@Nullable final String json) {
        if (json == null || json.isEmpty()) {
            return RestoreResult.corrupt("empty_blob");
        }
        final MicrosoftAccount parsed;
        try {
            //JavaAuthManager.fromJson only PARSES the JSON chain into the manager
            //(no network IO); the MicrosoftAccount constructor below then refreshes
            //its expired holders (which can hit the network) — the two phases are
            //classified separately here.
            parsed = new MicrosoftAccount(JsonParser.parseString(json).getAsJsonObject());
        } catch (final IllegalStateException | com.google.gson.JsonParseException | java.util.NoSuchElementException e) {
            //Malformed JSON or a JSON object without the JavaAuthManager chain:
            //permanently unreadable, never retryable. Errors (OOM etc.) propagate.
            return RestoreResult.corrupt("unparseable_json");
        } catch (final RuntimeException e) {
            //An unknown parse failure still means the data cannot be read again:
            //the shape is broken on disk, so CORRUPT stays the honest answer.
            return RestoreResult.corrupt("unparseable_json:" + e.getClass().getSimpleName());
        } catch (final Exception e) {
            return RestoreResult.retryableFailure("restore_io:" + e.getClass().getSimpleName());
        }
        return restoreFromParsed(parsed);
    }

    /**
     * Classifies a failure of the (network) token refresh that ran while
     * constructing the account. Exposed for tests through
     * {@code restoreThrowing}-style seams and for the restore path above.
     */
    static RestoreResult classifyRefreshFailure(final Throwable failure) {
        if (failure instanceof final MsaRequestException msa) {
            final String error = msa.getError();
            if (DEAD_CREDENTIAL_ERRORS.contains(error == null ? "" : error.toLowerCase(java.util.Locale.ROOT))) {
                return RestoreResult.reauthRequired("refresh_rejected:" + error);
            }
            //An undocumented OAuth error is not proof of dead credentials: the
            //ciphertext stays and a later refresh may succeed (conservative).
            return RestoreResult.retryableFailure("refresh_error:" + error);
        }
        if (failure instanceof final IllegalStateException state
                && state.getMessage() != null
                && state.getMessage().contains("has to sign in again")) {
            //MinecraftAuth's missing-refresh-token signal: the login chain cannot
            //continue without a new interactive authorization.
            return RestoreResult.reauthRequired("no_refresh_token");
        }
        if (failure instanceof HttpRequestException || failure instanceof java.io.IOException) {
            return RestoreResult.retryableFailure("refresh_io:" + failure.getClass().getSimpleName());
        }
        //Unknown exception type: conservative default (plan §6).
        return RestoreResult.retryableFailure("unknown:" + failure.getClass().getSimpleName());
    }

    /**
     * OAuth error codes that prove the stored credentials can never refresh
     * again (the user must sign in interactively). Everything else keeps the
     * encrypted blob and stays retryable.
     */
    private static final java.util.Set<String> DEAD_CREDENTIAL_ERRORS = java.util.Set.of(
            "invalid_grant",
            "interaction_required",
            "consent_required",
            "invalid_client"
    );

    private static RestoreResult restoreFromParsed(final MicrosoftAccount parsed) {
        try {
            return RestoreResult.restored(new ViaProxyAccount(parsed));
        } catch (final Exception e) {
            //The refresh itself failed (MicrosoftAccount refreshes in its
            //constructor): classify, never degrade into a logout.
            return classifyRefreshFailure(e);
        }
    }

    /**
     * Test seam: classifies a refresh failure directly (the production path
     * routes constructor failures of the parsed account through the same
     * method).
     */
    public static RestoreResult restoreThrowing(final Throwable refreshFailure) {
        return classifyRefreshFailure(refreshFailure);
    }

    /**
     * Unwraps the ViaProxy account behind a ConnectPlus account, or null when the
     * account is not ViaProxy-backed (never the case today); used by the switch
     * engine to attach the account to UserOptions.
     */
    static @Nullable net.raphimc.viaproxy.saves.impl.accounts.Account unwrapViaProxyAccount(final CPAccount account) {
        return account instanceof ViaProxyAccount viaProxyAccount ? viaProxyAccount.wrapped() : null;
    }

    record ViaProxyAccount(MicrosoftAccount wrapped) implements CPAccount {

        @Override
        public String displayName() {
            return this.wrapped.getDisplayString();
        }

        @Override
        public String profileName() {
            //The real Minecraft profile name; the display string is decorative
            return this.wrapped.getName();
        }

        @Override
        public java.util.UUID uuid() {
            return this.wrapped.getUUID();
        }

        @Override
        public boolean isExpired() {
            return this.wrapped.getAuthManager().getMinecraftToken().isExpired();
        }

        @Override
        public String toJson() {
            return this.wrapped.toJson().toString();
        }

    }

}
