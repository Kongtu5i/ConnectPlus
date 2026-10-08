package dev.connectplus.compat;

import dev.connectplus.switching.SwitchEngine;
import dev.connectplus.switching.SwitchSuppressionHandler;
import io.netty.channel.ChannelHandlerContext;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.events.Proxy2ServerHandlerCreationEvent;
import net.raphimc.viaproxy.plugins.events.ProxySessionCreationEvent;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerChannelInitializer;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Replaces ViaProxy's plain proxy sessions with {@link SwitchableProxyConnection}
 * instances (same c2p, same initializer semantics) and installs the switch
 * suppression handler at the head of each session's packet handler list. Bedrock
 * sessions and legacy passthrough connections are left untouched (design F2.4).
 *
 * <p>The p2s handler created for switched connections guards the cascade close:
 * while a switch is running, a dying p2s is converted into a switch failure instead
 * of ViaProxy's default "close the client too" (design F3.3).</p>
 */
public class SwitchableSessionInstaller {

    private final SwitchEngine engine;

    public SwitchableSessionInstaller(@Nullable final SwitchEngine engine) {
        this.engine = engine;
    }

    @EventHandler
    public void onProxySessionCreation(final ProxySessionCreationEvent<ProxyConnection> event) {
        if (event.isLegacyPassthrough()) {
            return;
        }
        final ProxyConnection original = event.getProxySession();
        if (original.getClass() != ProxyConnection.class) {
            return; //Bedrock and other specialized sessions never hot switch
        }
        //The guarded p2s handler resolves the suppression lazily: the initializer runs
        //per channel, after the replacement (and its suppression) are fully built.
        final AtomicReference<SwitchSuppressionHandler> suppressionRef = new AtomicReference<>();
        final Supplier<SwitchSuppressionHandler> suppressionSupplier = suppressionRef::get;
        final SwitchableProxyConnection replacement = new SwitchableProxyConnection(
                new Proxy2ServerChannelInitializer(() -> ViaProxy.EVENT_MANAGER.call(
                        new Proxy2ServerHandlerCreationEvent(this.guardedHandler(suppressionSupplier), false)).getHandler()),
                original.getC2P());
        if (this.engine != null) {
            final SwitchSuppressionHandler suppression = new SwitchSuppressionHandler(replacement, this.engine);
            suppressionRef.set(suppression);
            replacement.getPacketHandlers().add(suppression);
        }
        event.setProxySession(replacement);
    }

    private Proxy2ServerHandler guardedHandler(final Supplier<SwitchSuppressionHandler> suppression) {
        return new Proxy2ServerHandler() {
            @Override
            public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
                final SwitchSuppressionHandler handler = suppression.get();
                if (handler != null && handler.guardChannelDeath(ctx.channel())) {
                    return;
                }
                super.channelInactive(ctx);
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
                final SwitchSuppressionHandler handler = suppression.get();
                if (handler != null && handler.guardChannelDeath(ctx.channel())) {
                    return;
                }
                super.exceptionCaught(ctx, cause);
            }
        };
    }

}
