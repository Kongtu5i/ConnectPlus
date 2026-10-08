package dev.connectplus.switching;

import dev.connectplus.logging.DebugLog;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import dev.connectplus.CoreMain;
import dev.connectplus.accounts.CPAccount;
import dev.connectplus.commands.LobbyCommands;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.compat.AccountLoginPolicy;
import dev.connectplus.compat.CompatSwitchSupport;
import dev.connectplus.compat.LobbyLink;
import dev.connectplus.compat.SwitchableProxyConnection;
import dev.connectplus.config.CPConfig;
import dev.connectplus.lobby.LobbyNotices;
import dev.connectplus.lobby.LobbyProtocol;
import dev.connectplus.lobby.LobbyTransfers;
import dev.connectplus.lobby.screen.Languages;
import dev.connectplus.lobby.screen.Messages;
import dev.connectplus.session.ConnectionInfo;
import io.netty.channel.Channel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.IntendedState;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.packet.impl.handshaking.C2SHandshakingClientIntentionPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginHelloPacket;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayKeepAlivePacket;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Orchestrates seamless server switches on one {@code ProxyConnection} (design F2.2):
 * tear the old p2s down (removing the cascade-close handler first, spike notes),
 * rebind the same connection to a fresh channel for the new target (reset + connect),
 * replay handshake and login hello, and let the suppression handler hide the backend
 * login from the client until the translated JoinGame arrives. Failures of any kind
 * hot-switch the player back into the lobby instead of disconnecting (F3.3); only a
 * failing lobby fallback falls back to a client kick.
 */
public class SwitchEngine implements SwitchInitiator, SwitchSuppressionHandler.Owner {

    /**
     * The §6 stale-callback gate of the switch chain (task 6): the engine's own
     * reconnect/fallback/transfer paths reuse a cached account — exactly the
     * "switch cache use" the plan demands a lease check for. The validator is
     * the lobby's coordinator in production (wired when the LobbyServer is
     * built); it answers whether the connection named by its c2p id still holds
     * a current lease, so a result captured before a displacement can never
     * reconnect a backend with the displaced session's credentials. The key is
     * the CONNECTION id (the session-registry and lease key) — never the
     * profile uuid. Missing wiring denies account use.
     */
    public interface AccountLeaseValidator {

        boolean accountMayConnect(UUID connectionId);
    }

    private static volatile @Nullable AccountLeaseValidator accountLeaseValidator;

    /**
     * Wires the production validator (the lobby's session coordinator); called
     * once when the lobby owning the coordinator is built, cleared at plugin
     * disable.
     */
    public static void setAccountLeaseValidator(@Nullable final AccountLeaseValidator validator) {
        accountLeaseValidator = validator;
    }

    /**
     * Whether the account cached for the connection {@code connectionId} may
     * still ride a backend connection: the wired validator re-checks the
     * session's lease currency, dropping the account the moment the lease is
     * frozen/invalidated. An unknown connection (null id or nothing wired)
     * cannot be verified and may not carry a cached account.
     */
    static boolean accountMayConnect(@Nullable final UUID connectionId) {
        if (connectionId == null) {
            return false;
        }
        final AccountLeaseValidator validator = accountLeaseValidator;
        return validator != null && validator.accountMayConnect(connectionId);
    }

    private final Supplier<SocketAddress> lobbyAddress;
    private final ConnectRateLimiter rateLimiter;
    private final ConcurrentHashMap<SwitchJob, List<ScheduledFuture<?>>> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CPAccount> accountsByConnection = new ConcurrentHashMap<>();
    /**
     * The engine's own active jobs on server targets, added at launch and
     * removed in cancelTasks (every terminal/abort path funnels through it).
     * The in-flight count filters by state again at read time, so even a job
     * whose removal is still pending can never be miscounted.
     */
    private final java.util.Set<SwitchJob> inFlightTargetJobs = ConcurrentHashMap.newKeySet();

    public SwitchEngine(final Supplier<SocketAddress> lobbyAddress) {
        this.lobbyAddress = lobbyAddress;
        this.rateLimiter = new ConnectRateLimiter(CPConfig.maxConnectAttemptsPerMinute);
    }

