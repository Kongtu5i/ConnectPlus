package dev.connectplus.compat;

/**
 * Implemented by NetClient/ProxyConnection subclasses created by ConnectPlus
 * to allow rebinding the underlying channel to a fresh connection without reflection.
 */
public interface ChannelResettable {

    /**
     * Drops the reference to the current channel so the next {@code connect(address)}
     * call initializes a brand-new channel on the same client object.
     */
    void resetChannel();
}
