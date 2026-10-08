package dev.connectplus.lobby.protocol.packets.play.c2s;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * The 1.19.1+ "signed chat command" variant ({@code chat_command_signed}). A
 * 1.7–1.19 client's slash command is translated by ViaVersion into this packet
 * even though it carries no real signature (manual verification V7 finding):
 * the lobby must parse it like {@link C2SChatCommandPacket} or every command
 * from such clients silently disappears. Only the command string is read; the
 * timestamp/salt/argument-signature/acknowledgment trailer is skipped — the
 * lobby never validates signatures.
 */
public class C2SChatCommandSignedPacket implements Packet {

    private static final int ACKNOWLEDGMENT_BYTES = 3;

    public String message;

    public C2SChatCommandSignedPacket(final String message) {
        this.message = message;
    }

    public C2SChatCommandSignedPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.message = PacketTypes.readString(byteBuf, Short.MAX_VALUE);
        //1.21.4: timestamp, salt, argument names + fixed 256-byte signatures,
        //offset varint, fixed three-byte acknowledgments. No preview flag.
        byteBuf.readLong();
        byteBuf.readLong();
        final int argumentCount = PacketTypes.readVarInt(byteBuf);
        for (int i = 0; i < argumentCount; i++) {
            PacketTypes.readString(byteBuf, 256);
            byteBuf.skipBytes(256);
        }
        PacketTypes.readVarInt(byteBuf); //offset
        byteBuf.skipBytes(ACKNOWLEDGMENT_BYTES);
        byteBuf.skipBytes(byteBuf.readableBytes()); //optional version tails
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeString(byteBuf, this.message);
        byteBuf.writeLong(0L);
        byteBuf.writeLong(0L);
        PacketTypes.writeVarInt(byteBuf, 0);
        PacketTypes.writeVarInt(byteBuf, 0); //offset
        byteBuf.writeZero(ACKNOWLEDGMENT_BYTES);
    }
}
