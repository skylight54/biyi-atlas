package com.biyi.atlas.client;

import com.biyi.atlas.map.MapViewport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class EditorPreviewScreen extends Screen {
    private final EditorAtlasScreen editor;
    private final Screen returnTo;
    private final PlayerAtlasScreen canvas = new PlayerAtlasScreen();
    private final ResourceLocation dimension;
    private final int layer;
    private Button back;

    EditorPreviewScreen(EditorAtlasScreen editor, Screen returnTo, ResourceLocation dimension, int layer) {
        super(Component.literal("玩家预览"));
        this.editor = editor;
        this.returnTo = returnTo;
        this.dimension = dimension;
        this.layer = layer;
    }

    @Override
    protected void init() {
        back = addRenderableWidget(Button.builder(Component.literal("返回编辑"), b -> onClose())
                .bounds(12, 10, 84, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float partialTick) {
        g.fill(0, 0, width, height, 0xffe0d6bc);
        var camera = editor.viewport();
        canvas.drawPlayerCanvas(g, dimension, layer, new MapViewport(width, height,
                camera.centerX(), camera.centerZ(), camera.pixelsPerBlock()));
        back.active = editor.canResume();
        g.drawString(font, AtlasMapSession.status(), 12, height - 18, 0xff35433c, false);
        super.render(g, x, y, partialTick);
    }

    @Override
    public void onClose() {
        if (editor.canResume()) minecraft.setScreen(returnTo);
        else AtlasScreen.open();
    }
}