    /**
     * Read-only count for the console status: the jobs currently connecting or
     * switching to a target server. Lobby returns and connections only waiting
     * for a scheduled reconnect never count.
     */
    public int inFlightTargetCount() {
        int count = 0;
        for (final SwitchJob job : this.inFlightTargetJobs) {
            if (job.isSwitching() && !job.target().lobby()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public StartResult startSwitchFromLobby(final Channel lobbyChannel, final ConnectionInfo target, @Nullable final CPAccount account, final String playerName) {
        final UUID linkId = lobbyChannel.attr(CPAttributeKeys.LOBBY_LINK_ID).get();
        if (linkId == null) {
            DebugLog.log("No switch link id on the lobby connection of {} (bare connection?)", playerName);
            return StartResult.NOT_AVAILABLE;
        }
        final ProxyConnection pc = LobbyLink.resolve(linkId);
        if (pc == null) {
            DebugLog.log("No proxy connection is linked to the lobby connection of {} ({})", playerName, linkId);
            return StartResult.NOT_AVAILABLE;
        }
        return this.startSwitch(pc, target, account, playerName);
    }

    /**
     * Starts a seamless switch for a caller that already holds the proxy connection
     * (used by tests and by future resilience features).
     *
     * @return why the switch did not start, or {@link StartResult#STARTED}
     */
    public StartResult startSwitch(final ProxyConnection pc, final ConnectionInfo target, @Nullable final CPAccount account, final String playerName) {
        if (!(pc instanceof SwitchableProxyConnection)) {
            CoreMain.logger().warn("The proxy connection of {} does not support switching", playerName);
            return StartResult.NOT_AVAILABLE;
        }
        final dev.connectplus.access.AccessGate gate = dev.connectplus.access.AccessGate.installed();
        if (gate != null) {
            if (!gate.isAllowed(pc.getC2P())) {
                // A denied connection must never switch anywhere; its own reject path
                // closes the client. A pending identity blocks switches as well.
                CoreMain.logger().info("Blocked the switch of {} (access check pending or denied)", playerName);
                return StartResult.REJECTED_ACCESS_DENIED;
            }
        }
        return this.launch(pc, new SwitchJob.Target(target.address(), target.version(), target.playerId(), playerName, false, 1, target.offlineMode(), target.connectionId()), account);
    }

    /**
     * Switches the player back into the built-in lobby; used for the on-target
     * {@code /disconnect} and as the failure fallback (design F2.2). The lobby
     * switch keeps the original login identity so the player's data (keyed by UUID)
     * and the lobby handshake information survive. The notice is a bilingual
     * template pair; the lobby formats it in the player's language.
     */
    public boolean switchToLobby(final ProxyConnection pc, @Nullable final LobbyNotices.Notice notice, final UUID playerId, final String playerName) {
        if (!clientConnected(pc)) return false;
        final SocketAddress lobby = this.lobbyAddress.get();
        if (lobby == null) {
            return false;
        }
        final Runnable discardNotice = notice == null ? () -> {}
                : LobbyNotices.post(playerId, List.of(notice), pc.getC2P());
        final String address = CompatSwitchSupport.toString(lobby);
        final StartResult result = this.launch(pc, new SwitchJob.Target(address, LobbyProtocol.VERSION, playerId, playerName, true, 1), null);
        if (result != StartResult.STARTED) {
            //Defensive (review finding 1): a rejection before the job exists has no
            //failure path of its own — the player would hang without this last resort.
            discardNotice.run();
            CoreMain.logger().error("The lobby fallback of {} could not start ({}), kicking", playerName, result);
            this.kick(pc, "the lobby fallback could not start (" + result + ")");
            return false;
        }
        return true; //the switch is running; its failure path is the engine's kick fallback
    }

    private StartResult launch(final ProxyConnection pc, final SwitchJob.Target target, @Nullable final CPAccount account) {
        // The access gate: a pending identity or an unfinished blacklist inheritance
        // blocks transfers to target servers. Lobby returns (safety recovery) stay
        // permitted for pending connections; denied ones were blocked above.
        final dev.connectplus.access.AccessGate launchGate = dev.connectplus.access.AccessGate.installed();
        if (launchGate != null && (launchGate.isDenied(pc.getC2P())
                || !target.lobby() && !launchGate.protectedOperationsAllowed(pc.getC2P()))) {
            CoreMain.logger().info("Blocked the transfer of {} to {} (access check pending or inheritance unfinished)",
                    target.playerName(), target.address());
            return StartResult.REJECTED_ACCESS_DENIED;
        }
        // Validate the client wire format before clearing inventory, closing the
        // working backend, caching credentials, or starting a recovery chain.
        // Snapshots carry a distinct wire ID; a release's layout cannot validate them.
        final ProtocolVersion clientVersion = pc.getClientVersion();
        if (clientVersion == null || clientVersion.getVersionType() != VersionType.RELEASE
                || !WorldReset.supports(clientVersion.getOriginalVersion())) {
            CoreMain.logger().warn("Rejected switch of {} before disconnecting: client protocol {} ({}) is not audited. Update ConnectPlus for this client version.",
                    target.playerName(), clientVersion == null ? "unknown" : clientVersion.getOriginalVersion(),
                    clientVersion == null ? "unknown" : clientVersion.getVersionType());
            return StartResult.REJECTED_UNSUPPORTED_PROTOCOL;
        }
        final CPAccount permittedAccount = AccountLoginPolicy.isAllowedForConnection(pc.getC2P())
                && !target.offlineMode() && accountMayConnect(target.connectionId()) ? account : null;
        //The lobby is recovery infrastructure (F3.1/F3.3): a fallback must never be
        //blocked by the connect rate limit, or a player could hang on a dead backend.
        if (!target.lobby() && CPConfig.maxConnectAttemptsPerMinute > 0 && !this.rateLimiter.tryAcquire(target.playerId())) {
            CoreMain.logger().info("Rate limited the connect attempt of {} to {}", target.playerName(), target.address());
            return StartResult.REJECTED_RATE_LIMITED;
        }
        final SwitchSuppressionHandler suppression = this.suppressionOf(pc);
        if (suppression == null) {
            CoreMain.logger().warn("No switch suppression handler installed for {}", target.playerName());
            return StartResult.NOT_AVAILABLE;
        }
        final SwitchJob job = suppression.begin(SwitchJob.starting(target));
        if (job == null) {
            CoreMain.logger().info("Rejected a second switch for {} (one is already running)", target.playerName());
            return StartResult.REJECTED_BUSY;
        }
        if (!target.lobby()) {
            this.inFlightTargetJobs.add(job);
        }
        if (permittedAccount != null) {
            this.accountsByConnection.put(target.connectionId(), permittedAccount);
        } else if (target.connectionId() != null) {
            this.accountsByConnection.remove(target.connectionId());
        }
        CoreMain.logger().info("Switching {} to {} ({} version, attempt {})", target.playerName(), target.address(),
                target.version() == null ? "auto detect" : target.version().getName(), target.attempt());
        pc.getC2P().eventLoop().execute(() -> this.run(pc, job, permittedAccount));
        return StartResult.STARTED;
    }

    private void run(final ProxyConnection pc, final SwitchJob job, @Nullable final CPAccount account) {
        if (!job.isSwitching()) {
            return; //failed meanwhile (e.g. the p2s died before the engine ran)
        }
        if (pc.getC2P() == null || !pc.getC2P().isActive()
                || Boolean.TRUE.equals(pc.getC2P().attr(CPAttributeKeys.ACCESS_DENIED).get())) {
            //The client connection is already gone (e.g. a ViaProxy kick escaped our
            //call stack): no switch outcome can reach the player, so skip the whole
            //run — a fallback job would only die at first write ("connect failed").
            this.cancelTasks(job);
            DebugLog.log("Client connection of {} closed during a switch; aborting the job", job.target().playerName());
            return;
        }
        final Channel c2p = pc.getC2P();
        final SwitchSuppressionHandler suppression = this.suppressionOf(pc);
        if (suppression != null) suppression.pauseFrontendReads();
        final List<ScheduledFuture<?>> jobTasks = this.scheduleWatchdogs(pc, job);
        try {
            final SocketAddress lobby = this.lobbyAddress.get();
            if (lobby != null && lobby.equals(pc.getServerAddress())
                    && pc.getClientVersion().newerThanOrEqualTo(ProtocolVersion.v1_7_2)) {
                // Suppression now drops old lobby replies, including click corrections.
                // Correct the predicted cursor and close the GUI directly on c2p,
                // before closing p2s or waiting for the target's login/auto-detection.
                final Integer windowId = c2p.attr(dev.connectplus.compat.CPAttributeKeys.LOBBY_WINDOW_ID).get();
                for (final var packet : LobbyInventoryReset.forClient(pc.getClientVersion().getVersion(), windowId == null ? 1 : windowId)) {
                    c2p.writeAndFlush(packet).addListener(io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                }
            }
            //Pull the old p2s out of PLAY immediately: its connection state is part of
            //the JoinGame detection, and in-flight packets of the old connection (e.g.
            //its own join sequence) must not be mistaken for the new backend's JoinGame.
            if (pc.getChannel() != null) {
                pc.setP2sConnectionState(ConnectionState.HANDSHAKING);
            }
            this.teardownOldP2s(pc);

            final ProtocolVersion version = this.resolveVersion(job.target().version(), pc.getClientVersion(), job);
            final SocketAddress address = CompatSwitchSupport.parseAddress(job.target().address(), version);

            ((SwitchableProxyConnection) pc).resetChannel();
            final io.netty.channel.ChannelFuture connectFuture = pc.connectToServer(address, version);
            connectFuture.addListener((io.netty.util.concurrent.GenericFutureListener<io.netty.channel.ChannelFuture>) future -> {
                final Channel connectedChannel = future.channel();
                if (!this.ownsAttempt(pc, job, connectedChannel)) {
                    if (pc.getChannel() != connectedChannel) connectedChannel.close();
                    return;
                }
                if (!future.isSuccess()) {
                    final Throwable cause = future.cause();
                    DebugLog.log("Backend connection error for {} (target version {}, client v{})",
                            job.target().address(), version == null ? "auto" : version.getName(), pc.getClientVersion().getVersion(), cause);
                    this.failJob(pc, job, "connect failed: " + describeFailure(cause));
                    return;
                }
                connectedChannel.eventLoop().submit(() -> this.handshakeAndLogin(pc, job, address, account, connectedChannel));
            });
        } catch (final Throwable t) {
            DebugLog.log("Server switch setup error for {}", job.target().address(), t);
            this.failJob(pc, job, describeFailure(t));
        }
    }

    private static String describeFailure(@Nullable final Throwable failure) {
        if (failure == null) return "unknown connection error";
        final StringBuilder text = new StringBuilder();
        final java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.size() < 8 && seen.add(cause); cause = cause.getCause()) {
            if (!text.isEmpty()) text.append("; caused by ");
            text.append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) text.append(": ").append(cause.getMessage());
        }
        return text.toString();
    }

    /**
     * Removes Proxy2ServerHandler from the old p2s before closing it, so its
     * channelInactive cannot cascade-close the client connection (spike notes).
     */
    private void teardownOldP2s(final ProxyConnection pc) {
        final Channel oldP2s = pc.getChannel();
        if (oldP2s == null) {
            return;
        }
        if (oldP2s.pipeline().get(MCPipeline.HANDLER_HANDLER_NAME) != null) {
            oldP2s.pipeline().remove(MCPipeline.HANDLER_HANDLER_NAME);
        }
        oldP2s.close();
    }

    @Nullable
    private ProtocolVersion resolveVersion(@Nullable final ProtocolVersion configured, final ProtocolVersion clientVersion, final SwitchJob job) {
        if (!CompatSwitchSupport.isAutomaticVersion(configured)) {
            return configured;
        }
        //Auto detect: a short status ping against the target (null version = default port handling)
        final SocketAddress probe = CompatSwitchSupport.parseAddress(job.target().address(), null);
        final ProtocolVersion detected = CompatSwitchSupport.detectProtocolVersion(probe, clientVersion);
        if (CompatSwitchSupport.isAutomaticVersion(detected)) {
            throw new IllegalStateException("protocol auto detection did not return a concrete server version");
        }
        DebugLog.log("[Switch:{}] auto-detected target version {} (protocol {})", job.target().playerName(),
                detected.getName(), detected.getOriginalVersion());
        return detected;
    }

    private boolean ownsAttempt(final ProxyConnection pc, final SwitchJob job, final Channel channel) {
        final SwitchSuppressionHandler suppression = this.suppressionOf(pc);
        return job.isSwitching() && suppression != null && suppression.currentJob() == job
                && pc.getC2P() != null && pc.getC2P().isActive() && pc.getChannel() == channel;
    }

    private void handshakeAndLogin(final ProxyConnection pc, final SwitchJob job, final SocketAddress address,
                                   @Nullable final CPAccount account, final Channel p2s) {
        if (!this.ownsAttempt(pc, job, p2s)) return;
        try {
            if (address instanceof InetSocketAddress target) {
                //ViaProxy's own connect flow rewrites the handshake address to the target
                //(its default config), so the backend sees a vanilla-looking handshake.
                final C2SHandshakingClientIntentionPacket handshake = new C2SHandshakingClientIntentionPacket(
                        pc.getClientVersion().getOriginalVersion(), target.getHostString(), target.getPort(), IntendedState.LOGIN);
                p2s.writeAndFlush(handshake).addListener(f -> {
                    if (!this.ownsAttempt(pc, job, p2s)) return;
                    if (!f.isSuccess()) {
                        this.failJob(pc, job, "handshake write failed");
                        return;
                    }
                    pc.setP2sConnectionState(ConnectionState.LOGIN);
                    this.sendLoginHello(pc, job, account, p2s);
                });
            } else {
                this.failJob(pc, job, "unsupported target address type " + address.getClass().getSimpleName());
            }
        } catch (final Throwable t) {
            this.failJob(pc, job, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    private void sendLoginHello(final ProxyConnection pc, final SwitchJob job, @Nullable final CPAccount account, final Channel p2s) {
        if (!this.ownsAttempt(pc, job, p2s)) return;
        try {
            final CPAccount permittedAccount = AccountLoginPolicy.isAllowedForConnection(pc.getC2P())
                    && !job.target().offlineMode() && accountMayConnect(job.target().connectionId()) ? account : null;
            job.markAuthenticated(permittedAccount != null);
            if (!job.target().lobby()) {
                //Server targets: attach the player's account like ViaProxy's original
                //login flow does (game profile, chat session data, hello rewrite).
                DebugLog.log("[Switch:{}] attaching account: {}",
                        job.target().playerName(), permittedAccount == null ? "NONE (offline join)" : permittedAccount.displayName());
                pc.setUserOptions(CompatSwitchSupport.userOptions(permittedAccount));
                if (job.target().offlineMode()) {
                    CompatSwitchSupport.prepareOfflinePlayerData(pc, job.target().playerName());
                }
                CompatSwitchSupport.fillPlayerData(pc);
            }
            if (!this.ownsAttempt(pc, job, p2s)) return;
            final C2SLoginHelloPacket hello = CompatSwitchSupport.loginHello(pc);
            if (hello == null) {
                this.failJob(pc, job, "no login hello to replay");
                return;
            }
            DebugLog.log("[Switch:{}] login hello replayed ({} ms)", job.target().playerName(), job.elapsedMillis());
            p2s.writeAndFlush(hello).addListener(f -> {
                if (!f.isSuccess() && this.ownsAttempt(pc, job, p2s)) {
                    this.failJob(pc, job, "login hello write failed");
                }
                //From here the suppression handler drives the backend login: it answers
                //the configuration handshake and completes the job on the JoinGame.
            });
        } catch (final Throwable t) {
            this.failJob(pc, job, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    private List<ScheduledFuture<?>> scheduleWatchdogs(final ProxyConnection pc, final SwitchJob job) {
        final Channel c2p = pc.getC2P();
        //A replaced entry (the reconnect chain's interim keepalive) must be cancelled
        //before being overwritten, or it keeps pinging the client forever (review finding 2)
        this.cancelScheduledTasks(job);
        final ScheduledFuture<?> timeout = c2p.eventLoop().schedule(() -> {
            this.failJob(pc, job, "switch timed out (" + CPConfig.switchTimeoutSeconds + "s)");
        }, Math.max(1, CPConfig.switchTimeoutSeconds), TimeUnit.SECONDS);
        final ScheduledFuture<?> keepalives = c2p.eventLoop().scheduleWithFixedDelay(() -> {
            //Keep the client's keepalive machinery alive while nothing is forwarded
            if (job.isSwitching()) {
                writeClientKeepAlive(pc);
            }
        }, 5, 5, TimeUnit.SECONDS);
        final List<ScheduledFuture<?>> list = List.of(timeout, keepalives);
        this.tasks.put(job, list);
        return list;
    }

    private void failJob(final ProxyConnection pc, final SwitchJob job, @Nullable final String reason) {
        if (job.fail(reason)) {
            CoreMain.logger().warn("Switch of {} to {} failed after {} ms (configured target version {}, client v{}): {}",
                    job.target().playerName(), job.target().address(), job.elapsedMillis(),
                    job.target().version() == null ? "auto" : job.target().version().getName(), pc.getClientVersion().getVersion(),
                    reason == null ? "unknown reason" : reason);
            this.onSwitchFailed(pc, job, reason);
        }
    }

    @Override
    public void onSwitchComplete(final ProxyConnection pc, final SwitchJob job) {
        this.cancelTasks(job);
    }

    @Override
    public void onSwitchFailed(final ProxyConnection pc, final SwitchJob job, @Nullable final String reason) {
        this.cancelTasks(job);
        if (!clientConnected(pc)) return;
        final SwitchSuppressionHandler suppression = this.suppressionOf(pc);
        if (suppression != null) suppression.pauseFrontendReads();
        if (job.target().lobby()) {
            //The fallback to the lobby itself failed: the last resort is a clean kick
            //(the only case where a switch failure may close the client connection).
            CoreMain.logger().error("The lobby fallback of {} failed, kicking ({}): {}", job.target().playerName(), job.target().address(), reason);
            this.kick(pc, reason);
            return;
        }
        if ("reconnect".equalsIgnoreCase(CPConfig.backendDownPolicy) && job.target().attempt() > 1) {
            //A failed reconnect attempt stays inside the chain (F3.1): the chain counts
            //this attempt and schedules the next one or falls back when exhausted.
            this.reconnectChain(pc, job);
            return;
        }
        final Object reasonArg = reason != null ? reason
                : (LobbyNotices.LocalizedArg) lang -> Languages.text(lang, Messages.Commands.SwitchFailedGeneric);
        final LobbyNotices.Notice notice = LobbyNotices.Notice.of(Messages.Commands.SwitchFailed, job.target().address(), reasonArg);
        if (!this.switchToLobby(pc, notice, job.target().playerId(), job.target().playerName())) {
            CoreMain.logger().error("No lobby to fall back to for {}, kicking", job.target().playerName());
            this.kick(pc, reason);
        }
    }

    @Override
    public void onForwardKick(final ProxyConnection pc, final SwitchJob job, final String reason) {
        //F3.2 with kickPolicy=lobby (the suppressor only calls this in that case): back
        //to the lobby with the kick reason; the player never sees the disconnect screen.
        pc.getC2P().eventLoop().execute(() -> {
            if (!clientConnected(pc)) return;
            final LobbyNotices.Notice notice = LobbyNotices.Notice.of(Messages.Commands.KickedByServer, job.target().address(), reason);
            this.switchToLobby(pc, notice, job.target().playerId(), job.target().playerName());
        });
    }

    @Override
    public void onForwardDeath(final ProxyConnection pc, final SwitchJob job) {
        //F3.1: the p2s died while forwarding on a server target.
        pc.getC2P().eventLoop().execute(() -> {
            if (!clientConnected(pc)) return;
            if ("reconnect".equalsIgnoreCase(CPConfig.backendDownPolicy)) {
                this.reconnectChain(pc, job);
                return;
            }
            final LobbyNotices.Notice notice = LobbyNotices.Notice.of(Messages.Commands.BackendDied, job.target().address());
            if (!this.switchToLobby(pc, notice, job.target().playerId(), job.target().playerName())) {
                CoreMain.logger().error("No lobby to fall back to for {} after a backend death, kicking", job.target().playerName());
                this.kick(pc, "backend died");
            }
        });
    }

    @Override
    public void onTargetTransfer(final ProxyConnection pc, final SwitchJob job, final String host, final int port) {
        //F7: the backend wants to move the player to another server.
        pc.getC2P().eventLoop().execute(() -> {
            final String policy = CPConfig.transferPolicy.toLowerCase(java.util.Locale.ROOT);
            switch (policy) {
                case "follow" -> {
                    //§6: the follow job carries the ORIGINAL connection id (the lease
                    //key) — dropping it here would sever the follow job's own reconnect
                    //chain from the cached account and log a misleading "not verifiably
                    //current" line for credentials that do ride this switch.
                    final StartResult result = this.launch(pc,
                            new SwitchJob.Target(transferAddress(host, port), null, job.target().playerId(), job.target().playerName(), false, 1, job.target().offlineMode(), job.target().connectionId()),
                            this.cachedAccountFor(job.target().connectionId(), job.target().playerName()));
                    if (result != StartResult.STARTED) {
                        //The player stays on the current server; there is no chat feedback
                        //channel on a target (M4), so the rejection is logged only.
                        CoreMain.logger().warn("Rejected the transfer follow of {} to {}:{} ({})",
                                job.target().playerName(), host, port, result);
                    }
                }
                case "ignore" -> CoreMain.logger().info("Ignored a transfer of {} to {}:{} (transferPolicy=ignore)",
                        job.target().playerName(), host, port);
                default -> {
                    //"confirm": back to the lobby, the confirmation GUI (or the chat
                    //fallback) lets the player re-connect deliberately (F7.1)
                    LobbyTransfers.post(job.target().playerId(), host, port);
                    final LobbyNotices.Notice notice = LobbyNotices.Notice.of(Messages.Commands.TransferConfirm, host, port);
                    this.switchToLobby(pc, notice, job.target().playerId(), job.target().playerName());
                }
            }
        });
    }

    /**
     * Renders a transfer packet target as a ConnectPlus target address. IPv6
     * literals must be bracketed or the port would be ambiguous to the address
     * parser ({@code ::1:25565} is unparseable, {@code [::1]:25565} is not).
     * Shared with the transfer confirmation GUI (M7), which reconnects with the
     * same address rendering as the engine's follow policy.
     */
    public static String transferAddress(final String host, final int port) {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }

    /**
     * Whether this client version gets the switch/reconnect keepalive watchdog.
     * Everything from 1.7.2 on does: NetMinecraft's keepalive packet picks the
     * payload encoding from the protocol version it is written with (long >= 340,
     * varint 47..339, int &lt; 47), and the c2p codec always writes with the client
     * version — so a 1.7 client receives a well-formed int keepalive. Pre-1.7
     * legacy protocols have no keepalive handshake to emulate.
     */
    static boolean keepAliveSupported(final ProtocolVersion clientVersion) {
        return clientVersion.newerThanOrEqualTo(ProtocolVersion.v1_7_2);
    }

    /**
     * Pings the client so its keepalive machinery does not time out while nothing
     * is forwarded (SWITCHING) or while the reconnect chain waits (F3.1). The
     * client's echo is dropped by the suppression handler during a switch and
     * forwarded during the reconnect wait, where it is harmless.
     */
    static void writeClientKeepAlive(final ProxyConnection pc) {
        if (keepAliveSupported(pc.getClientVersion())) {
            pc.getC2P().writeAndFlush(pc.getC2pConnectionState() == ConnectionState.CONFIGURATION
                    ? new net.raphimc.netminecraft.packet.impl.configuration.S2CConfigKeepAlivePacket(System.nanoTime())
                    : new S2CPlayKeepAlivePacket(System.nanoTime()));
        }
    }

    /**
     * The cached account of a connection for a retry/reconnect/transfer, or
     * null when the session's lease is no longer current (§6): a stale cached
     * account must not ride a later backend connection — the displaced (or
     * otherwise invalidated) session never revives through the engine's own
     * recovery paths. Keyed by the CONNECTION id (the lease key), never by the
     * profile uuid.
     */
    @Nullable
    private CPAccount cachedAccountFor(@Nullable final UUID connectionId, final String playerName) {
        if (connectionId == null) {
            return null;
        }
        final CPAccount cached = this.accountsByConnection.get(connectionId);
        if (cached == null) {
            return null;
        }
        if (!accountMayConnect(connectionId)) {
            CoreMain.logger().info("Dropping the cached account of {} (the session lease is no longer current)", playerName);
            this.accountsByConnection.remove(connectionId);
            return null;
        }
        return cached;
    }

    /**
     * Test seam: a retry-path read of the §6 cache — the exact semantics of
     * {@link #cachedAccountFor} (lease re-check + eviction on staleness),
     * minus the log argument, so tests can observe what the reconnect chain,
     * transfer-follow and on-target commands would reuse.
     */
    @Nullable
    CPAccount cachedAccountForTest(@Nullable final UUID connectionId) {
        return this.cachedAccountFor(connectionId, "TestPlayer");
    }

    /**
     * The M5 automatic reconnect chain (F3.1, backendDownPolicy=reconnect): retry the
     * same target with linear backoff while the client stays connected (the keepalive
     * watchdog covers the waiting time). Exhausted attempts fall back to the lobby.
     */
    private void reconnectChain(final ProxyConnection pc, final SwitchJob job) {
        final SwitchJob.Target target = job.target();
        //0 = no retries (straight to the lobby); negative configs are treated as 0
        if (target.attempt() > Math.max(0, CPConfig.reconnectAttempts)) {
            final LobbyNotices.Notice notice = LobbyNotices.Notice.of(Messages.Commands.ReconnectExhausted, target.address(), CPConfig.reconnectAttempts);
            if (!this.switchToLobby(pc, notice, target.playerId(), target.playerName())) {
                CoreMain.logger().error("No lobby to fall back to for {} after exhausted reconnects, kicking", target.playerName());
                this.kick(pc, "reconnect attempts exhausted");
            }
            return;
        }
        final long delaySeconds = (long) Math.max(0, CPConfig.reconnectDelaySeconds) * (target.attempt() - 1);
        final ScheduledFuture<?> keepalive = pc.getC2P().eventLoop().scheduleWithFixedDelay(() -> writeClientKeepAlive(pc), 5, 5, TimeUnit.SECONDS);
        this.tasks.put(job, List.of(keepalive));
        CoreMain.logger().info("Reconnect {} of {} to {} in {}s", target.attempt(), target.playerName(), target.address(), delaySeconds);
        pc.getC2P().eventLoop().schedule(() -> {
            final StartResult result = pc.getC2P().isActive()
                    ? this.launch(pc, target.nextAttempt(), this.cachedAccountFor(target.connectionId(), target.playerName()))
                    : StartResult.NOT_AVAILABLE; //the player is gone; nothing to recover
            if (result == StartResult.STARTED) {
                this.cancelTasks(job); //the new job installs its own watchdogs
                return;
            }
            //Review finding 3: a rejected reconnect (busy / rate limited / gone) must not
            //stall the player under a keepalive drip — treat it like an exhausted chain.
            this.cancelTasks(job);
            if (!pc.getC2P().isActive()) {
                return;
            }
            if (result == StartResult.REJECTED_BUSY) {
                //Another switch owns the connection (e.g. a kick-triggered lobby
                //fallback whose recovery started between this job's death and the
                //retry): its own watchdogs recover the player — kicking here would
                //race that recovery and disconnect the player (V4 finding)
                CoreMain.logger().info("The reconnect of {} was rejected (busy); another recovery owns the connection", target.playerName());
                return;
            }
            CoreMain.logger().error("The reconnect of {} could not start ({}), falling back to the lobby", target.playerName(), result);
            final LobbyNotices.Notice notice = LobbyNotices.Notice.of(Messages.Commands.ReconnectExhausted, target.address(), CPConfig.reconnectAttempts);
            if (!this.switchToLobby(pc, notice, target.playerId(), target.playerName())) {
                this.kick(pc, "the reconnect could not start (" + result + ")");
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    @Override
    public void onTargetCommand(final ProxyConnection pc, final LobbyCommands.Command command) {
        //Only the exit commands reach this callback (the suppression handler consumes
        //exactly Type.DISCONNECT); on a target there is no lobby feedback channel, so
        //usage errors are never intercepted at all.
        final SwitchSuppressionHandler suppression = this.suppressionOf(pc);
        final SwitchJob current = suppression == null ? null : suppression.currentJob();
        if (current == null) {
            return;
        }
        if (command.type() == LobbyCommands.Type.DISCONNECT) {
            this.switchToLobby(pc, null, current.target().playerId(), current.target().playerName());
        }
    }

    private static boolean clientConnected(final ProxyConnection pc) {
        return pc.getC2P() != null && pc.getC2P().isActive()
                && !Boolean.TRUE.equals(pc.getC2P().attr(CPAttributeKeys.ACCESS_DENIED).get());
    }

    private void kick(final ProxyConnection pc, @Nullable final String reason) {
        try {
            pc.kickClient("ConnectPlus: the server switch failed and the proxy could not reach the lobby."
                    + (reason == null ? "" : " (" + reason + ")"));
        } catch (final Throwable ignored) {
            try {
                pc.getC2P().close();
            } catch (final Throwable ignoredAgain) {
            }
        }
    }

    private void cancelTasks(final SwitchJob job) {
        this.inFlightTargetJobs.remove(job); //no terminal/abort path skips this funnel
        this.cancelScheduledTasks(job);
    }

    private void cancelScheduledTasks(final SwitchJob job) {
        final List<ScheduledFuture<?>> jobTasks = this.tasks.remove(job);
        if (jobTasks != null) {
            for (final ScheduledFuture<?> task : jobTasks) {
                task.cancel(false);
            }
        }
    }

    @Nullable
    private SwitchSuppressionHandler suppressionOf(final ProxyConnection pc) {
        for (final net.raphimc.viaproxy.proxy.packethandler.PacketHandler handler : pc.getPacketHandlers()) {
            if (handler instanceof SwitchSuppressionHandler switchSuppressionHandler) {
                return switchSuppressionHandler;
            }
        }
        return null;
    }

}
