package com.biyi.atlas.client;

import java.util.EnumMap;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Feature owners register their client panel during client setup and return to the supplied screen. */
public final class EditorServiceScreen extends Screen {
    public enum Panel {
        SURVEY("采集任务"), STRUCTURES("建筑候选"), PRESENTATION("美化校对"), FLOORS("局部楼层"), PUBLISH("发布回执");
        final String label;
        Panel(String label) { this.label = label; }
    }
    private static final EnumMap<Panel, Consumer<Screen>> panels = new EnumMap<>(Panel.class);
    private final EditorAtlasScreen editor;

    public static void register(Panel panel, Consumer<Screen> open) {
        panels.put(panel, java.util.Objects.requireNonNull(open));
    }

    EditorServiceScreen(EditorAtlasScreen editor) {
        super(Component.literal("作者设置"));
        this.editor = editor;
    }

    @Override
    protected void init() {
        int w = Math.min(width - 20, 320), left = (width - w) / 2;
        int i = 0, top = Math.min(92, height - 104);
        for (var panel : Panel.values()) {
            var open = panels.get(panel);
            var label = panel.label + (open == null ? " · 服务未接入" : "");
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                if (editor.canResume() && open != null) open.accept(this);
            }).bounds(left + (i % 2) * (w / 2), top + 23 * (i / 2), w / 2 - 3, 20).build()).active = open != null;
            i++;
        }
        addRenderableWidget(Button.builder(Component.literal("返回工作台"), b -> onClose())
                .bounds(left, top + 72, w, 20).build());
    }

    @Override
    public void tick() { if (!editor.canResume()) AtlasScreen.open(); }

    @Override
    public void onClose() {
        if (editor.canResume()) minecraft.setScreen(editor);
        else AtlasScreen.open();
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float partialTick) {
        renderBackground(g);
        int left = Math.max(10, (width - 320) / 2);
        String[] lines = {"作者设置 · " + (editor.canResume() ? "已获编辑授权" : "编辑权限已暂停"),
                AtlasAiBridge.connectionStatus(), AtlasMapSession.status(), AtlasMapSession.preparation()};
        int top = 8;
        for (var line : lines) {
            g.drawString(font, font.plainSubstrByWidth(line, width - left * 2), left, top, 0xffeee7d6, false);
            top += 15;
        }
        super.render(g, x, y, partialTick);
    }
}
