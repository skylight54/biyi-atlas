package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.registry.ModItems;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import com.biyi.atlas.map.HeldMapMotion;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** 主线程低频更新离屏纸面；手持只绘制一张贴图，不重走完整地图。 */
@Mod.EventBusSubscriber(modid=BiyiAtlas.MODID,value=Dist.CLIENT)
public final class AtlasHeldRenderer {
    private static AtlasScreen view;
    private static RenderTarget paper;
    private static int age;
    private static boolean ready;
    private AtlasHeldRenderer(){}
    public static boolean holdingMap(net.minecraft.world.entity.player.Player p){return p.getMainHandItem().is(ModItems.ATLAS.get())||p.getOffhandItem().is(ModItems.ATLAS.get());}
    static void invalidate(){age=5;}
    static void reset(){
        view=null;ready=false;age=0;
        if(paper!=null){paper.destroyBuffers();paper=null;}
    }
    static void tick(){
        var c=Minecraft.getInstance();
        if(c.player==null||c.level==null||(c.screen!=null&&!(c.screen instanceof AtlasHeldSettingsScreen))||!c.options.getCameraType().isFirstPerson())return;
        if(!c.player.getMainHandItem().is(ModItems.ATLAS.get())&&!c.player.getOffhandItem().is(ModItems.ATLAS.get()))return;
        if(ready&&++age<5)return;age=0;
        if(view==null)view=new PlayerAtlasScreen();
        int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        var projection=new org.joml.Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting=RenderSystem.getVertexSorting();
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        var model=RenderSystem.getModelViewStack();model.pushPose();model.setIdentity();RenderSystem.applyModelViewMatrix();
        try{
            if(paper==null)paper=new TextureTarget(512,512,true,Minecraft.ON_OSX);
            paper.setClearColor(0,0,0,0);paper.clear(Minecraft.ON_OSX);paper.bindWrite(true);
            RenderSystem.setProjectionMatrix(new org.joml.Matrix4f().setOrtho(0,256,256,0,-1000,1000),VertexSorting.ORTHOGRAPHIC_Z);
            GuiGraphics g=new GuiGraphics(c,c.renderBuffers().bufferSource());
            view.renderHeldMap(g);g.flush();ready=true;
        }finally{
            model.popPose();RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection,sorting);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            RenderSystem.setShaderColor(1,1,1,1);
            if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
        }
    }
    @SubscribeEvent public static void renderHand(RenderHandEvent event){
        var mc=Minecraft.getInstance();
        boolean settings=mc.screen instanceof AtlasHeldSettingsScreen;
        if(settings&&mc.player!=null){
            InteractionHand chosen=mc.player.getMainHandItem().is(ModItems.ATLAS.get())?InteractionHand.MAIN_HAND:InteractionHand.OFF_HAND;
            if(event.getHand()!=chosen){event.setCanceled(true);return;}
        }
        if(!event.getItemStack().is(ModItems.ATLAS.get()))return;
        event.setCanceled(true);
        var c=Minecraft.getInstance();if(c.player==null)return;
        HumanoidArm arm=event.getHand()==InteractionHand.MAIN_HAND?c.player.getMainArm():c.player.getMainArm().getOpposite();
        PoseStack pose=event.getPoseStack();pose.pushPose();
        boolean cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),blend=GL11.glIsEnabled(GL11.GL_BLEND);
        try{
            if(settings){float raised=((AtlasHeldSettingsScreen)mc.screen).raised();pose.translate(-.18f*raised,0,0);float scale=1-.12f*raised;pose.scale(scale,scale,scale);}
            if(settings||HeldMapMotion.twoHanded(event.getHand()==InteractionHand.MAIN_HAND,c.player.getOffhandItem().isEmpty()))twoHands(event);
            else oneHand(event,arm);
            if(event.getMultiBufferSource() instanceof MultiBufferSource.BufferSource source)source.endBatch();
            // Same paper coordinate system as vanilla renderMap, scaled for our 256px canvas.
            pose.mulPose(Axis.YP.rotationDegrees(180));pose.mulPose(Axis.ZP.rotationDegrees(180));
            pose.scale(.38f,.38f,.38f);pose.translate(-.5f,-.5f,0);pose.scale(1/256f,1/256f,1/256f);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            if(ready&&paper!=null)RenderSystem.setShaderTexture(0,paper.getColorTextureId());
            else RenderSystem.setShaderTexture(0,new net.minecraft.resources.ResourceLocation(BiyiAtlas.MODID,"textures/atlas/paper_ink.png"));
            RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            var b=Tesselator.getInstance().getBuilder();var m=pose.last().pose();
            b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX);
            b.vertex(m,0,256,0).uv(0,0).endVertex();b.vertex(m,256,256,0).uv(1,0).endVertex();
            b.vertex(m,256,0,0).uv(1,1).endVertex();b.vertex(m,0,0,0).uv(0,1).endVertex();
            BufferUploader.drawWithShader(b.end());
        }finally{
            RenderSystem.setShaderColor(1,1,1,1);
            pose.popPose();if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
            if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();
        }
    }

    private static void hand(PoseStack p,MultiBufferSource buffers,int light,HumanoidArm arm){
        var c=Minecraft.getInstance();if(c.player==null||c.player.isInvisible())return;
        var renderer=(PlayerRenderer)c.getEntityRenderDispatcher().getRenderer(c.player);
        if(arm==HumanoidArm.RIGHT)renderer.renderRightHand(p,buffers,light,c.player);
        else renderer.renderLeftHand(p,buffers,light,c.player);
    }
    private static void mapGrip(PoseStack p,MultiBufferSource buffers,int light,HumanoidArm arm){
        float side=arm==HumanoidArm.RIGHT?1:-1;p.pushPose();
        try{p.mulPose(Axis.YP.rotationDegrees(92));p.mulPose(Axis.XP.rotationDegrees(45));
            p.mulPose(Axis.ZP.rotationDegrees(side*-41));p.translate(side*.3f,-1.1f,.45f);hand(p,buffers,light,arm);
        }finally{p.popPose();}
    }
    private static void twoHands(RenderHandEvent e){
        var p=e.getPoseStack();float swing=e.getSwingProgress(),root=Mth.sqrt(swing),wave=Mth.sin(root*Mth.PI);
        p.translate(0,.1f*Mth.sin(swing*Mth.PI),-.4f*wave);
        float tilt=HeldMapMotion.tilt(e.getInterpolatedPitch());
        if(Minecraft.getInstance().screen instanceof AtlasHeldSettingsScreen settings)tilt*=1-settings.raised();
        p.translate(0,.04f-e.getEquipProgress()*1.2f-tilt*.5f,-.72f);p.mulPose(Axis.XP.rotationDegrees(tilt*-85));
        p.pushPose();try{p.mulPose(Axis.YP.rotationDegrees(90));mapGrip(p,e.getMultiBufferSource(),e.getPackedLight(),HumanoidArm.RIGHT);mapGrip(p,e.getMultiBufferSource(),e.getPackedLight(),HumanoidArm.LEFT);}finally{p.popPose();}
        p.mulPose(Axis.XP.rotationDegrees(wave*20));p.scale(2,2,2);
    }
    private static void oneHand(RenderHandEvent e,HumanoidArm arm){
        var p=e.getPoseStack();float side=arm==HumanoidArm.RIGHT?1:-1,sw=e.getSwingProgress(),root=Mth.sqrt(sw),wave=Mth.sin(root*Mth.PI),equip=e.getEquipProgress();
        p.translate(side*.125f,-.125f,0);
        p.pushPose();
        try{
            p.mulPose(Axis.ZP.rotationDegrees(side*10));
            p.translate(side*(-.3f*wave+.64000005f),.4f*Mth.sin(root*Mth.TWO_PI)-.6f-equip*.6f,-.4f*Mth.sin(sw*Mth.PI)-.71999997f);
            p.mulPose(Axis.YP.rotationDegrees(side*45));p.mulPose(Axis.YP.rotationDegrees(side*wave*70));
            p.mulPose(Axis.ZP.rotationDegrees(side*Mth.sin(sw*sw*Mth.PI)*-20));
            p.translate(side*-1,3.6f,3.5f);p.mulPose(Axis.ZP.rotationDegrees(side*120));p.mulPose(Axis.XP.rotationDegrees(200));p.mulPose(Axis.YP.rotationDegrees(side*-135));p.translate(side*5.6f,0,0);
            hand(p,e.getMultiBufferSource(),e.getPackedLight(),arm);
        }finally{p.popPose();}
        p.translate(side*.51f,-.08f-equip*1.2f,-.75f);
        p.translate(side*-.5f*wave,.4f*Mth.sin(root*Mth.TWO_PI)-.3f*wave,-.3f*Mth.sin(sw*Mth.PI));
        p.mulPose(Axis.XP.rotationDegrees(-45*wave));p.mulPose(Axis.YP.rotationDegrees(side*wave*-30));
    }
}
