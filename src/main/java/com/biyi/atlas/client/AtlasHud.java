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

    private AtlasHud() {
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("biyi_atlas_navigate", AtlasHud::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        Font font = minecraft.font;

        if (AtlasRoadRecorder.isRecording()) {
            String recording = "● 记录道路轨迹 " + AtlasRoadRecorder.pointCount() + " 点（R 结束）";
            graphics.drawString(font, recording, width - font.width(recording) - 8, 8, 0xFFD06A5A, true);
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
