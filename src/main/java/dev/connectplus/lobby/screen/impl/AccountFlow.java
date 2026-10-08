package dev.connectplus.lobby.screen.impl;

import dev.connectplus.accounts.CPAccount;
import dev.connectplus.compat.CpAccounts;
import dev.connectplus.compat.AccountLoginPolicy;
import dev.connectplus.config.CPConfig;
import dev.connectplus.identity.IdentityLinkService;
import dev.connectplus.lobby.LobbyServerHandler;
import dev.connectplus.lobby.protocol.packets.play.S2CSystemChatPacket;
import dev.connectplus.lobby.screen.Lang;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.lobby.screen.ScreenHandler;
import dev.connectplus.session.PlayerData;
import dev.connectplus.session.PlayerSession;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.Style;
import net.lenni0451.mcstructs.text.components.StringComponent;
import net.lenni0451.mcstructs.text.events.click.types.CopyToClipboardClickEvent;
import net.lenni0451.mcstructs.text.events.click.types.OpenUrlClickEvent;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

/**
 * The Microsoft device code login flow: runs the (blocking, minutes-long)
 * MinecraftAuth login on a daemon thread, reports the verification URI and
 * user code into the chat and reopens the main screen when done. Derived from
 * MiniConnect's login flow (MIT, Copyright (c) 2024 Lenni0451). Modified in
 * this repo: the account is persisted through PlayerStore/TokenStore, the
 * thread is interrupted when the client disconnects mid-login and the texts
 * follow the player's language captured at flow start (M6 F1.4).
 *
 * <p>Task 5 adds the first-link flow (plan §1.1): for a verified, unlinked
 * bedrock player the login becomes "login and link" — the fixed warning about
 * the discarded bedrock profile is chatted BEFORE the device-code
 * authorization starts, and a successful authorization hands the verified
 * account to the {@link IdentityLinkService} whose §4.1 transaction performs
 * the binding. A cancelled/failed authorization or a non-committed result
 * never touches the session's account or profile.</p>
 */
public final class AccountFlow {

    private AccountFlow() {
    }

    /**
     * A chat sink for the link-flow texts; the production implementation writes
     * system chat lines through the screen handler. Extracted so the §1.1
     * ordering rule (warning before the device-code start) is testable without
     * a full lobby rig.
     */
    @FunctionalInterface
    public interface LinkChatSink {

        void send(String line);
    }

    /**
     * The §1.1 ordering rule, pinned by {@code AccountFlowLinkTest}: the fixed
     * warning about the discarded bedrock profile is sent first, and only then
     * does {@code deviceCodeStart} (the device-code authorization) run. For a
     * Java player (or any non-bedrock-link flow) {@code startLogin} proceeds
     * without the warning — this method is only invoked on the bedrock link
     * path.
     */
    public static void sendLinkWarningThenStart(final LinkChatSink sink, final Runnable deviceCodeStart,
                                                final Lang lang) {
        for (final TextComponent component : Messages.format(Languages.text(lang, Messages.MainScreen.Login.ChatLinkWarning))) {
            sink.send(component.asUnformattedString());
        }
        deviceCodeStart.run();
    }

    /**
     * Whether the player's name passes the optional account-login allowlist
     * (F8.1); an empty list means every player is allowed. Logout is not gated.
     */
    public static boolean allowlisted(final PlayerSession session) {
        if (CPConfig.accountLoginAllowlist.isEmpty()) {
            return true;
        }
        return session.name != null && CPConfig.accountLoginAllowlist.stream()
                .anyMatch(name -> name.equalsIgnoreCase(session.name));
    }

