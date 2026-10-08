package dev.connectplus.compat;

import java.net.SocketAddress;

/**
 * Verified mechanism (spike, docs/superpowers/notes/spike-netclient-reconnect.md):
 * NetClient.connect(address) only initializes a new channel when the channelFuture field is
 * null; resetting that field and calling connect again re-runs the channel initializer on a
 * fresh channel while keeping the same client object (and therefore the same ProxyConnection
 * identity that Client2ProxyHandler forwards packets through).
 */
public final class CompatChannelSwapper {

    private CompatChannelSwapper() {
    }

    /**
     * Rebinds the client to a fresh channel and connects it to the given address.
     * The caller is responsible for detaching handlers from and closing the previous channel first.
     *
     * @return the connect future of the new channel
     */
    public static io.netty.channel.ChannelFuture reconnect(final ChannelResettable client, final SocketAddress address) {
        client.resetChannel();
        if (client instanceof net.raphimc.netminecraft.netty.connection.NetClient netClient) {
            return netClient.connect(address);
        }
        throw new IllegalArgumentException("ChannelResettable must also extend NetClient: " + client.getClass().getName());
    }
}
