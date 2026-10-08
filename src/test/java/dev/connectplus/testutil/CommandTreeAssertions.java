package dev.connectplus.testutil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.packet.PacketTypes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Independently decodes the literal-only command fixtures used by integration tests. */
public final class CommandTreeAssertions {
    private CommandTreeAssertions() { }

    public static List<String> executableRoots(final byte[] data) {
        final ByteBuf input = Unpooled.wrappedBuffer(data);
        try {
            final int count = PacketTypes.readVarInt(input);
            final List<String> names = new ArrayList<>();
            final List<List<Integer>> children = new ArrayList<>();
            final List<Integer> flags = new ArrayList<>();
            for (int node = 0; node < count; node++) {
                final int flag = input.readUnsignedByte();
                flags.add(flag);
                final int childCount = PacketTypes.readVarInt(input);
                final List<Integer> ids = new ArrayList<>();
                for (int child = 0; child < childCount; child++) ids.add(PacketTypes.readVarInt(input));
                children.add(ids);
                if ((flag & 8) != 0) PacketTypes.readVarInt(input);
                assertTrue((flag & 3) == 0 || (flag & 3) == 1, "Fixture must contain root/literal nodes only");
                names.add((flag & 3) == 1 ? PacketTypes.readString(input, 32767) : null);
            }
            final int root = PacketTypes.readVarInt(input);
            assertFalse(input.isReadable(), "Command packet must be fully consumed");
            assertEquals(0, flags.get(root) & 3);
            return children.get(root).stream().filter(id -> (flags.get(id) & 4) != 0).map(names::get).toList();
        } finally {
            input.release();
        }
    }
}
