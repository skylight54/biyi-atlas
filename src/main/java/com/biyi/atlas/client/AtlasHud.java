package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** HUD 指路：顶部的箭头指向追踪目标，并显示名字与距离。 */
@Mod.EventBusSubscriber(modid = BiyiAtlas.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AtlasHud {

    private static final ResourceLocation ARROW =
            new ResourceLocation(BiyiAtlas.MODID, "textures/atlas/arrow.png");

    private static final ResourceLocation CLOUD =
            new ResourceLocation(BiyiAtlas.MODID, "textures/atlas/fog_ink.png");

    private static void renderMap(GuiGraphics g, Minecraft mc, float tick, int width, int height, int size) {
        var corner = AtlasSettings.corner();
        int left = corner == AtlasSettings.Corner.右上 || corner == AtlasSettings.Corner.右下 ? width - size - 6 : 6;
        int top = corner == AtlasSettings.Corner.左下 || corner == AtlasSettings.Corner.右下 ? height - size - 6 : 6;
        var player = mc.player;
        var position = player.getPosition(tick);
        float heading = player.getViewYRot(tick) + 180;
        int extent = 2 * (size + 16);
        var camera = AtlasSettings.hudCamera(extent, position.x, position.z);
        var dimension = mc.level.dimension().location();
        var area = AtlasMapSession.active();
        g.fill(left-2, top-2, left+size+2, top+size+2, 0xffaa956f);
        g.fill(left, top, left+size, top+size, 0xff35464a);
        g.enableScissor(left, top, left+size, top+size);
        var pose = g.pose();
        pose.pushPose();
        try {
            pose.translate(left + size / 2f, top + size / 2f, 0);
            if (!AtlasSettings.northUp()) pose.mulPose(Axis.ZP.rotationDegrees(-heading));
            pose.translate(-extent / 2, -extent / 2, 0);
            g.blit(CLOUD, 0, 0, extent, extent, 0, 0, 1, 1, 1, 1);
            if (AtlasAuthoring.known() && AtlasServerView.authoritative() && area != null
                    && area.layer() == 0 && dimension.equals(area.dimension()) && AtlasServerView.visible(area.id())) {
                AtlasMapSession.rendering(false, area);
                AtlasTerrainTiles.render(g, AtlasMapSession.terrain(), extent, extent, position.x, position.z,
                        camera.pixelsPerBlock(), (x,z) -> known(dimension, x, z));
                if (AtlasSettings.markers()) {
                    for (var marker : AtlasStyle.INSTANCE.markers()) {
                        if (dimension.equals(marker.dimension())) dot(g, camera, dimension, marker.x(), marker.z());
                    }
                    for (var point : AtlasStorage.get(dimension).waypoints()) {
                        if (point.layer() == 0) dot(g, camera, dimension, point.x(), point.z());
                    }
                }
            }
            pose.translate(extent / 2, extent / 2, 0);
            pose.mulPose(Axis.ZP.rotationDegrees(heading));
            g.blit(ARROW, -5, -5, 10, 10, 0, 0, 32, 32, 32, 32);
        } finally {
            pose.popPose();
            g.disableScissor();
        }
    }

    private static boolean known(ResourceLocation dim, double x, double z) {
        return AtlasReadView.elementVisible(com.biyi.atlas.map.MapAudience.PLAYER,
                AtlasVisibility.Kind.MARKER, null, dim, 0, x, z);
    }

    private static void dot(GuiGraphics g, com.biyi.atlas.map.MapViewport camera, ResourceLocation dim,
                            double x, double z) {
        double px = camera.screenX(x), py = camera.screenY(z);
        if (px < 0 || px >= camera.width() || py < 0 || py >= camera.height() || !known(dim, x, z)) return;
        int i = (int) px, j = (int) py;
        g.fill(i-2, j-2, i+3, j+3, 0xff293d3e);
        g.fill(i-1, j-1, i+2, j+2, 0xffe8cd87);
    }

    private AtlasHud() {
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("biyi_atlas_navigate", AtlasHud::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null || minecraft.options.hideGui) {
            return;
        }
        Font font = minecraft.font;

        int mapSize = Math.min(112, Math.min(width / 4, height / 3));
        boolean mapVisible = AtlasSettings.enabled();
        if (mapVisible) renderMap(graphics, minecraft, partialTick, width, height, mapSize);
        int recordingY = mapVisible && AtlasSettings.corner() == AtlasSettings.Corner.右上 ? mapSize + 12 : 8;
        if (AtlasRoadRecorder.isRecording()) {
            String recording = "● 记录道路轨迹 " + AtlasRoadRecorder.pointCount() + " 点（R 结束）";
            graphics.drawString(font, recording, width - font.width(recording) - 8, recordingY, 0xFFD06A5A, true);
        }

        AtlasTracker.Target target = AtlasTracker.target();
        if (target == null) {
            return;
        }
        boolean sameDimension = minecraft.level.dimension().location().equals(target.dimension());
        String text;
        float rotation = 0.0F;
        int color = 0xFF3B2C1B;
        if (!sameDimension) {
            text = target.name() + " · 在其它维度";
        } else {
            double dx = target.x() - minecraft.player.getX();
            double dz = target.z() - minecraft.player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            rotation = Mth.wrapDegrees(targetYaw - minecraft.player.getYRot());
            text = target.name() + " · " + (int) Math.round(distance) + "m";
        }

        int centerX = width / 2;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, 22.0F, 0.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(rotation));
        graphics.setColor(0.75F, 0.22F, 0.18F, 0.95F);
        graphics.blit(ARROW, -8, -8, 16, 16, 0.0F, 0.0F, 32, 32, 32, 32);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        pose.popPose();

        int textWidth = font.width(text);
        graphics.drawString(font, text, centerX - textWidth / 2, 34, color, true);
    }
}