    /**
     * Starts the login for the given session; a login already in progress is
     * ignored. All client writes are {@code writeAndFlush}, so a finished login
     * after a disconnect is a safe no-op.
     */
    public static void startLogin(final PlayerSession session, final ScreenHandler screenHandler, final LobbyServerHandler handler) {
        if (!AccountLoginPolicy.isAllowedForSession(session)) {
            sendChatLines(screenHandler, Languages.text(Lang.of(session), Messages.Commands.AccountLoginDisabled));
            return;
        }
        if (session.loginInProgress) {
            return;
        }
        if (qualifiesForFirstLink(session) && handler.getLinkService() != null) {
            //§1.1: a verified unlinked bedrock player logs in "and links" — the
            //warning-before-authorization flow with the §4.1 transaction. A lobby
            //built without the link service (tests of unrelated flows) falls
            //through to the plain login path.
            startLinkLogin(session, screenHandler, handler);
            return;
        }
        if (session.playerData == null) {
            //Final fix: a proxied verified session with neither lease nor data has
            //its protected load still in flight — the landing guard would refuse
            //the real profile once a wire-uuid scratch profile occupied the slot.
            //The legacy lazy-init stays for bare/unverified sessions only.
            if (protectedLoadInFlight(session)) {
                sendChatLines(screenHandler, Languages.text(Lang.of(session), Messages.MainScreen.Login.ChatLinkStale));
                return;
            }
            session.playerData = new PlayerData(session.uuid);
        }
        session.loginInProgress = true;
        //Close the GUI right away: the device-code link arrives in the chat and the
        //player must be able to see and click it without a container in the way
        screenHandler.closeScreen();
        final Lang lang = Lang.of(session);
        sendChatLines(screenHandler, Languages.text(lang, Messages.MainScreen.Login.ChatLoading));
        final io.netty.channel.Channel channel = screenHandler.getStateHandler().getChannel();

        final Thread thread = new Thread(() -> {
            try {
                final CPAccount account = CpAccounts.loginViaDeviceCode(code -> {
                    final String uri = code.verificationUri();
                    for (final TextComponent component : Messages.format(
                            Languages.text(lang, Messages.MainScreen.Login.ChatCodeLogin),
                            verificationLink(session.clientVersion, uri),
                            code.userCode()
                    )) {
                        screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                    }
                    if (supportsClickToCopy(session.clientVersion)) {
                        sendChatLines(screenHandler, Languages.text(lang, Messages.MainScreen.Login.ChatCodeLoginCopyHint));
                    }
                });
                channel.eventLoop().execute(() -> {
                    if (!channel.isActive() || session.lobbyChannel != channel) return;
                    try {
                        final boolean installed = installAccountForOwner(session, account, handler, channel);
                        sendChatLines(screenHandler, Languages.text(lang, installed
                                ? Messages.MainScreen.Login.ChatLoginSuccess : Messages.Commands.AccountLoginDisabled));
                    } catch (final Throwable t) {
                        sendChatLines(screenHandler, Languages.text(lang, Messages.MainScreen.Login.ChatLoginFailed), String.valueOf(t.getMessage()));
                    }
                });
            } catch (final Throwable t) {
                LoggerFactory.getLogger("ConnectPlus").warn("Device code login of {} failed: {}", session.uuid, String.valueOf(t.getMessage()));
                channel.eventLoop().execute(() -> {
                    if (!channel.isActive() || session.lobbyChannel != channel) return;
                    for (final TextComponent component : Messages.format(Languages.text(lang, Messages.MainScreen.Login.ChatLoginFailed), String.valueOf(t.getMessage()))) {
                        screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                    }
                });
            } finally {
                //GUI/session state may only be touched on the event loop (the login ran
                //on its own thread)
                channel.eventLoop().execute(() -> {
                    if (finishLogin(session, channel)) screenHandler.openScreen(new MainScreen(lang));
                });
            }
        }, "ConnectPlus-Login");
        thread.setDaemon(true);
        thread.start();

        channel.closeFuture().addListener(future -> thread.interrupt());
    }

    /** A completed attempt on an old lobby channel must not unlock a new attempt after a switch. */
    static boolean finishLogin(final PlayerSession session, final io.netty.channel.Channel owner) {
        synchronized (session) {
            if (!owner.isActive() || session.lobbyChannel != owner) return false;
            session.loginInProgress = false;
            return true;
        }
    }

    // ---- task 5: the first-link flow (§1.1) -----------------------------------

