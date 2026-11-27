package com.biyi.atlas.client;

import com.biyi.atlas.map.HeldMapView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared personal settings entry. V19 stores its mini-map scale in the held-map preference. */
public final class AtlasDisplaySettingsScreen extends Screen {
    private final AtlasScreen parent;

    public AtlasDisplaySettingsScreen(AtlasScreen parent) {
        super(Component.literal("地图显示设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - 88;
        int top = height / 2;
        addRenderableWidget(Button.builder(Component.literal("缩小"), b -> adjust(-1))
                .bounds(left, top, 84, 20).build());
        addRenderableWidget(Button.builder(Component.literal("放大"), b -> adjust(1))
                .bounds(left + 92, top, 84, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回地图"), b -> onClose())
                .bounds(left, top + 26, 176, 20).build());
    }

    private void adjust(int steps) {
        AtlasSettings.setHeldZoom(HeldMapView.scroll(AtlasSettings.heldZoom(), steps));
        AtlasHeldRenderer.invalidate();
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float tick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 44, 0xffeee7d4);
        String scale = String.format(java.util.Locale.ROOT, "手持图倍率  %.2f×", AtlasSettings.heldZoom() / HeldMapView.DEFAULT);
        graphics.drawCenteredString(font, scale, width / 2, height / 2 - 20, 0xffe2d6b9);
        super.render(graphics, x, y, tick);
    }

    @Override public void removed() { AtlasSettings.save(); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() {
        if (parent.isAuthorMode() && !AtlasAuthoring.confirmed()) AtlasScreen.open();
        else minecraft.setScreen(parent);
    }
}
