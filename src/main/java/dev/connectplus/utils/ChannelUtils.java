package dev.connectplus.utils;

import io.netty.channel.Channel;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * <p>Derived from MiniConnect's ChannelUtils (MIT, Copyright (c) 2024 Lenni0451).</p>
 */
public final class ChannelUtils {

    private ChannelUtils() {
    }

    public static InetAddress getChannelAddress(final Channel channel) {
        return ((InetSocketAddress) channel.remoteAddress()).getAddress();
    }
}
