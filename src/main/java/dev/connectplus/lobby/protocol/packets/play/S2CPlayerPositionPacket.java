package dev.connectplus.lobby.protocol.packets.play;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * Derived from MiniConnect's S2CPlayerPositionPacket (MIT, Copyright (c) 2024 Lenni0451).
 */
public class S2CPlayerPositionPacket implements Packet {

    public int teleportId;
    public double posX;
    public double posY;
    public double posZ;
    public double velocityX;
    public double velocityY;
    public double velocityZ;
    public float yaw;
    public float pitch;
    public int flagMask;

    public S2CPlayerPositionPacket(final int teleportId, final double posX, final double posY, final double posZ, final double velocityX, final double velocityY, final double velocityZ, final float yaw, final float pitch, final int flagMask) {
        this.teleportId = teleportId;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.flagMask = flagMask;
    }

    public S2CPlayerPositionPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.teleportId = PacketTypes.readVarInt(byteBuf);
        this.posX = byteBuf.readDouble();
        this.posY = byteBuf.readDouble();
        this.posZ = byteBuf.readDouble();
        this.velocityX = byteBuf.readDouble();
        this.velocityY = byteBuf.readDouble();
        this.velocityZ = byteBuf.readDouble();
        this.yaw = byteBuf.readFloat();
        this.pitch = byteBuf.readFloat();
        this.flagMask = byteBuf.readInt();
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeVarInt(byteBuf, this.teleportId);
        byteBuf.writeDouble(this.posX);
        byteBuf.writeDouble(this.posY);
        byteBuf.writeDouble(this.posZ);
        byteBuf.writeDouble(this.velocityX);
        byteBuf.writeDouble(this.velocityY);
        byteBuf.writeDouble(this.velocityZ);
        byteBuf.writeFloat(this.yaw);
        byteBuf.writeFloat(this.pitch);
        byteBuf.writeInt(this.flagMask);
    }
}
