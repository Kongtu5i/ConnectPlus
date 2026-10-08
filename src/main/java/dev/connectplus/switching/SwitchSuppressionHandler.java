package dev.connectplus.switching;

import dev.connectplus.logging.DebugLog;
import dev.connectplus.compat.AccountLoginPolicy;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.CoreMain;
import dev.connectplus.commands.LobbyCommands;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.netminecraft.packet.impl.common.S2CDisconnectPacket;
import net.raphimc.netminecraft.packet.impl.common.S2CTransferPacket;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigKeepAlivePacket;
import net.raphimc.netminecraft.packet.impl.configuration.C2SConfigCookieResponsePacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigCookieRequestPacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigFinishConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigKeepAlivePacket;
import net.raphimc.netminecraft.packet.impl.configuration.S2CConfigSelectKnownPacksPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginAcknowledgedPacket;
import net.raphimc.netminecraft.packet.impl.login.C2SLoginCookieResponsePacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginCookieRequestPacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginGameProfilePacket;
import net.raphimc.netminecraft.packet.impl.login.S2CLoginDisconnectPacket;
import dev.connectplus.lobby.screen.Messages;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayStartConfigurationPacket;
import net.raphimc.netminecraft.packet.impl.play.S2CPlayTransferPacket;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import net.raphimc.viaproxy.proxy.util.ChannelUtil;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;
import net.raphimc.vialegacy.protocol.release.r1_6_4tor1_7_2_5.storage.ProtocolMetadataStorage;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The chain-head PacketHandler implementing the handler x switching behavior matrix
 * (docs/superpowers/notes/handler-switch-matrix.md). Installed once per session (at
 * index 0 by the session installer); without a job it is a transparent passthrough.
 *
 * <p>SWITCHING: p2s packets are handed to the remaining handlers (so compression,
 * encryption and the backend login/configuration state machines keep advancing) and
 * then suppressed except for target configuration data. Audited modern clients enter
 * configuration, receive the target registries and acknowledge finish before the
 * backend enters play. Old-world client traffic is dropped. The translated JoinGame
 * flips the job to FORWARD after the configuration handshake.</p>
 *
 * <p>FORWARD on a server target: transparent except for the on-target commands
 * {@code /connect} and {@code /disconnect} (design F1.3). FORWARD into the lobby or
 * no job at all: fully transparent, the lobby handles its own commands.</p>
 */
public class SwitchSuppressionHandler extends PacketHandler {

    private final ClientConfiguration clientConfiguration;

    /**
     * Engine callbacks; the implementation (SwitchEngine) schedules timeouts/keepalives,
     * owns the failure fallback and the M5 FORWARD-phase resilience policies.
     */
    public interface Owner {

        void onSwitchComplete(ProxyConnection pc, SwitchJob job);

        void onSwitchFailed(ProxyConnection pc, SwitchJob job, @Nullable String reason);

        void onTargetCommand(ProxyConnection pc, LobbyCommands.Command command);

        /**
         * The target server kicked the player while forwarding (play disconnect, M5
         * F3.2); the engine applies the kick policy (back to the lobby by default).
         */
        void onForwardKick(ProxyConnection pc, SwitchJob job, String reason);

        /**
         * The p2s channel died while forwarding on a server target (M5 F3.1); the
         * engine applies the backend-down policy (back to the lobby or reconnect).
         */
        void onForwardDeath(ProxyConnection pc, SwitchJob job);

        /**
         * The target server sent a transfer packet while forwarding (M5 F7); the
         * engine applies the transfer policy (confirm/follow/ignore).
         */
        void onTargetTransfer(ProxyConnection pc, SwitchJob job, String host, int port);
    }

    private final AtomicReference<SwitchJob> currentJob = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean deathHandled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Owner owner;
    private final TabListState tabList = new TabListState();
    private final ScoreboardState scoreboard = new ScoreboardState();

    public SwitchSuppressionHandler(final ProxyConnection proxyConnection, final Owner owner) {
        super(proxyConnection);
        this.owner = owner;
        this.clientConfiguration = new ClientConfiguration(proxyConnection, this.currentJob::get, this::fail);
    }

