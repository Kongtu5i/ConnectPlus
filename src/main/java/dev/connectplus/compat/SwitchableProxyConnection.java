package dev.connectplus.compat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

/**
 * ProxyConnection subclass exposing the channel reset needed for seamless server
 * switches. Verified mechanism (spike, docs/superpowers/notes/spike-netclient-reconnect.md):
 * the channelFuture field is protected in NetClient, so resetting it and calling
 * connectToServer again re-runs the channel initializer on a fresh channel — the new
 * Via translation pipeline is built for the new target version — while the
 * ProxyConnection object identity (and therefore its packet handler list and the
 * Client2ProxyHandler reference) stays the same.
 */
public class SwitchableProxyConnection extends ProxyConnection implements ChannelResettable {

    public SwitchableProxyConnection(final ChannelInitializer<Channel> channelInitializer, final Channel c2p) {
        super(channelInitializer, c2p);
    }

    @Override
    public void resetChannel() {
        this.channelFuture = null;
    }

}
