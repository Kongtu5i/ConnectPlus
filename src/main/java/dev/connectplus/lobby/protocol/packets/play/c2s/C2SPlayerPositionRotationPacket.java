package dev.connectplus.lobby.protocol.packets.play.c2s;

/** A distinct registry class for Java's position-and-rotation packet ID. */
public final class C2SPlayerPositionRotationPacket extends C2SPlayerPositionPacket {
    public C2SPlayerPositionRotationPacket() {
        super(true);
    }
}
