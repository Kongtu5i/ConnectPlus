package dev.connectplus.commands;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.packet.PacketTypes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Command declarations owned by CP; argument nodes remain opaque backend bytes. */
public final class ExitCommandTree {
    private ExitCommandTree() { }

    public static byte[] lobby() {
        //One empty root node and root index zero.
        return append(new byte[] {1, 0, 0, 0});
    }

    /**
     * Augments the vanilla breadth-first layout: root zero, then its literals.
     * Existing node IDs, argument parsers/properties and redirects are retained.
     * A nonstandard root/layout is returned untouched rather than guessing how
     * to skip version-specific argument properties. Only the literal prefix is
     * inspected; adding literals needs no host parser or internal access.
     */
    public static byte[] append(final byte[] original) {
        if (original == null || original.length < 4 || original.length > 2_097_152
                || original[original.length - 1] != 0) return original;
        final ByteBuf input = Unpooled.wrappedBuffer(original);
        final ByteBuf output = Unpooled.buffer();
        try {
            final int count = PacketTypes.readVarInt(input);
            if (count < 1 || count > 32_768 || count > original.length / 2 || input.readUnsignedByte() != 0) return original;
            final int childCount = PacketTypes.readVarInt(input);
            if (childCount < 0 || childCount >= count) return original;
            final List<Integer> children = new ArrayList<>();
            final HashSet<Integer> childIds = new HashSet<>();
            int lastChild = 0;
            for (int i = 0; i < childCount; i++) {
                final int child = PacketTypes.readVarInt(input);
                if (child <= 0 || child >= count || !childIds.add(child)) return original;
                children.add(child);
                lastChild = Math.max(lastChild, child);
            }
            final int suffixStart = input.readerIndex();
            final List<String> missing = new ArrayList<>(LobbyCommands.EXIT_NAMES);
            final List<Integer> executableFlags = new ArrayList<>();
            for (int id = 1; id <= lastChild; id++) {
                final int flagsOffset = input.readerIndex();
                final int flags = input.readUnsignedByte();
                if ((flags & 3) != 1 || (flags & 0x10) != 0) return original;
                final int descendants = PacketTypes.readVarInt(input);
                if (descendants < 0 || descendants >= count) return original;
                for (int i = 0; i < descendants; i++) {
                    final int descendant = PacketTypes.readVarInt(input);
                    if (descendant < 0 || descendant >= count) return original;
                }
                if ((flags & 8) != 0) {
                    final int redirect = PacketTypes.readVarInt(input);
                    if (redirect < 0 || redirect >= count) return original;
                }
                final String name = PacketTypes.readString(input, 32767);
                if (childIds.contains(id) && LobbyCommands.EXIT_NAMES.contains(name)) {
                    missing.remove(name);
                    if ((flags & 4) == 0) executableFlags.add(flagsOffset);
                }
            }
            if (input.readerIndex() > original.length - 1) return original;
            if (missing.isEmpty() && executableFlags.isEmpty()) return original;
            final byte[] retained = original.clone();
            for (final int offset : executableFlags) retained[offset] |= 4;
            PacketTypes.writeVarInt(output, count + missing.size());
            output.writeByte(0);
            PacketTypes.writeVarInt(output, children.size() + missing.size());
            for (final int child : children) PacketTypes.writeVarInt(output, child);
            for (int i = 0; i < missing.size(); i++) PacketTypes.writeVarInt(output, count + i);
            output.writeBytes(retained, suffixStart, original.length - 1 - suffixStart);
            for (final String name : missing) {
                output.writeByte(5); //literal, executable, no arguments/redirect
                PacketTypes.writeVarInt(output, 0);
                PacketTypes.writeString(output, name);
            }
            PacketTypes.writeVarInt(output, 0);
            return ByteBufUtil.getBytes(output);
        } catch (final RuntimeException malformed) {
            return original;
        } finally {
            input.release();
            output.release();
        }
    }
}
