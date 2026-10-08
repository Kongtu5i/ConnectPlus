package dev.connectplus.lobby.protocol.packets.play;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;

/**
 * Derived from MiniConnect's S2CPlayerAbilitiesPacket (MIT, Copyright (c) 2024 Lenni0451).
 */
public class S2CPlayerAbilitiesPacket implements Packet {

    public boolean invulnerable;
    public boolean isFlying;
    public boolean canFly;
    public boolean instabuild;
    public float flyingSpeed;
    public float walkingSpeed;

    public S2CPlayerAbilitiesPacket(final boolean invulnerable, final boolean isFlying, final boolean canFly, final boolean instabuild, final float flyingSpeed, final float walkingSpeed) {
        this.invulnerable = invulnerable;
        this.isFlying = isFlying;
        this.canFly = canFly;
        this.instabuild = instabuild;
        this.flyingSpeed = flyingSpeed;
        this.walkingSpeed = walkingSpeed;
    }

    public S2CPlayerAbilitiesPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        final int flags = byteBuf.readUnsignedByte();
        this.invulnerable = (flags & 0b1) != 0;
        this.isFlying = (flags & 0b10) != 0;
        this.canFly = (flags & 0b100) != 0;
        this.instabuild = (flags & 0b1000) != 0;
        this.flyingSpeed = byteBuf.readFloat();
        this.walkingSpeed = byteBuf.readFloat();
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        int flags = 0;
        if (this.invulnerable) flags |= 0b1;
        if (this.isFlying) flags |= 0b10;
        if (this.canFly) flags |= 0b100;
        if (this.instabuild) flags |= 0b1000;
        byteBuf.writeByte(flags);
        byteBuf.writeFloat(this.flyingSpeed);
        byteBuf.writeFloat(this.walkingSpeed);
    }
}
