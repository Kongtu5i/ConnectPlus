package dev.connectplus.resources;

/** A queued task whose reservation must be returned if shutdown discards it before execution. */
public interface DiscardableTask extends Runnable {
    void discard();
}