    /**
     * Whether this session qualifies for the first-link flow: a verified
     * bedrock identity that currently uses its own (unlinked) BEDROCK profile.
     * The decision reads only trusted state — the captured ClientIdentity and
     * the resolved ProfileKey — never a claimed name or UUID.
     */
    static boolean qualifiesForFirstLink(final PlayerSession session) {
        final io.netty.channel.Channel c2p = session.c2pChannel;
        if (c2p == null) return false;
        final dev.connectplus.identity.ClientIdentity identity =
                c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).get();
        if (identity == null || identity.kind() != dev.connectplus.identity.ClientIdentity.Kind.VERIFIED_BEDROCK) {
            return false;
        }
        return session.profileKey != null
                && session.profileKey.kind() == dev.connectplus.identity.ProfileKey.Kind.BEDROCK;
    }

    /**
     * Applies the link transaction's result to the session (called on the
     * owning event loop). Only a committed binding whose lease is still the
     * session's current one publishes anything: the account goes live on the
     * session, the profile switch onto the Java profile was performed by the
     * service inside the committed transaction. Every non-committed result —
     * and every result landing after the session's lease was invalidated
     * (displaced, released or superseded) — installs nothing (§4.1/§6: a
     * stale result never revives the old session).
     */
    static void applyLinkResult(final PlayerSession session, final IdentityLinkService.Result result,
                                @Nullable final CPAccount account) {
        if (!leaseCurrent(session, null)) {
            return; // stale callback: the new holder owns the session state now
        }
        switch (result) {
            case COMMITTED, COMMITTED_CLEANUP_PENDING, COMMITTED_ACCESS_PENDING -> {
                //§6 (task 7, R5): a committed result may only install its account
                //while the session still runs the profile that result belongs to.
                //After an unlink the session runs its blank BEDROCK profile — a
                //late link callback must not resurrect the Java credentials.
                final boolean matchesProfile = session.profileKey == null
                        || (session.profileKey.kind() == dev.connectplus.identity.ProfileKey.Kind.JAVA
                        && account != null && account.uuid() != null
                        && session.profileKey.javaUuid().equals(account.uuid()));
                if (account == null || (matchesProfile && AccountLoginPolicy.isAllowedForSession(session))) {
                    session.account = account;
                }
            }
            case CONFLICT, STALE, FAILED -> { /* nothing may change on the session */ }
        }
    }

    /**
     * The task 6 lease-currency check (plan Review Focus 1 residual, §6): a
     * write path may only touch a protected session while its lease is current
     * and the session was not displaced. The coordinator's volatile lease
     * invalidation makes a displacement that happened after the caller's
     * ownership snapshot visible here (check-then-write safety: the caller
     * holds the validated lease reference; this check reads the coordinator's
     * own state through the lobby's granter).
     *
     * <p>A session without a protected profile (no lease) is not refused: it
     * holds no protected state that a displacement could take over, so the
     * legacy channel-ownership guard stays authoritative for it. One load
     * window is the exception (final fix): a PROXIED session with a VERIFIED
     * identity but neither a landed lease nor profile data has its protected
     * load still in flight (claim → load → land) — treating that as "no
     * profile" would let a GUI click lazily create a wire-uuid scratch
     * PlayerData whose landing guard then refuses the real profile and strands
     * the session on scratch data. An UNVERIFIED proxied session (offline
     * players, bridge down) and a bare diagnostic connection (no c2p) have no
     * protected load at all and keep the legacy lazy-init.</p>
     *
     * @param granter the lobby's lease granter (nullable in tests without a
     *                granter wiring); when null only the displaced flag and
     *                the captured lease reference are checked.
     */
    static boolean leaseCurrent(final PlayerSession session, @Nullable final dev.connectplus.session.SessionLeaseGranter granter) {
        if (session.displaced) {
            return false;
        }
        final dev.connectplus.session.SessionLease lease = session.lease;
        if (lease == null) {
            //A verified proxied session with neither lease nor data is mid-load,
            //not "no profile": the GUI must wait instead of creating a wire-uuid
            //scratch profile that would strand the verified session (final fix).
            return !protectedLoadInFlight(session);
        }
        return granter == null || granter.isCurrent(lease);
    }

    /**
     * Whether this session's protected profile load is still in flight (final
     * fix): a proxied session (real c2p) whose identity is pending or verified
     * but which holds neither a landed lease nor profile data sits between the
     * claim and the land — {@code loadAndLandProfile} refuses to install the
     * real profile once {@code playerData} is occupied, so any scratch profile
     * created in this window would run for the session's whole lifetime.
     */
    static boolean protectedLoadInFlight(final PlayerSession session) {
        final io.netty.channel.Channel c2p = session.c2pChannel;
        if (c2p == null || session.playerData != null) {
            return false;
        }
        final dev.connectplus.identity.ClientIdentity identity =
                c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).get();
        final var resolution = c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY_RESOLUTION).get();
        return (resolution != null && !resolution.isDone())
                || (identity != null && identity.kind() != dev.connectplus.identity.ClientIdentity.Kind.UNVERIFIED);
    }

    /**
     * Public access for the connect flow (the §6 connect-target-selection
     * point); same semantics as {@link #leaseCurrent}.
     */
    public static boolean isLeaseCurrent(final PlayerSession session,
                                         @Nullable final dev.connectplus.session.SessionLeaseGranter granter) {
        return leaseCurrent(session, granter);
    }

    /**
     * The chat line for a link result.
     */
    static dev.connectplus.lobby.screen.Msg linkResultMessage(final IdentityLinkService.Result result) {
        return switch (result) {
            case COMMITTED -> Messages.MainScreen.Login.ChatLinkCommitted;
            case COMMITTED_CLEANUP_PENDING -> Messages.MainScreen.Login.ChatLinkCommittedCleanupPending;
            case COMMITTED_ACCESS_PENDING -> Messages.MainScreen.Login.ChatLinkCommittedAccessPending;
            case CONFLICT -> Messages.MainScreen.Login.ChatLinkConflict;
            case STALE -> Messages.MainScreen.Login.ChatLinkStale;
            case FAILED -> Messages.MainScreen.Login.ChatLinkFailed;
        };
    }

    /**
     * The chat line for an unlink result (task 7).
     */
    static dev.connectplus.lobby.screen.Msg unlinkResultMessage(final IdentityLinkService.Result result) {
        return switch (result) {
            case COMMITTED -> Messages.MainScreen.Unlink.ChatCommitted;
            case COMMITTED_CLEANUP_PENDING -> Messages.MainScreen.Unlink.ChatCommittedCleanupPending;
            // Unlink never leaves a list delta pending; the case exists for exhaustiveness.
            case COMMITTED_ACCESS_PENDING -> Messages.MainScreen.Unlink.ChatCommitted;
            case CONFLICT, STALE -> Messages.MainScreen.Unlink.ChatStale;
            case FAILED -> Messages.MainScreen.Unlink.ChatFailed;
        };
    }

    /**
     * The §1.3 safety processing for the Java account's display name before it
     * reaches any GUI item or chat component: legacy formatting codes are
     * stripped (they could restyle the whole line), control characters are
     * removed and the result is length-capped. A name that is empty after the
     * processing falls back to the provided fallback. Never a raw client-
     * controlled string (task 7 requirement).
     */
    static String safeDisplayName(@Nullable final CPAccount account, final String fallback) {
        if (account == null) {
            return fallback;
        }
        String name;
        try {
            name = account.displayName();
        } catch (final Throwable t) {
            return fallback;
        }
        if (name == null) {
            return fallback;
        }
        //Strip legacy section formatting codes (§x too) before anything else.
        name = name.replaceAll("§[0-9a-fk-orA-FK-ORxX]", "");
        name = name.replace("§", "");
        //Strip control characters (chat injection boundaries).
        final StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                cleaned.append(c);
            }
        }
        name = cleaned.toString().trim();
        if (name.length() > MAX_DISPLAY_NAME_LENGTH) {
            name = name.substring(0, MAX_DISPLAY_NAME_LENGTH);
        }
        return name.isEmpty() ? fallback : name;
    }

    /** §1.3: the display name cap (the vanilla name length, safely below it). */
    private static final int MAX_DISPLAY_NAME_LENGTH = 32;

    /**
     * The §1.3 unlink button title: the localized template with B's SAFELY
     * PROCESSED display name formatted in through the shared
     * {@link Messages#format} pipeline (no direct string concatenation of
     * client-controlled data). Used by the {@link MainScreen} item and pinned
     * by {@code BedrockUnlinkTest}.
     */
    static TextComponent unlinkButtonTitle(final Lang lang, @Nullable final CPAccount account,
                                           final dev.connectplus.identity.ProfileKey linkedProfile) {
        final String name = safeDisplayName(account, Languages.text(lang, Messages.MainScreen.Unlink.FallbackName));
        final TextComponent[] parts = Messages.format(Languages.text(lang, Messages.MainScreen.Unlink.ItemName), name);
        return parts[0];
    }
    /**
     * Starts the login for a verified unlinked bedrock player (§1.1): the
     * fixed profile-discard warning is chatted BEFORE the device-code
     * authorization starts; on success the verified account goes into the
     * {@link IdentityLinkService} §4.1 transaction instead of the plain
     * install path. All client writes are {@code writeAndFlush}; a finished
     * flow after a disconnect is a safe no-op.
     */
    static void startLinkLogin(final PlayerSession session, final ScreenHandler screenHandler,
                               final LobbyServerHandler handler) {
        final Lang lang = Lang.of(session);
        final io.netty.channel.Channel channel = screenHandler.getStateHandler().getChannel();
        session.loginInProgress = true;
        screenHandler.closeScreen();
        //§1.1 ordering (binding): the warning first, then the device-code start.
        sendLinkWarningThenStart(
                line -> sendChatLines(screenHandler, line),
                () -> sendChatLines(screenHandler, Languages.text(lang, Messages.MainScreen.Login.ChatLoading)),
                lang);

        final Thread thread = new Thread(() -> {
            CPAccount account = null;
            Throwable failure = null;
            try {
                account = CpAccounts.loginViaDeviceCode(code -> {
                    final String uri = code.verificationUri();
                    for (final TextComponent component : Messages.format(
                            Languages.text(lang, Messages.MainScreen.Login.ChatCodeLogin),
                            verificationLink(session.clientVersion, uri),
                            code.userCode()
                    )) {
                        screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                    }
                    if (supportsClickToCopy(session.clientVersion)) {
                        sendChatLines(screenHandler, Languages.text(lang, Messages.MainScreen.Login.ChatCodeLoginCopyHint));
                    }
                });
            } catch (final Throwable t) {
                failure = t; //cancelled or failed authorization: no account
            }
            final CPAccount verifiedAccount = account;
            try {
                final IdentityLinkService linkService = handler.getLinkService();
                final IdentityLinkService.Result result = verifiedAccount != null && linkService != null
                        && AccountLoginPolicy.isAllowedForSession(session)
                        ? linkService.link(session, verifiedAccount).toCompletableFuture()
                                .get(60, java.util.concurrent.TimeUnit.SECONDS)
                        : IdentityLinkService.Result.FAILED;
                channel.eventLoop().execute(() -> {
                    if (!finishLoginOwnerCheck(session, channel)) return;
                    try {
                        //Only a committed result publishes anything (the same guard
                        //set as every other late callback of this flow). The profile
                        //switch itself was performed inside the committed transaction;
                        //here only the chat lines are added.
                        applyLinkResult(session, result, verifiedAccount);
                        sendChatLines(screenHandler, Languages.text(lang, linkResultMessage(result)));
                    } catch (final Throwable t) {
                        LoggerFactory.getLogger("ConnectPlus").warn("The link flow completion failed", t);
                    }
                });
            } catch (final Throwable t) {
                LoggerFactory.getLogger("ConnectPlus").warn("The link transaction of {} failed: {}",
                        session.uuid, String.valueOf(t.getMessage()));
                channel.eventLoop().execute(() -> {
                    if (!channel.isActive() || session.lobbyChannel != channel) return;
                    applyLinkResult(session, IdentityLinkService.Result.FAILED, null);
                    sendChatLines(screenHandler, Languages.text(lang, Messages.MainScreen.Login.ChatLinkFailed));
                });
            } finally {
                //GUI/session state may only be touched on the event loop (the login ran
                //on its own thread)
                channel.eventLoop().execute(() -> {
                    if (finishLogin(session, channel)) screenHandler.openScreen(new MainScreen(lang));
                });
            }
        }, "ConnectPlus-Link");
        thread.setDaemon(true);
        thread.start();

        channel.closeFuture().addListener(future -> thread.interrupt());
    }

    /** Same ownership check as {@link #finishLogin} without unlocking the login flag (the link unlock is separate). */
    private static boolean finishLoginOwnerCheck(final PlayerSession session, final io.netty.channel.Channel owner) {
        synchronized (session) {
            return owner.isActive() && session.lobbyChannel == owner;
        }
    }

    // ---- task 7: the §1.3 unlink flow ------------------------------------------

    /**
     * Whether the unlink entry may even be OFFERED (§1.3, binding): the player
     * must be a trusted bedrock identity AND currently run its LINKED Java
     * profile. Java players and unlinked bedrock players see no such option.
     * Reads only trusted state — the captured {@code ClientIdentity} and the
     * resolved {@code ProfileKey}, never a claimed name or UUID.
     */
    static boolean qualifiesForUnlink(final PlayerSession session) {
        final io.netty.channel.Channel c2p = session.c2pChannel;
        if (c2p == null) return false;
        final dev.connectplus.identity.ClientIdentity identity =
                c2p.attr(dev.connectplus.compat.CPAttributeKeys.CLIENT_IDENTITY).get();
        if (identity == null || identity.kind() != dev.connectplus.identity.ClientIdentity.Kind.VERIFIED_BEDROCK) {
            return false;
        }
        return session.profileKey != null
                && session.profileKey.kind() == dev.connectplus.identity.ProfileKey.Kind.JAVA;
    }

    /**
     * Starts the §4.2 unlink transaction for a linked trusted bedrock session
     * (called from the {@link MainScreen} unlink item). Repeat-click
     * protection: a second click while an unlink transaction is in flight is
     * rejected (no double transaction). The result is applied on the owning
     * event loop with the same stale-callback guards as the link flow; the
     * lobby GUI is refreshed afterwards.
     */
    static void startUnlink(final PlayerSession session, final ScreenHandler screenHandler,
                            final dev.connectplus.lobby.LobbyServerHandler handler) {
        if (!AccountLoginPolicy.isAllowedForSession(session)) {
            sendChatLines(screenHandler, Languages.text(Lang.of(session), Messages.Commands.AccountLoginDisabled));
            return;
        }
        if (!qualifiesForUnlink(session) || handler.getLinkService() == null) {
            return; // no option for Java / unlinked bedrock / no service wiring
        }
        synchronized (session) {
            if (session.loginInProgress) {
                //Busy feedback instead of a silent ignore (review minor): the
                //player learns why the click did nothing.
                for (final TextComponent component : Messages.format(
                        Languages.text(Lang.of(session), Messages.MainScreen.Unlink.ChatBusy))) {
                    screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
                }
                return;
            }
            session.loginInProgress = true;
        }
        final Lang lang = Lang.of(session);
        final io.netty.channel.Channel channel = screenHandler.getStateHandler().getChannel();
        screenHandler.closeScreen();
        for (final TextComponent component : Messages.format(Languages.text(lang, Messages.MainScreen.Unlink.ChatLoading))) {
            screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
        }
        final dev.connectplus.identity.IdentityLinkService linkService = handler.getLinkService();
        final Thread thread = new Thread(() -> {
            dev.connectplus.identity.IdentityLinkService.Result result;
            try {
                result = linkService.unlink(session).toCompletableFuture()
                        .get(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final Throwable t) {
                LoggerFactory.getLogger("ConnectPlus").warn("The unlink transaction of {} failed: {}",
                        session.uuid, String.valueOf(t.getMessage()));
                result = dev.connectplus.identity.IdentityLinkService.Result.FAILED;
            }
            final dev.connectplus.identity.IdentityLinkService.Result finalResult = result;
            channel.eventLoop().execute(() -> {
                if (!finishLoginOwnerCheck(session, channel)) return;
                try {
                    //Only a result landing while the session is still owned may
                    //touch it (the same guard set as every other late callback;
                    //the transaction itself published/cleared the session state).
                    applyUnlinkResult(session, finalResult);
                    sendChatLines(screenHandler, Languages.text(lang, unlinkResultMessage(finalResult)));
                } catch (final Throwable t) {
                    LoggerFactory.getLogger("ConnectPlus").warn("The unlink flow completion failed", t);
                } finally {
                    if (finishLogin(session, channel)) {
                        //Refresh the lobby GUI: the blank A profile's main screen
                        //(no account item state, no unlink button — §1.3).
                        screenHandler.openScreen(new MainScreen(lang));
                    }
                }
            });
        }, "ConnectPlus-Unlink");
        thread.setDaemon(true);
        thread.start();
        channel.closeFuture().addListener(future -> thread.interrupt());
    }

    /**
     * Applies the unlink transaction's result to the session (called on the
     * owning event loop). The transaction itself performs all session state
     * changes while its lease is current; the only job left here is the stale
     * guard — a result arriving after the session was displaced/superseded
     * touches nothing (§6: the new holder owns the session state now).
     */
    static void applyUnlinkResult(final PlayerSession session, final dev.connectplus.identity.IdentityLinkService.Result result) {
        if (!leaseCurrent(session, null)) {
            return; // stale callback: the new holder owns the session state now
        }
    }

    /** Validate and install under the same lock used to hand a session to a new lobby. */
    static boolean installAccountForOwner(final PlayerSession session, final CPAccount account,
                                         final LobbyServerHandler handler, final io.netty.channel.Channel owner) {
        synchronized (session) {
            return owner.isActive() && session.lobbyChannel == owner && installAccount(session, account, handler);
        }
    }

    /**
     * Removes the stored account from the session and the player data file.
     * Logout is the ONLY path that clears stored credentials — every failed
     * restore leaves the encrypted blob untouched (task 6 §6).
     *
     * <p>Final fix: logout is lease-gated like every other credential write.
     * A displaced-but-not-yet-evicted session (frozen, GUI still live during
     * the bounded exit window) must not wipe the stored blob onto the profile
     * file the arriving same-identity holder is about to restore.</p>
     */
    public static void logout(final PlayerSession session, final ScreenHandler screenHandler, final LobbyServerHandler handler) {
        if (!AccountLoginPolicy.isAllowedForSession(session)) {
            sendChatLines(screenHandler, Languages.text(Lang.of(session), Messages.Commands.AccountLoginDisabled));
            return;
        }
        if (!leaseCurrent(session, handler.getLeaseGranter())) {
            //The profile (and its stored credentials) belongs to the new holder now.
            sendChatLines(screenHandler, Languages.text(Lang.of(session), Messages.MainScreen.Login.ChatLinkStale));
            return;
        }
        session.account = null;
        if (session.playerData != null) {
            session.playerData.accountBlob = null;
            handler.getPlayerStore().save(session.playerData);
        }
        final Lang lang = Lang.of(session);
        sendChatLines(screenHandler, Languages.text(lang, Messages.MainScreen.Login.ChatLogout));
    }

    /**
     * Installs a freshly authorized account on the session (called on the
     * owning event loop after the asynchronous device-code flow). Task 6 closes
     * the plan's Review Focus 1 residual: the install validates the lease
     * CURRENCY (isCurrent + !displaced) in addition to the lobby-channel
     * ownership — a session displaced between the click and the completion must
     * not persist credentials the new holder's load will never see.
     */
    static boolean installAccount(final PlayerSession session, final CPAccount account, final LobbyServerHandler handler) {
        if (!AccountLoginPolicy.isAllowedForSession(session)) return false;
        //The lease-currency gate: the coordinator's volatile invalidation makes a
        //displacement that ran after the caller's ownership snapshot visible here.
        if (!leaseCurrent(session, handler.getLeaseGranter())) return false;
        String encrypted = null;
        if (session.playerData.saveLoginInfo) {
            encrypted = handler.getTokenStore().encrypt(account.toJson());
            //A mode change while encrypting must leave no credentials behind (see the
            //AccountFlowPolicyTest invariant)
            if (!AccountLoginPolicy.isAllowedForSession(session)) return false;
            //Re-check the currency after the (blocking) encryption: the displacement
            //may have run meanwhile — the frozen session keeps no new credentials.
            if (!leaseCurrent(session, handler.getLeaseGranter())) return false;
        }
        session.account = account;
        //Save-login off: the login stays valid for this session only and no
        //account data is written anywhere
        session.playerData.accountBlob = encrypted;
        handler.getPlayerStore().save(session.playerData);
        //The account's real profile name joins the visit index's known names —
        //never a lobby visit (learning a name is not entering the lobby)
        handler.recordKnownAccountName(account);
        return true;
    }

    private static void sendChatLines(final ScreenHandler screenHandler, final String message, final Object... args) {
        for (final TextComponent component : Messages.format(message, args)) {
            screenHandler.getStateHandler().send(new S2CSystemChatPacket(component, false));
        }
    }

    /**
     * Whether the client can handle a click-to-clipboard event. 1.21.5+ know the
     * copy_to_clipboard action; ViaVersion's legacy→1.21.5 conversion leaves
     * unknown actions untouched, so the copy event passes through intact. Older
     * clients would reject the unknown action — they get a plain open-url link.
     */
    static boolean supportsClickToCopy(@Nullable final ProtocolVersion clientVersion) {
        return clientVersion != null && clientVersion.newerThanOrEqualTo(ProtocolVersion.v1_21_5);
    }

    /**
     * The verification-URI link component: 1.21.5+ clients copy on click, every
     * other client opens the browser directly.
     */
    static TextComponent verificationLink(@Nullable final ProtocolVersion clientVersion, final String uri) {
        final Style style = new Style();
        if (supportsClickToCopy(clientVersion)) {
            style.setClickEvent(new CopyToClipboardClickEvent(uri));
        } else {
            style.setClickEvent(new OpenUrlClickEvent(uri));
        }
        return new StringComponent(uri).setStyle(style);
    }

}
