package dev.connectplus.lobby.states;

import dev.connectplus.lobby.LobbyServerHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import net.lenni0451.lambdaevents.IExceptionHandler;
import net.lenni0451.lambdaevents.LambdaManager;
import net.lenni0451.lambdaevents.generator.LambdaMetaFactoryGenerator;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.netminecraft.packet.Packet;

import java.lang.invoke.MethodHandles;

/**
 * Base state machine for one lobby connection. Derived from MiniConnect's
 * StateHandler (MIT, Copyright (c) 2024 Lenni0451). Modified in this repo:
 * exposes the handler and event manager for the GUI ScreenHandler.
 */
public class StateHandler {

    protected final LobbyServerHandler handler;
    protected final Channel channel;
    protected final LambdaManager handlerManager;

    public StateHandler(final LobbyServerHandler handler, final Channel channel) {
        this.handler = handler;
        this.channel = channel;
        this.handlerManager = LambdaManager.basic(new LambdaMetaFactoryGenerator(MethodHandles.lookup()));

        this.handlerManager.setExceptionHandler(IExceptionHandler.throwing());
        this.handlerManager.register(this);
    }

    public LobbyServerHandler getHandler() {
        return this.handler;
    }

    public LambdaManager getHandlerManager() {
        return this.handlerManager;
    }

    public Channel getChannel() {
        return this.channel;
    }

    public void tick() {
    }

    public final void handle(final Packet packet) {
        this.handlerManager.call(packet);
    }

    public final void send(final Packet packet) {
        this.channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    public final void sendAndClose(final Packet packet) {
        this.channel.writeAndFlush(packet).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                future.channel().close();
            } else {
                future.channel().pipeline().fireExceptionCaught(future.cause());
            }
        });
    }

    protected final void setState(final ConnectionState state) {
        this.channel.attr(MCPipeline.PACKET_REGISTRY_ATTRIBUTE_KEY).get().setConnectionState(state);
        this.handler.update(this.channel, state);
    }
}
