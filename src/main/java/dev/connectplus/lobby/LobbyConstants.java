package dev.connectplus.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.viaversion.viaversion.api.minecraft.GlobalBlockPosition;
import com.viaversion.viaversion.api.minecraft.chunks.Chunk;
import com.viaversion.viaversion.api.minecraft.chunks.Chunk1_18;
import com.viaversion.viaversion.api.minecraft.chunks.ChunkSection;
import com.viaversion.viaversion.api.minecraft.chunks.ChunkSectionImpl;
import com.viaversion.viaversion.api.minecraft.chunks.DataPaletteImpl;
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import com.viaversion.viaversion.api.type.Types;
import net.lenni0451.mcstructs.nbt.NbtTag;
import net.lenni0451.mcstructs.nbt.io.NbtIO;
import net.lenni0451.mcstructs.nbt.io.NbtReadTracker;
import net.lenni0451.mcstructs.nbt.tags.CompoundTag;
import net.lenni0451.mcstructs.text.serializer.TextComponentCodec;
import net.raphimc.viabedrock.protocol.data.enums.java.GameEventType;
import dev.connectplus.lobby.protocol.packets.model.CommonPlayerSpawnInfo;
import dev.connectplus.lobby.protocol.packets.play.S2CGameEventPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CLevelChunkWithLightPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CPlayerAbilitiesPacket;
import dev.connectplus.lobby.protocol.packets.play.S2CPlayerPositionPacket;
import dev.connectplus.lobby.states.StateHandler;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Lobby protocol constants and resources. Derived from MiniConnect's
 * ProtocolConstants (MIT, Copyright (c) 2024 Lenni0451); the registry and
 * tag NBT resources are reused unmodified. Modified in this repo: ITEMS is
 * parsed from items.json with plain Gson instead of net.lenni0451.commons.
 */
public final class LobbyConstants {

    public static final TextComponentCodec TEXT_CODEC = TextComponentCodec.V1_21_4;
    public static final Map<String, CompoundTag> REGISTRIES;
    public static final Map<String, Map<String, int[]>> TAGS;
    public static final List<String> ITEMS;
    public static final String[] DIMENSIONS;
    public static final int CHUNK_SECTION_COUNT = 16; //16 for the end
    public static final byte[] FULL_LIGHT = new byte[2048];
    public static final double SPAWN_X = 24.5, SPAWN_Y = 64, SPAWN_Z = 24.5;
    // Block-state IDs belong to the lobby's fixed Java 1.21.4 wire protocol.
    private static final int FLOOR_BLOCK_STATE = 11245; // minecraft:barrier[waterlogged=false], no block entity
    // Adventure exposes native hotbar/item controls; spectator replaces those
    // with its own selection menu. The lobby remains invulnerable and flying.
    public static final CommonPlayerSpawnInfo DEFAULT_SPAWN_INFO = new CommonPlayerSpawnInfo(3, "minecraft:the_end", 0, 2, 0, false, false, null, 0, 0);

    static {
        REGISTRIES = readCompound("registries.nbt", tag -> {
            Map<String, CompoundTag> registries = new HashMap<>();
            for (Map.Entry<String, NbtTag> entry : tag) {
                registries.put(entry.getKey(), entry.getValue().asCompoundTag());
            }
            return registries;
        });
        TAGS = readCompound("tags.nbt", tag -> {
            Map<String, Map<String, int[]>> tags = new HashMap<>();
            for (Map.Entry<String, NbtTag> entry : tag) {
                Map<String, int[]> registryTags = new HashMap<>();
                for (Map.Entry<String, NbtTag> tagEntry : entry.getValue().asCompoundTag()) {
                    registryTags.put(tagEntry.getKey(), tagEntry.getValue().asIntArrayTag().getValue());
                }
                tags.put(entry.getKey(), registryTags);
            }
            return tags;
        });
        DIMENSIONS = REGISTRIES.get("minecraft:dimension_type").asCompoundTag().getValue().keySet().toArray(new String[0]);
        ITEMS = readItems("items.json");
        Arrays.fill(FULL_LIGHT, (byte) -1);
    }

    private LobbyConstants() {
    }

    private static <T> T readCompound(final String name, final Function<CompoundTag, T> mapper) {
        final InputStream stream = LobbyConstants.class.getClassLoader().getResourceAsStream(name);
        if (stream == null) throw new IllegalStateException("Missing resource: " + name);
        try {
            return mapper.apply(NbtIO.LATEST.read(stream, true, NbtReadTracker.unlimitedDepth()).asCompoundTag());
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to read resource: " + name, e);
        }
    }

    private static List<String> readItems(final String name) {
        final InputStream stream = LobbyConstants.class.getClassLoader().getResourceAsStream(name);
        if (stream == null) throw new IllegalStateException("Missing resource: " + name);
        try {
            final JsonArray array = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
            final List<String> items = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                items.add(array.get(i).getAsString());
            }
            return items;
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to read resource: " + name, e);
        }
    }

    public static void sendSpawnInfo(final StateHandler stateHandler) {
        stateHandler.send(new S2CPlayerAbilitiesPacket(true, true, true, false, 0, 0));
        stateHandler.send(new S2CGameEventPacket(GameEventType.LEVEL_CHUNKS_LOAD_START.ordinal(), 0));
        for (int i = 0; i < 9; i++) {
            Chunk chunk = new Chunk1_18(i % 3, i / 3, new ChunkSection[LobbyConstants.CHUNK_SECTION_COUNT], new com.viaversion.nbt.tag.CompoundTag(), new ArrayList<>());
            for (int s = 0; s < chunk.getSections().length; s++) {
                ChunkSection section = new ChunkSectionImpl(false);
                chunk.getSections()[s] = section;
                section.palette(PaletteType.BLOCKS).addId(0);
                section.addPalette(PaletteType.BIOMES, new DataPaletteImpl(ChunkSection.BIOME_SIZE));
                section.palette(PaletteType.BIOMES).addId(0);
            }
            // A Bedrock dimension change may reset client flight. Give the player
            // real collision support instead of depending on flight in an empty
            // world: Geyser cannot place a virtual container outside world bounds.
            if (chunk.getX() == 1 && chunk.getZ() == 1) {
                ChunkSection floor = chunk.getSections()[((int) SPAWN_Y - 1) >> 4];
                for (int x = 20; x <= 28; x++) for (int z = 20; z <= 28; z++) {
                    floor.palette(PaletteType.BLOCKS).setIdAt(x & 15, ((int) SPAWN_Y - 1) & 15, z & 15, FLOOR_BLOCK_STATE);
                }
                // Geyser skips sections whose non-air count is zero.
                floor.setNonAirBlocksCount(81);
            }
            stateHandler.send(new S2CLevelChunkWithLightPacket(chunk));
        }
        stateHandler.send(spawnPosition(0));
    }

    public static S2CPlayerPositionPacket spawnPosition(final int teleportId) {
        return new S2CPlayerPositionPacket(teleportId, SPAWN_X, SPAWN_Y, SPAWN_Z, 0, 0, 0, 0, 0, 0);
    }

    public static boolean isSafePosition(final double x, final double y, final double z) {
        // Keep movement within the supported platform and clear container space.
        // These comparisons also reject NaN and infinity.
        return Math.abs(x - SPAWN_X) <= 3 && Math.abs(z - SPAWN_Z) <= 3
                && y >= SPAWN_Y - 0.01 && y <= SPAWN_Y + 4;
    }
}
