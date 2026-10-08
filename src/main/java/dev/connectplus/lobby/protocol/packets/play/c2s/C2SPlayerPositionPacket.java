package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;

/** Position movement after ViaVersion translates to the lobby's fixed 1.21.4 protocol. */
public class C2SPlayerPositionPacket implements Packet {
    public final boolean rotation;
    public double x, y, z;
    public float yaw, pitch;
    public int flags;

    public C2SPlayerPositionPacket() {
        this(false);
    }

    protected C2SPlayerPositionPacket(final boolean rotation) {
        this.rotation = rotation;
    }

    @Override public void read(final ByteBuf buf, final int protocolVersion) {
        x = buf.readDouble();
        y = buf.readDouble();
        z = buf.readDouble();
        if (rotation) {
            yaw = buf.readFloat();
            pitch = buf.readFloat();
        }
        flags = buf.readUnsignedByte();
    }

    @Override public void write(final ByteBuf buf, final int protocolVersion) {
        buf.writeDouble(x).writeDouble(y).writeDouble(z);
        if (rotation) buf.writeFloat(yaw).writeFloat(pitch);
        buf.writeByte(flags);
    }
}
