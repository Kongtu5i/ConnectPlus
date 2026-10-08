package dev.connectplus.lobby.protocol.packets.config;

import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

/**
 * The client settings packet (M6 F1.4): NetMinecraft 3.1.7 ships no typed class
 * for it, so the lobby defines its own to capture the client's locale for the
 * bilingual texts. The v1_21_4 wire layout is identical in the configuration
 * state (sent after login, the one that reaches the lobby first) and in the
 * play state (sent again when the client changes its settings), but the packet
 * registry rejects one packet class for two ids — the play-state re-send is the
 * separate subclass {@link C2SPlayClientInformationPacket} (M7).
 *
 * <p>The 1.21.2+ particleStatus tail is optional on read: ViaProxy rewrites the
 * packet without it for clients it translates (1.7 upgrades, 1.21.4 and older),
 * but forwards it untouched for clients it has no rewriter for on the
 * 1.21.4..1.21.11 path (public-deployment finding: a leftover byte was
 * misparsed by the packet codec as the next packet id and crashed the lobby
 * decode). Any unknown remaining bytes are consumed as a tail.</p>
 */
public class C2SClientInformationPacket implements Packet {

    public String locale;
    public int viewDistance;
    public int chatMode;
    public boolean chatColors;
    public int displayedSkinParts;
    public int mainHand;
    public boolean enableTextFiltering;
    public boolean allowServerListings;
    public int particleStatus = -1;

    public C2SClientInformationPacket(final String locale, final int viewDistance, final int chatMode, final boolean chatColors,
                                      final int displayedSkinParts, final int mainHand, final boolean enableTextFiltering, final boolean allowServerListings) {
        this(locale, viewDistance, chatMode, chatColors, displayedSkinParts, mainHand, enableTextFiltering, allowServerListings, 0);
    }

    public C2SClientInformationPacket(final String locale, final int viewDistance, final int chatMode, final boolean chatColors,
                                      final int displayedSkinParts, final int mainHand, final boolean enableTextFiltering, final boolean allowServerListings,
                                      final int particleStatus) {
        this.locale = locale;
        this.viewDistance = viewDistance;
        this.chatMode = chatMode;
        this.chatColors = chatColors;
        this.displayedSkinParts = displayedSkinParts;
        this.mainHand = mainHand;
        this.enableTextFiltering = enableTextFiltering;
        this.allowServerListings = allowServerListings;
        this.particleStatus = particleStatus;
    }

    public C2SClientInformationPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.locale = PacketTypes.readString(byteBuf, 16);
        this.viewDistance = byteBuf.readByte();
        this.chatMode = PacketTypes.readVarInt(byteBuf);
        this.chatColors = byteBuf.readBoolean();
        this.displayedSkinParts = byteBuf.readUnsignedByte();
        this.mainHand = PacketTypes.readVarInt(byteBuf);
        this.enableTextFiltering = byteBuf.readBoolean();
        this.allowServerListings = byteBuf.readBoolean();
        if (byteBuf.isReadable()) {
            this.particleStatus = PacketTypes.readVarInt(byteBuf);
        }
        while (byteBuf.isReadable()) {
            byteBuf.readByte(); //unknown future tail must not leak into the codec's next-packet framing
        }
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        PacketTypes.writeString(byteBuf, this.locale);
        byteBuf.writeByte(this.viewDistance);
        PacketTypes.writeVarInt(byteBuf, this.chatMode);
        byteBuf.writeBoolean(this.chatColors);
        byteBuf.writeByte(this.displayedSkinParts);
        PacketTypes.writeVarInt(byteBuf, this.mainHand);
        byteBuf.writeBoolean(this.enableTextFiltering);
        byteBuf.writeBoolean(this.allowServerListings);
        if (this.particleStatus >= 0) {
            PacketTypes.writeVarInt(byteBuf, this.particleStatus);
        }
    }
}
