package com.biyi.atlas.client;

import com.biyi.atlas.map.MapViewport;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** A temporary, one-pixel-per-block surface reference around the author. */
final class EditorSurfaceReference {
    private static final int SIZE = 256;
    private ClientLevel level;
    private int originX, originZ, next;
    private long lastTick;
    private DynamicTexture texture;
    private ResourceLocation id;

    void close() {
        if (id != null) Minecraft.getInstance().getTextureManager().release(id);
        level = null;
        texture = null;
        id = null;
    }

    void draw(GuiGraphics graphics, MapViewport camera) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        if (mc.level != level || Math.abs(px-originX-SIZE/2) >= 64 || Math.abs(pz-originZ-SIZE/2) >= 64) {
            close();
            level = mc.level;
            originX = Math.floorDiv(px,16)*16 - SIZE/2;
            originZ = Math.floorDiv(pz,16)*16 - SIZE/2;
            next = 0;
            lastTick = Long.MIN_VALUE;
            texture = new DynamicTexture(new NativeImage(SIZE, SIZE, true));
            id = mc.getTextureManager().register("atlas_surface_reference", texture);
        }
        if (lastTick != level.getGameTime()) {
            lastTick = level.getGameTime();
            // Start at the player and wrap outwards. Refresh also picks up later block edits.
            for (int i = 0; i < 4; i++) {
                int cx = (8 + next % 16) % 16, cz = (8 + next / 16) % 16;
                sample(originX + cx*16, originZ + cz*16);
                next = (next + 1) % 256;
            }
            texture.upload();
        }
        int x = (int)Math.round(camera.screenX(originX)), y = (int)Math.round(camera.screenY(originZ));
        int w = (int)Math.round(camera.screenX(originX+SIZE))-x, h = (int)Math.round(camera.screenY(originZ+SIZE))-y;
        if (w > 0 && h > 0) graphics.blit(id, x,y,w,h,0,0,SIZE,SIZE,SIZE,SIZE);
    }

    private void sample(int x0, int z0) {
        var pixels = texture.getPixels();
        var chunk = level.getChunkSource().getChunkNow(Math.floorDiv(x0,16), Math.floorDiv(z0,16));
        var pos = new BlockPos.MutableBlockPos();
        for (int x=x0; x<x0+16; x++) {
            int previous = Integer.MIN_VALUE;
            for (int z=z0; z<z0+16; z++) {
                int color = 0;
                if (chunk != null) {
                    int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                    while (y >= level.getMinBuildHeight()) {
                        pos.set(x,y,z);
                        var material = chunk.getBlockState(pos).getMapColor(level,pos);
                        if (material != MapColor.NONE) {
                            var brightness = previous == Integer.MIN_VALUE || previous == y ? MapColor.Brightness.NORMAL :
                                    (previous < y ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW);
                            color = material.calculateRGBColor(brightness);
                            previous = y;
                            break;
                        }
                        y--;
                    }
                }
                pixels.setPixelRGBA(x-originX,z-originZ,color);
            }
        }
    }
}
