package com.biyi.atlas.client;

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
        int y = height / 2 - 63;
        control(y, "小地图 " + on(AtlasSettings.enabled()), AtlasSettings::toggleEnabled);
        control(y + 22, "位置 " + AtlasSettings.corner(), AtlasSettings::cycleCorner);
        control(y + 44, AtlasSettings.northUp() ? "北向朝上" : "随玩家转向", AtlasSettings::toggleNorth);
        control(y + 66, "已知标记 " + on(AtlasSettings.markers()), AtlasSettings::toggleMarkers);
        button(width / 2 - 88, y + 88, 40, "−", () -> AtlasSettings.adjustZoom(-.25));
        button(width / 2 + 48, y + 88, 40, "+", () -> AtlasSettings.adjustZoom(.25));
        control(y + 110, "返回地图", this::onClose);
    }
    private String on(boolean value) { return value ? "开" : "关"; }
    private void control(int y, String label, Runnable action) {
        button(width / 2 - 88, y, 176, label, action);
    }
    private void button(int x, int y, int w, String label, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> {
            action.run();
            if (minecraft.screen == this) rebuildWidgets();
        }).bounds(x, y, w, 20).build());
    }
    @Override public void render(GuiGraphics graphics, int x, int y, float tick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 80, 0xffeee7d4);
        graphics.drawCenteredString(font, String.format(java.util.Locale.ROOT, "%.2f×", AtlasSettings.zoom()),
                width / 2, height / 2 + 31, 0xffeee7d4);
        super.render(graphics, x, y, tick);
    }

    @Override public void removed() { AtlasSettings.save(); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() {
        if (parent.isAuthorMode() && !AtlasAuthoring.confirmed()) AtlasScreen.open();
        else minecraft.setScreen(parent);
    }
}
