package com.biyi.atlas.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/** The editor instance owns its camera, selected object and unfinished geometry. */
public final class EditorAtlasScreen extends AtlasScreen {
    private final Object connection = Minecraft.getInstance().getConnection();
    private final Object level = Minecraft.getInstance().level;
    private ResourceLocation previewDimension;
    private int previewLayer;

    EditorAtlasScreen() {
        super(true);
    }

    boolean canResume() {
        var client = Minecraft.getInstance();
        return level != null && level == client.level && connection == client.getConnection()
                && client.player != null && client.player.isCreative() && AtlasAuthoring.confirmed();
    }

    @Override
    public void tick() {
        if (!canResume()) AtlasScreen.open();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return !canResume() || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return !canResume() || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        return !canResume() || super.keyPressed(key, scan, modifiers);
    }

    void openPreview(Screen returnTo, ResourceLocation dimension, int layer) {
        previewDimension = dimension;
        previewLayer = layer;
        openPreview(returnTo);
    }

    void openPreview(Screen returnTo) {
        if (previewDimension == null) previewDimension = Minecraft.getInstance().level.dimension().location();
        Minecraft.getInstance().setScreen(new EditorPreviewScreen(this, returnTo, previewDimension, previewLayer));
    }

    void rememberView(ResourceLocation dimension, int layer) {
        previewDimension = dimension;
        previewLayer = layer;
    }
}
