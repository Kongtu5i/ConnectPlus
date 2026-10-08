package dev.connectplus.commands;

import dev.connectplus.testutil.CommandTreeAssertions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.packet.PacketTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExitCommandTreeTest {
    @Test
    void existingAliasesAreNotDuplicated() {
        final byte[] existing = ExitCommandTree.lobby();
        assertSame(existing, ExitCommandTree.append(existing));
        assertEquals(List.of("disconnect", "dc"), CommandTreeAssertions.executableRoots(existing));
    }

    @Test
    void backendArgumentBytesArePreservedWithoutParsingThem() {
        //root -> help -> executable greedy-string "text" (numeric parser 5).
        final byte[] original = {3, 0, 1, 1, 1, 1, 2, 4, 'h', 'e', 'l', 'p',
                6, 0, 4, 't', 'e', 'x', 't', 5, 2, 0};
        final byte[] untouched = original.clone();
        final byte[] augmented = ExitCommandTree.append(original);
        final ByteBuf input = Unpooled.wrappedBuffer(augmented);
        try {
            assertEquals(5, PacketTypes.readVarInt(input));
            assertEquals(0, input.readUnsignedByte());
            assertEquals(3, PacketTypes.readVarInt(input));
            assertEquals(1, PacketTypes.readVarInt(input));
            assertEquals(3, PacketTypes.readVarInt(input));
            assertEquals(4, PacketTypes.readVarInt(input));
            final byte[] retained = new byte[original.length - 5];
            input.readBytes(retained);
            assertArrayEquals(Arrays.copyOfRange(original, 4, original.length - 1), retained);
            assertArrayEquals(untouched, original, "Augmentation must not mutate shared backend input");
        } finally {
            input.release();
        }
    }

    @Test
    void commandCountsAndChildIdsCrossVarIntBoundaries() {
        final ByteBuf input = Unpooled.buffer();
        try {
            PacketTypes.writeVarInt(input, 131);
            input.writeByte(0);
            PacketTypes.writeVarInt(input, 130);
            for (int i = 1; i <= 130; i++) PacketTypes.writeVarInt(input, i);
            final List<String> expected = new ArrayList<>();
            for (int i = 0; i < 130; i++) {
                final String name = "server" + i;
                expected.add(name);
                input.writeByte(5);
                PacketTypes.writeVarInt(input, 0);
                PacketTypes.writeString(input, name);
            }
            PacketTypes.writeVarInt(input, 0);
            expected.add("disconnect");
            expected.add("dc");
            final byte[] augmented = ExitCommandTree.append(ByteBufUtil.getBytes(input));
            assertEquals(expected, CommandTreeAssertions.executableRoots(augmented));
            assertSame(augmented, ExitCommandTree.append(augmented));
        } finally {
            input.release();
        }
    }

    @Test
    void existingExitLiteralBecomesExecutableAndRetainsItsChildren() {
        //Root -> disconnect -> literal "extra". /disconnect itself was non-executable.
        final byte[] original = {3, 0, 1, 1, 1, 1, 2, 10,
                'd', 'i', 's', 'c', 'o', 'n', 'n', 'e', 'c', 't',
                5, 0, 5, 'e', 'x', 't', 'r', 'a', 0};
        final byte[] augmented = ExitCommandTree.append(original);
        assertEquals(List.of("disconnect", "dc"), CommandTreeAssertions.executableRoots(augmented));
        final ByteBuf input = Unpooled.wrappedBuffer(augmented);
        try {
            assertEquals(4, PacketTypes.readVarInt(input));
            input.readByte();
            assertEquals(2, PacketTypes.readVarInt(input));
            assertEquals(1, PacketTypes.readVarInt(input));
            assertEquals(3, PacketTypes.readVarInt(input));
            assertEquals(5, input.readUnsignedByte());
            assertEquals(1, PacketTypes.readVarInt(input));
            assertEquals(2, PacketTypes.readVarInt(input), "Existing extra-argument branch remains intact");
        } finally {
            input.release();
        }
    }

    @Test
    void unsupportedRootsAndMalformedPrefixesPassThroughUnchanged() {
        for (final byte[] data : List.of(new byte[0], new byte[] {0, 0, 0, 0},
                new byte[] {2, 0, 1, 7, 5, 0, 0, 0},
                new byte[] {2, 0, 1, 1, 5, 0, 20, 'a', 0},
                new byte[] {2, 5, 0, 1, 'x', 0, 1, 0, 1},
                new byte[] {2, 0, 1, 1, 6, 0, 1, 'x', 5, 2, 0})) {
            assertSame(data, ExitCommandTree.append(data));
        }
        assertNull(ExitCommandTree.append(null));
    }
}
