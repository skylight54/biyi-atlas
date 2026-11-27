package com.biyi.atlas.client;

/** Compatibility hooks for the old events until integration removes their callers. */
final class AtlasHeldRenderer {
    static boolean holdingMap(net.minecraft.world.entity.player.Player player) { return false; }
    static void tick() {}
    static void reset() {}
}