    /**
     * Installs a new job; returns null (and changes nothing) when a switch is already
     * running on this connection (design F2.3). Terminal (FORWARD/FAILED) jobs are replaced.
     */
    @Nullable
    public SwitchJob begin(final SwitchJob job) {
        for (;;) {
            final SwitchJob current = this.currentJob.get();
            if (current != null && current.isSwitching()) {
                return null;
            }
            if (this.currentJob.compareAndSet(current, job)) {
                //A fresh job means a fresh p2s channel: the death marker of the old one
                //must not swallow that channel's callbacks
                this.deathHandled.set(false);
                this.clientConfiguration.begin(job);
                return job;
            }
        }
    }

    /**
     * The current job, or null while idle.
     */
    @Nullable
    public SwitchJob currentJob() {
        return this.currentJob.get();
    }

    void pauseFrontendReads() { this.clientConfiguration.pauseReads(); }

    /**
     * Called by the guarded Proxy2ServerHandler wrapper when the p2s channel died.
     * During SWITCHING the death is converted into a switch failure (the engine falls
     * back to the lobby). During FORWARD on a server target the death is handed to the
     * engine's backend-down policy (M5 F3.1) and the event is swallowed so ViaProxy's
     * cascade c2p close never happens (design F3.3). Any other state (idle, a completed
     * lobby switch) keeps the stock ViaProxy behavior.
     *
     * @return true when the event was consumed and must not propagate
     */
    public boolean guardChannelDeath(final Channel deadP2s) {
        if (this.proxyConnection.getChannel() != deadP2s) {
            return false;
        }
        if (this.proxyConnection.getC2P() == null || !this.proxyConnection.getC2P().isActive()
                || Boolean.TRUE.equals(this.proxyConnection.getC2P().attr(dev.connectplus.compat.CPAttributeKeys.ACCESS_DENIED).get())) {
            return false; // Client exit closes the backend normally; there is nobody to recover.
        }
        if (this.deathHandled.get()) {
            //The real environment fires exceptionCaught AND channelInactive for one
            //death; every callback after the first must be swallowed as well, or the
            //stock cascade would close the client connection the recovery preserves
            //(V3 manual verification finding)
            return true;
        }
        final SwitchJob job = this.currentJob.get();
        if (job == null) {
            return false;
        }
        if (job.isSwitching()) {
            this.pauseFrontendReads();
            this.deathHandled.set(true);
            this.fail(job, "backend connection lost during switch");
            return true;
        }
        if (!job.target().lobby()) {
            this.pauseFrontendReads();
            //A completed lobby switch has no job installed anymore (compareAndSet to
            //null on completion), so anything reaching here is a server FORWARD death.
            //Consume the job first: exceptionCaught + channelInactive both arrive for
            //one death, and only one of them may start the recovery (review finding 2).
            this.deathHandled.set(true);
            if (this.currentJob.compareAndSet(job, null)) {
                this.owner.onForwardDeath(this.proxyConnection, job);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean handleC2P(final Packet packet, final List<ChannelFutureListener> listeners) {
        final SwitchJob job = this.currentJob.get();
        if (this.clientConfiguration.waitingForClient() && (job == null || job.isFailed())) {
            //An ACK can arrive between a failed attempt and its recovery job.
            //It still belongs to the frontend, never to the dead backend.
            return this.clientConfiguration.handleClient(packet, job);
        }
        if (job == null) {
            return this.lobbySuggestions(packet);
        }
        if (job.isSwitching()) {
            if (this.clientConfiguration.enabled()) return this.clientConfiguration.handleClient(packet, job);
            //The client's old traffic (movement, clicks, keepalive echoes) means nothing
            //to the new backend; keepalives for the client are generated by the engine.
            return false;
        }
        if (job.target().lobby()) {
            return this.lobbySuggestions(packet); //Back in the lobby: tab completion is ours.
        }
        this.clientConfiguration.observeClient(packet);
        return this.proxyConnection.getC2pConnectionState() != ConnectionState.PLAY
                || this.interceptTargetCommands(packet); //Only PLAY packets can be chat/commands.
    }

    /**
     * Lobby-state handling of the client's tab completion (M5): the suggestion request
     * is answered by the proxy (the lobby has no such handling), everything else
     * passes through untouched.
     */
    private boolean lobbySuggestions(final Packet packet) {
        if (!this.answerSuggestions(packet)) {
            return true;
        }
        return false; //the request was consumed and answered
    }

    /**
     * Synthesizes the suggestions response for a lobby-state command suggestion
     * request; a no-op for every other packet. The lobby only owns the two exit
     * commands, so only those are offered. The response replaces the whole typed
     * input (start 0, length = input length — the suggestion texts carry the
     * leading slash) and every match carries the no-tooltip marker the client
     * expects per entry. An unmatched input still gets an (empty) response so the
     * client clears its list. Pre-1.13 clients use a different (transaction-id-less)
     * tab-complete format, so they keep the plain passthrough.
     *
     * @return true when the packet was a suggestion request and was answered
     */
    private boolean answerSuggestions(final Packet packet) {
        if (!(packet instanceof UnknownPacket unknownPacket)
                || this.proxyConnection.getClientVersion().olderThan(ProtocolVersion.v1_13)
                || unknownPacket.packetId != MCPackets.C2S_COMMAND_SUGGESTION.getId(this.proxyConnection.getClientVersion().getVersion())) {
            return false;
        }
        final int transactionId;
        final String input;
        try {
            final ByteBuf buf = Unpooled.wrappedBuffer(unknownPacket.data);
            transactionId = PacketTypes.readVarInt(buf);
            input = PacketTypes.readString(buf, 256);
        } catch (final Exception e) {
            //Malformed client input must never close the connection (F3.3): ignore it,
            //mirroring readFirstString's defensive parsing of chat packets.
            DebugLog.log("Ignored a malformed command suggestion request: {}", e.toString());
            return true;
        }
        if (input == null) {
            return true;
        }
        final List<String> suggestions = new java.util.ArrayList<>();
        for (final String name : LobbyCommands.EXIT_NAMES) {
            final String suggestion = "/" + name;
            if (suggestion.startsWith(input)) {
                suggestions.add(suggestion);
            }
        }
        final ByteBuf response = Unpooled.buffer();
        PacketTypes.writeVarInt(response, transactionId);
        PacketTypes.writeVarInt(response, 0); //start of the replaced range (the input including its slash)
        PacketTypes.writeVarInt(response, input.length()); //length of the replaced range
        PacketTypes.writeVarInt(response, suggestions.size());
        for (final String suggestion : suggestions) {
            PacketTypes.writeString(response, suggestion);
            response.writeBoolean(false); //per-entry no-tooltip marker
        }
        this.proxyConnection.getC2P().writeAndFlush(
                new UnknownPacket(MCPackets.S2C_COMMAND_SUGGESTIONS.getId(this.proxyConnection.getClientVersion().getVersion()),
                        ByteBufUtil.getBytes(response)));
        return true;
    }

    /**
     * The M5 FORWARD-phase resilience intercepts on a server target (F3.1/F3.2/F7).
     * Everything not matched here falls through to the normal command interception.
     */
    private boolean interceptForwardPackets(final Packet packet, final SwitchJob job) {
        if (packet instanceof S2CDisconnectPacket kick) {
            //play disconnect incl. the S2CPlayDisconnectPacket subclass; login
            //disconnects are a separate class and only matter while SWITCHING
            if (!"disconnect".equalsIgnoreCase(this.kickPolicy())) {
                //The server closes this p2s right after the kick; its death must not
                //trigger the backend-down policy next to the kick recovery, let alone
                //fail the freshly started lobby fallback (V4 verification finding).
                this.deathHandled.set(true);
                //Skip the remaining handlers entirely (the stock DisconnectPacketHandler
                //only logs, but the kick must not take its vanilla c2p-close path).
                this.owner.onForwardKick(this.proxyConnection, job, kick.reason.asLegacyFormatString());
                return false;
            }
            return true;
        }
        if (packet instanceof S2CTransferPacket transfer) {
            //Never run the remaining handlers here: ViaProxy's TransferPacketHandler
            //would arm a temp redirect on the c2p (matrix rule, Review Focus 3).
            if (!"ignore".equalsIgnoreCase(dev.connectplus.config.CPConfig.transferPolicy)) {
                //confirm/follow recover the player themselves; the server closing this
                //p2s right after the transfer must not race that recovery
                this.deathHandled.set(true);
            }
            this.owner.onTargetTransfer(this.proxyConnection, job, transfer.host, transfer.port);
            return false;
        }
        return this.interceptTargetCommands(packet);
    }

    /**
     * The kick policy; a separate method so tests can pin the behavior per policy value.
     */
    String kickPolicy() {
        return dev.connectplus.config.CPConfig.kickPolicy;
    }

    /**
     * On a target server only the exit commands ({@code /disconnect}, {@code /dc})
     * are intercepted (design F1.3); every other command — including the removed
     * lobby commands and usage-error exit inputs — passes through untouched.
     * NetMinecraft has no typed play chat packets, so they are detected by packet id
     * and the leading string field — the same approach ViaProxy's ChatSignaturePacketHandler uses.
     */
    private boolean interceptTargetCommands(final Packet packet) {
        if (!(packet instanceof UnknownPacket unknownPacket)) {
            return true;
        }
        final int protocolVersion = this.proxyConnection.getClientVersion().getVersion();
        final String text;
        if (unknownPacket.packetId == MCPackets.C2S_CHAT.getId(protocolVersion)) {
            final String message = this.readFirstString(unknownPacket);
            if (message == null || !message.startsWith("/")) {
                return true;
            }
            text = message;
        } else if (unknownPacket.packetId == MCPackets.C2S_CHAT_COMMAND.getId(protocolVersion)
                || unknownPacket.packetId == MCPackets.C2S_CHAT_COMMAND_SIGNED.getId(protocolVersion)) {
            final String message = this.readFirstString(unknownPacket);
            if (message == null) {
                return true;
            }
            text = "/" + message;
        } else {
            return true;
        }
        if (LobbyCommands.parse(text).type() == LobbyCommands.Type.DISCONNECT) {
            this.owner.onTargetCommand(this.proxyConnection, LobbyCommands.DISCONNECT);
            return false;
        }
        return true;
    }

    @Nullable
    private String readFirstString(final UnknownPacket packet) {
        try {
            final ByteBuf buf = Unpooled.wrappedBuffer(packet.data);
            return PacketTypes.readString(buf, 256);
        } catch (final Exception e) {
            return null;
        }
    }

    /** Changes only the payload passed through the documented packet-handler API. */
    private void appendExitCommandDeclarations(final Packet packet) {
        final ProtocolVersion protocol = this.proxyConnection.getClientVersion();
        if (this.proxyConnection.getP2sConnectionState() != ConnectionState.PLAY
                || protocol.getVersionType() != com.viaversion.viaversion.api.protocol.version.VersionType.RELEASE
                || protocol.isSnapshot() || protocol.olderThan(ProtocolVersion.v1_13)
                || protocol.getOriginalVersion() > 777
                || !(packet instanceof UnknownPacket raw)
                || raw.packetId != MCPackets.S2C_COMMANDS.getId(protocol.getVersion())) return;
        raw.data = dev.connectplus.commands.ExitCommandTree.append(raw.data);
    }

    @Override
    public boolean handleP2S(final Packet packet, final List<ChannelFutureListener> listeners) {
        final SwitchJob job = this.currentJob.get();
        if (job != null && !job.isSwitching() && !job.target().lobby()) {
            this.appendExitCommandDeclarations(packet);
        }
        if (job != null && !job.isSwitching()) this.clientConfiguration.observeServer(packet, job);
        if (job == null) {
            return this.forwardClientState(packet, listeners);
        }
        if (!job.isSwitching()) {
            if (job.target().lobby()) {
                return this.forwardClientState(packet, listeners);
            }
            return this.interceptForwardPackets(packet, job) && this.forwardClientState(packet, listeners);
        }
        try {
            //Diagnostic trace of the backend's switch traffic: the state progression
            //(login -> configuration -> play) is exactly where a remote deployment can
            //stall, and the step lines pin which side stopped talking.
            final ConnectionState p2sState = this.proxyConnection.getP2sConnectionState();
            if (DebugLog.enabled()) {
                final String packetKind = packet instanceof net.raphimc.netminecraft.packet.UnknownPacket unknown
                        ? "UnknownPacket(0x" + Integer.toHexString(unknown.packetId) + ", " + (unknown.data == null ? 0 : unknown.data.length) + "B)"
                        : packet.getClass().getSimpleName();
                DebugLog.log("[Switch:{}] p2s {} {}", job.target().playerName(), p2sState, packetKind);
            }
            if (p2sState == ConnectionState.CONFIGURATION && packet instanceof net.raphimc.netminecraft.packet.UnknownPacket rawConfig) {
                //Configuration-phase packets the p2s registry does not type (netminecraft
                //ships no class for the config ping, and version skew can leave others
                //untyped too) must still be answered, or the backend waits forever:
                //Velocity pings every client in the configuration phase and stalls the
                //whole switch until the pong arrives (public-deployment finding, the
                //"UnknownPacket(0x5, 4B)" in the trace = the config ping of protocol 774).
                //The Via codec translates before PacketCodec decodes incoming traffic
                //and after it encodes replies. Both packet IDs here are therefore in
                //the CLIENT version, even when the backend speaks a different version.
                //A body size alone cannot identify a packet (e.g. custom payloads).
                final int version = this.proxyConnection.getClientVersion().getVersion();
                final Channel p2s = this.proxyConnection.getChannel();
                final int pingId = MCPackets.S2C_CONFIG_PING.getId(version);
                final int keepAliveId = MCPackets.S2C_CONFIG_KEEP_ALIVE.getId(version);
                final int knownPacksId = MCPackets.S2C_CONFIG_SELECT_KNOWN_PACKS.getId(version);
                final int finishId = MCPackets.S2C_CONFIG_FINISH_CONFIGURATION.getId(version);
                final int rawId = rawConfig.packetId;
                final int rawSize = rawConfig.data == null ? 0 : rawConfig.data.length;
                DebugLog.log("[Switch:{}] raw config packet id=0x{} ({}B), expected for v{}: ping=0x{} keepalive=0x{} knownPacks=0x{} finish=0x{}",
                        job.target().playerName(), Integer.toHexString(rawId), rawSize, version,
                        Integer.toHexString(pingId), Integer.toHexString(keepAliveId), Integer.toHexString(knownPacksId), Integer.toHexString(finishId));
                if (rawId == pingId) {
                    if (rawSize != 4) {
                        throw new IllegalArgumentException("Invalid configuration ping payload size: " + rawSize);
                    }
                    //the pong echoes the ping's int payload
                    DebugLog.log("[Switch:{}] answering config ping with pong ({}B)", job.target().playerName(), rawSize);
                    p2s.writeAndFlush(new net.raphimc.netminecraft.packet.UnknownPacket(
                            MCPackets.C2S_CONFIG_PONG.getId(version), rawConfig.data));
                    return false;
                }
                if (rawId == keepAliveId) {
                    if (rawSize != 8) {
                        throw new IllegalArgumentException("Invalid configuration keepalive payload size: " + rawSize);
                    }
                    //echo the long payload
                    DebugLog.log("[Switch:{}] answering config keepalive ({}B)", job.target().playerName(), rawSize);
                    p2s.writeAndFlush(new net.raphimc.netminecraft.packet.UnknownPacket(
                            MCPackets.C2S_CONFIG_KEEP_ALIVE.getId(version), rawConfig.data));
                    return false;
                }
                if (rawId == knownPacksId) {
                    //an empty known-packs answer: the backend then sends its full registry data
                    DebugLog.log("[Switch:{}] answering known-packs with an empty list", job.target().playerName());
                    p2s.writeAndFlush(new net.raphimc.netminecraft.packet.UnknownPacket(
                            MCPackets.C2S_CONFIG_SELECT_KNOWN_PACKS.getId(version), new byte[]{0}));
                    return false;
                }
                if (rawId == finishId) {
                    DebugLog.log("[Switch:{}] configuration finish received ({} ms)", job.target().playerName(), job.elapsedMillis());
                    final var finish = new S2CConfigFinishConfigurationPacket();
                    this.runRemainingHandlers(finish, listeners);
                    if (this.clientConfiguration.enabled()) this.clientConfiguration.accept(job, finish);
                    else this.acknowledgeConfigurationFinish();
                    return false;
                }
            }
            if (packet instanceof net.raphimc.netminecraft.packet.impl.login.S2CLoginHelloPacket encryptionRequest) {
                // Match ViaProxy's authentication decision. Before 1.20.5 there
                // is no wire flag, so a decoded false still requires authentication.
                boolean authenticate = this.proxyConnection.getClientVersion().olderThan(ProtocolVersion.v1_20_5)
                        || encryptionRequest.authenticate;
                final var serverVersion = this.proxyConnection.getServerVersion();
                if (authenticate && serverVersion != null && serverVersion.olderThanOrEqualTo(LegacyProtocolVersion.r1_6_4)) {
                    final var metadata = this.proxyConnection.getUserConnection().get(ProtocolMetadataStorage.class);
                    if (metadata != null) authenticate = metadata.isAuthenticate();
                }
                // With authentication required and no permitted
                //Microsoft account on this job, ViaProxy's own joinServer handling
                //would kick and CLOSE the client connection mid-switch (the
                //"requires a valid authentication mode" kick of the public-deployment
                //finding). Fail the job here instead — the player is still safely in
                //the lobby and gets a real failure notice. The raw request must not
                //reach the remaining handlers (the kick lives there).
                DebugLog.log("[Switch:{}] encryption request (authenticate={}, jobAuthenticated={}, {} ms)",
                        job.target().playerName(), authenticate, job.authenticated(), job.elapsedMillis());
                if (authenticate && (!job.authenticated()
                        || !AccountLoginPolicy.isAllowedForConnection(this.proxyConnection.getC2P())
                        || !SwitchEngine.accountMayConnect(job.target().connectionId()))) {
                    this.fail(job, Messages.Commands.SwitchNeedsAccount.english());
                    this.proxyConnection.getChannel().close();
                    return false;
                }
                this.runRemainingHandlers(packet, listeners);
                DebugLog.log("[Switch:{}] encryption request answered, waiting for the login success ({} ms)",
                        job.target().playerName(), job.elapsedMillis());
                return false;
            }
            if (this.isJoinGame(packet)) {
                // Build every reset before committing FORWARD. Truncated/unaudited
                // joins fail through the existing recovery path without a partial reset.
                final int clientVersion = this.proxyConnection.getClientVersion().getVersion();
                final List<UnknownPacket> resets = WorldReset.fromJoin((UnknownPacket) packet, clientVersion);
                if (!this.runRemainingHandlers(packet, listeners)) {
                    return false;
                }
                if (job.markForward()) {
                    CoreMain.logger().info("Switch of {} to {} completed in {} ms",
                            job.target().playerName(), job.target().address(), job.elapsedMillis());
                    final boolean legacy = this.proxyConnection.getClientVersion().olderThan(ProtocolVersion.v1_9);
                    final ChannelFutureListener[] writeListeners = listeners.toArray(new ChannelFutureListener[0]);
                    this.onClientLoop(() -> {
                        for (final UnknownPacket remove : this.scoreboard.clear(clientVersion)) {
                            this.proxyConnection.getC2P().writeAndFlush(remove)
                                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                        }
                        // JoinGame/Respawn do not clear client player profiles.
                        // Remove old entries before the next world's join and tab adds.
                        for (final UnknownPacket remove : this.tabList.clear(clientVersion)) {
                            this.proxyConnection.getC2P().writeAndFlush(remove)
                                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                        }
                        //1.7/1.8 must not receive a second JoinGame (manual verification).
                        //1.9+ retains JoinGame to update its entity ID and registry data.
                        if (!legacy) this.proxyConnection.getC2P().writeAndFlush(packet).addListeners(writeListeners);
                        for (int i = 0; i < resets.size(); i++) {
                            final var write = this.proxyConnection.getC2P().writeAndFlush(resets.get(i))
                                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                            if (legacy && i == resets.size() - 1) write.addListeners(writeListeners);
                        }
                    });
                    DebugLog.log("[Switch:{}] world reset sent (client v{}, respawns={}, keep=0)",
                            job.target().playerName(), clientVersion, resets.size());
                    this.owner.onSwitchComplete(this.proxyConnection, job);
                    if (job.target().lobby()) {
                        this.currentJob.compareAndSet(job, null); //lobby owns the commands again
                    }
                    return false; //ordered reset paths already wrote the client traffic
                }
                // A concurrent failure won the transition. Do not leak a bare join
                // or invoke the handlers again through the normal forwarding chain.
                return false;
            }

            if (packet instanceof S2CLoginDisconnectPacket loginKick) {
                this.runRemainingHandlers(packet, listeners);
                this.fail(job, loginKick.reason.asLegacyFormatString());
                return false;
            }
            if (packet instanceof S2CDisconnectPacket kick) {
                this.deathHandled.set(true); //the server closes the p2s right after
                this.runRemainingHandlers(packet, listeners);
                this.fail(job, kick.reason.asLegacyFormatString());
                return false;
            }

            if (packet instanceof S2CLoginGameProfilePacket) {
                DebugLog.log("[Switch:{}] login success received ({} ms)", job.target().playerName(), job.elapsedMillis());
                this.runRemainingHandlers(packet, listeners);
                this.advanceAfterLoginSuccess();
                this.clientConfiguration.loggedIn(job);
                return false;
            }
            if (packet instanceof S2CConfigFinishConfigurationPacket) {
                DebugLog.log("[Switch:{}] configuration finish received ({} ms)", job.target().playerName(), job.elapsedMillis());
                this.runRemainingHandlers(packet, listeners);
                if (this.clientConfiguration.enabled()) this.clientConfiguration.accept(job, packet);
                else this.acknowledgeConfigurationFinish();
                return false;
            }
            if (packet instanceof S2CConfigKeepAlivePacket keepAlive) {
                this.proxyConnection.getChannel().writeAndFlush(new C2SConfigKeepAlivePacket(keepAlive.id));
                this.runRemainingHandlers(packet, listeners);
                return false;
            }
            if (packet instanceof S2CConfigSelectKnownPacksPacket) {
                //Vanilla 1.20.5+ servers stall the whole configuration phase until the
                //client reports its known packs (manual verification finding: switching
                //to a real vanilla server timed out). An empty list = "knows nothing",
                //so the backend sends its full registry data.
                this.proxyConnection.getChannel().writeAndFlush(
                        new net.raphimc.netminecraft.packet.impl.configuration.C2SConfigSelectKnownPacksPacket(java.util.List.of()));
                this.runRemainingHandlers(packet, listeners);
                return false;
            }
            if (packet instanceof S2CLoginCookieRequestPacket loginCookie) {
                this.proxyConnection.getChannel().writeAndFlush(new C2SLoginCookieResponsePacket(loginCookie.key, null));
                return false;
            }
            if (packet instanceof S2CConfigCookieRequestPacket configCookie) {
                this.proxyConnection.getChannel().writeAndFlush(new C2SConfigCookieResponsePacket(configCookie.key, null));
                return false;
            }
            if (packet instanceof S2CTransferPacket || packet instanceof S2CPlayTransferPacket
                    || packet instanceof S2CPlayStartConfigurationPacket) {
                //Dropped without running the remaining handlers: a transfer must not arm
                //ViaProxy's temp redirect during a switch, and a start-configuration would
                //disable p2s auto read with no client ack to ever restore it (matrix).
                return false;
            }

            if (this.proxyConnection.getP2sConnectionState() == ConnectionState.CONFIGURATION
                    && this.clientConfiguration.enabled()) {
                if (this.runRemainingHandlers(packet, listeners)) this.clientConfiguration.accept(job, packet);
                return false;
            }
            this.runRemainingHandlers(packet, listeners);
            return false;
        } catch (final Exception e) {
            this.fail(job, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * The translated JoinGame arrives as an id-matched UnknownPacket while the p2s is in
     * PLAY (the same detection ViaProxy's ChatSignaturePacketHandler uses).
     */
    private boolean isJoinGame(final Packet packet) {
        return packet instanceof UnknownPacket unknownPacket
                && this.proxyConnection.getP2sConnectionState() == ConnectionState.PLAY
                && unknownPacket.packetId == MCPackets.S2C_LOGIN.getId(this.proxyConnection.getClientVersion().getVersion());
    }

    /**
     * Runs the remaining packet handlers so ViaProxy's own state machines (compression,
     * encryption, login, configuration) advance normally during the switch.
     */
    private boolean runRemainingHandlers(final Packet packet, final List<ChannelFutureListener> listeners) throws Exception {
        for (final PacketHandler handler : this.proxyConnection.getPacketHandlers()) {
            if (handler == this) {
                continue;
            }
            if (!handler.handleP2S(packet, listeners)) {
                return false;
            }
        }
        return true;
    }

    private boolean forwardClientState(final Packet packet, final List<ChannelFutureListener> listeners) {
        final var protocol = this.proxyConnection.getClientVersion();
        if (this.proxyConnection.getP2sConnectionState() != ConnectionState.PLAY
                || protocol.getVersionType() != com.viaversion.viaversion.api.protocol.version.VersionType.RELEASE
                || protocol.isSnapshot() || !(packet instanceof UnknownPacket raw)
                || !(TabListState.isPlayerList(raw, protocol.getOriginalVersion())
                    || ScoreboardState.isScoreboard(raw, protocol.getOriginalVersion()))) return true;
        try {
            if (!this.runRemainingHandlers(packet, listeners)) return false;
        } catch (Exception e) {
            throw new IllegalStateException("Client-state forwarding handler failed", e);
        }
        final ChannelFutureListener[] writeListeners = listeners.toArray(new ChannelFutureListener[0]);
        this.onClientLoop(() -> {
            try {
                if (TabListState.isPlayerList(raw, protocol.getOriginalVersion())) this.tabList.observe(raw, protocol.getOriginalVersion());
                else this.scoreboard.observe(raw, protocol.getOriginalVersion());
            } catch (RuntimeException e) {
                // A malformed/unknown body remains native client traffic. Never
                // invent an ID, partially mutate the tracker or alter its packet.
                CoreMain.logger().warn("Could not track client state for client v{}: {}", protocol.getOriginalVersion(), e.toString());
            }
            this.proxyConnection.getC2P().writeAndFlush(packet).addListeners(writeListeners);
        });
        return false; // the public handler chain and final client write ran once
    }

    private void onClientLoop(final Runnable action) {
        final Channel client = this.proxyConnection.getC2P();
        if (client.eventLoop().inEventLoop()) action.run();
        else client.eventLoop().execute(action);
    }

    /**
     * Takes over the state advancement that ViaProxy normally hangs onto the c2p write
     * of the login success (which is suppressed): the p2s follows the client version's
     * progression (the p2s registry and the Via codec are client-version based), the
     * client state is never touched.
     */
    private void advanceAfterLoginSuccess() {
        final Channel p2s = this.proxyConnection.getChannel();
        if (this.proxyConnection.getClientVersion().newerThanOrEqualTo(ProtocolVersion.v1_20_2)) {
            //The proxy plays the client's role: acknowledge the login towards the new
            //backend while the registry is still in LOGIN, then advance to CONFIGURATION.
            p2s.writeAndFlush(new C2SLoginAcknowledgedPacket()).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    this.proxyConnection.setP2sConnectionState(ConnectionState.CONFIGURATION);
                    this.restoreP2sAutoRead(p2s);
                }
            });
        } else {
            this.proxyConnection.setP2sConnectionState(ConnectionState.PLAY);
            this.restoreP2sAutoRead(p2s);
        }
    }

    /**
     * The proxy plays the client's role for the configuration finish as well.
     */
    private void acknowledgeConfigurationFinish() {
        final Channel p2s = this.proxyConnection.getChannel();
        p2s.writeAndFlush(new C2SConfigFinishConfigurationPacket()).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                this.proxyConnection.setP2sConnectionState(ConnectionState.PLAY);
                this.restoreP2sAutoRead(p2s);
            }
        });
    }

    /**
     * The remaining handlers (login success / configuration finish) disabled auto read
     * on the p2s expecting the suppressed client write to restore it; restore defensively
     * (never throws when the stack is already balanced).
     */
    private void restoreP2sAutoRead(final Channel p2s) {
        if (p2s.isActive() && !p2s.config().isAutoRead()) {
            try {
                ChannelUtil.restoreAutoRead(p2s);
            } catch (final IllegalStateException e) {
                CoreMain.logger().warn("Switch auto read restore raced on the p2s channel", e);
            }
        }
    }

    private void fail(final SwitchJob job, @Nullable final String reason) {
        if (job.fail(reason)) {
            CoreMain.logger().warn("Switch of {} to {} failed after {} ms: {}",
                    job.target().playerName(), job.target().address(), job.elapsedMillis(), reason == null ? "unknown reason" : reason);
            this.owner.onSwitchFailed(this.proxyConnection, job, reason);
        }
    }

}
