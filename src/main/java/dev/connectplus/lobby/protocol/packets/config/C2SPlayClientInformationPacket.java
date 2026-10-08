package dev.connectplus.lobby.protocol.packets.config;

import net.raphimc.netminecraft.constants.MCPackets;

/**
 * The play-state client settings (M7): a client that changes its language in
 * the options screen re-sends the settings packet in the play state. The packet
 * registry rejects one packet class for two ids, so this empty subclass carries
 * the play-state id ({@code MCPackets.C2S_CLIENT_INFORMATION}) while the
 * configuration variant keeps the config-state id; the wire layout is identical.
 * PlayStateHandler captures the locale update.
 */
public class C2SPlayClientInformationPacket extends C2SClientInformationPacket {

    public C2SPlayClientInformationPacket(final String locale, final int viewDistance, final int chatMode, final boolean chatColors,
                                          final int displayedSkinParts, final int mainHand, final boolean enableTextFiltering, final boolean allowServerListings) {
        super(locale, viewDistance, chatMode, chatColors, displayedSkinParts, mainHand, enableTextFiltering, allowServerListings);
    }

    public C2SPlayClientInformationPacket() {
    }
}
