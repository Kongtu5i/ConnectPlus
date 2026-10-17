package dev.connectplus.testutil;

import com.mojang.authlib.GameProfile;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.access.AccessConnectionRegistry;
import dev.connectplus.access.AccessSubject;
import dev.connectplus.compat.CPAttributeKeys;
import dev.connectplus.identity.ClientIdentity;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.netty.connection.MinecraftChannelInitializer;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

/** A real host session with in-memory client/backend channels instead of external servers. */
public final class OnlineConnection extends ProxyConnection implements AutoCloseable {
    public final EmbeddedChannel client;
    public final EmbeddedChannel backend = new EmbeddedChannel();
    public final UUID connectionId = UUID.randomUUID();
    public SocketAddress address = InetSocketAddress.createUnresolved("private.example.com", 25565);
    public ProtocolVersion serverVersion;

    public OnlineConnection(final AccessConnectionRegistry registry, final AccessSubject subject,
                            final ClientIdentity identity, final ProtocolVersion version) {
        this(new EmbeddedChannel(), registry, subject, identity, version);
    }

    private OnlineConnection(final EmbeddedChannel client, final AccessConnectionRegistry registry,
                             final AccessSubject subject, final ClientIdentity identity, final ProtocolVersion version) {
        super(new MinecraftChannelInitializer(Proxy2ServerHandler::new), client);
        this.client = client;
        client.attr(PROXY_CONNECTION_ATTRIBUTE_KEY).set(this);
        client.attr(CPAttributeKeys.CONNECTION_ID).set(this.connectionId);
        client.attr(CPAttributeKeys.CLIENT_IDENTITY).set(identity);
        this.setGameProfile(new GameProfile(subject.entryUuid(), subject.name()));
        this.setClientVersion(version);
        client.attr(net.raphimc.netminecraft.constants.MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY)
                .set(new net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry(true, version.getVersion()));
        this.backend.attr(net.raphimc.netminecraft.constants.MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY)
                .set(new net.raphimc.netminecraft.packet.registry.DefaultPacketRegistry(false, version.getVersion()));
        this.setC2pConnectionState(ConnectionState.PLAY);
        this.setP2sConnectionState(ConnectionState.PLAY);
        registry.register(this.connectionId, client);
        registry.update(this.connectionId, subject);
        registry.mark(this.connectionId, AccessConnectionRegistry.Status.ALLOWED);
    }

    @Override public SocketAddress getServerAddress() { return this.address; }
    @Override public ProtocolVersion getServerVersion() { return this.serverVersion; }
    @Override public Channel getChannel() { return this.backend; }
    @Override public void close() {
        this.client.finishAndReleaseAll();
        this.backend.finishAndReleaseAll();
    }
}
