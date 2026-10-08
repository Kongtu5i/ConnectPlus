package dev.connectplus.lobby.protocol.packets.play;

import com.viaversion.viaversion.api.minecraft.chunks.Chunk;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.chunk.ChunkType1_20_2;
import dev.connectplus.lobby.LobbyConstants;
import io.netty.buffer.ByteBuf;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;

import java.util.BitSet;

/**
 * Derived from MiniConnect's S2CLevelChunkWithLightPacket (MIT, Copyright (c) 2024 Lenni0451).
 * Modified in this repo: read() is implemented so the scripted test client can decode it.
 */
public class S2CLevelChunkWithLightPacket implements Packet {

    private static final com.viaversion.viaversion.api.type.Type<Chunk> CHUNK_TYPE = new ChunkType1_20_2(LobbyConstants.CHUNK_SECTION_COUNT, 15, 7);

    public Chunk chunk;

    public S2CLevelChunkWithLightPacket(final Chunk chunk) {
        this.chunk = chunk;
    }

    public S2CLevelChunkWithLightPacket() {
    }

    @Override
    public void read(final ByteBuf byteBuf, final int protocolVersion) {
        this.chunk = CHUNK_TYPE.read(byteBuf);
        //Skip the light data: only the chunk is of interest to the test client
        Types.LONG_ARRAY_PRIMITIVE.read(byteBuf); //sky light mask
        Types.LONG_ARRAY_PRIMITIVE.read(byteBuf); //block light mask
        Types.LONG_ARRAY_PRIMITIVE.read(byteBuf); //empty sky light mask
        Types.LONG_ARRAY_PRIMITIVE.read(byteBuf); //empty block light mask
        final int skyLightCount = PacketTypes.readVarInt(byteBuf);
        for (int i = 0; i < skyLightCount; i++) Types.BYTE_ARRAY_PRIMITIVE.read(byteBuf);
        final int blockLightCount = PacketTypes.readVarInt(byteBuf);
        for (int i = 0; i < blockLightCount; i++) Types.BYTE_ARRAY_PRIMITIVE.read(byteBuf);
    }

    @Override
    public void write(final ByteBuf byteBuf, final int protocolVersion) {
        CHUNK_TYPE.write(byteBuf, this.chunk);
        final BitSet emptyLightMask = new BitSet();
        final int lightCount = this.chunk.getSections().length + 2;
        emptyLightMask.set(0, lightCount);
        Types.LONG_ARRAY_PRIMITIVE.write(byteBuf, emptyLightMask.toLongArray());
        Types.LONG_ARRAY_PRIMITIVE.write(byteBuf, new long[0]);
        Types.LONG_ARRAY_PRIMITIVE.write(byteBuf, new long[0]);
        Types.LONG_ARRAY_PRIMITIVE.write(byteBuf, emptyLightMask.toLongArray());
        PacketTypes.writeVarInt(byteBuf, lightCount);
        for (int i = 0; i < lightCount; i++) Types.BYTE_ARRAY_PRIMITIVE.write(byteBuf, LobbyConstants.FULL_LIGHT);
        PacketTypes.writeVarInt(byteBuf, 0);
    }
}
