package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 冷灰纸面控件，采用 blit 保持工作端兼容性。 */
final class AtlasUiTheme {
    private static final ResourceLocation PIXEL = new ResourceLocation(BiyiAtlas.MODID, "textures/atlas/pixel.png");
    private AtlasUiTheme() {}
    static void rect(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        g.setColor(((color >> 16) & 255) / 255F, ((color >> 8) & 255) / 255F,
                (color & 255) / 255F, ((color >>> 24) & 255) / 255F);
        g.blit(PIXEL, x, y, w, h, 0, 0, 16, 16, 16, 16);
        g.setColor(1, 1, 1, 1);
    }
    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        rect(g, x + 2, y + 3, w, h, 0x202C3535);
        rect(g, x, y, w, h, 0xFF737F7C);
        rect(g, x + 1, y + 1, w - 2, h - 2, 0xFFE0E4DF);
    }
    static void button(GuiGraphics g, int x, int y, int w, int h, boolean active) {
        rect(g, x, y, w, h, active ? 0xFF8B5550 : 0xFFA6AFAB);
        rect(g, x + 1, y + 1, w - 2, h - 2, active ? 0xFFD7DCD5 : 0xFFE8EAE3);
        if (active) rect(g, x, y, 2, h, 0xFF8B5550);
    }
}
