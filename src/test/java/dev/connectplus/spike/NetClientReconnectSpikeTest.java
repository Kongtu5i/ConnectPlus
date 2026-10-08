package dev.connectplus.spike;

import dev.connectplus.compat.CompatChannelSwapper;
import dev.connectplus.compat.SwitchableNetClient;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetClientReconnectSpikeTest {

    private static final EventLoopGroup GROUP = new NioEventLoopGroup(2);

    @AfterAll
    static void shutdownGroup() {
        GROUP.shutdownGracefully();
    }

    private static InetSocketAddress startEchoServer() throws InterruptedException {
        final ServerBootstrap bootstrap = new ServerBootstrap()
                .group(GROUP, GROUP)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
                                ctx.writeAndFlush(msg);
                            }
                        });
                    }
                });
        return (InetSocketAddress) bootstrap.bind("127.0.0.1", 0).sync().channel().localAddress();
    }

    @Test
    void netClientSupportsReconnectAfterChannelReset() throws Exception {
        final InetSocketAddress serverA = startEchoServer();
        final InetSocketAddress serverB = startEchoServer();

        final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        final SwitchableNetClient client = new SwitchableNetClient(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final Channel ch) {
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
                        received.add(msg);
                    }
                });
            }
        });

        client.connect(serverA).syncUninterruptibly();
        client.getChannel().writeAndFlush(Unpooled.wrappedBuffer("ping".getBytes(StandardCharsets.UTF_8))).syncUninterruptibly();
        final Object first = received.poll(5, TimeUnit.SECONDS);
        assertEquals("ping", ((ByteBuf) first).toString(StandardCharsets.UTF_8));

        client.getChannel().close().syncUninterruptibly();
        CompatChannelSwapper.reconnect(client, serverB).syncUninterruptibly();

        client.getChannel().writeAndFlush(Unpooled.wrappedBuffer("ping2".getBytes(StandardCharsets.UTF_8))).syncUninterruptibly();
        final Object second = received.poll(5, TimeUnit.SECONDS);
        assertEquals("ping2", ((ByteBuf) second).toString(StandardCharsets.UTF_8));
    }
}
