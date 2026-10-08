package dev.connectplus.compat;

import io.netty.channel.ChannelInitializer;

/**
 * NetClient subclass that exposes the channel reset needed for seamless server switches.
 * The channelFuture field is protected in NetClient, so resetting it is legal from here
 * without any reflection.
 */
public class SwitchableNetClient extends net.raphimc.netminecraft.netty.connection.NetClient implements ChannelResettable {

    public SwitchableNetClient(final ChannelInitializer<io.netty.channel.Channel> channelInitializer) {
        super(channelInitializer);
    }

    @Override
    public void resetChannel() {
        this.channelFuture = null;
    }
}
