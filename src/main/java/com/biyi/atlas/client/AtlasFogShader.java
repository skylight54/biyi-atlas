package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

/** Samples one continuous cloud field; cached polygon textures supply only opacity. */
@Mod.EventBusSubscriber(modid=BiyiAtlas.MODID, value=Dist.CLIENT, bus=Mod.EventBusSubscriber.Bus.MOD)
public final class AtlasFogShader {
    private static ShaderInstance shader;
    private AtlasFogShader(){}

    /** Preserve the opaque paper under transparent fog, including repeated cached shader draws. */
    public static void configureBlend(){
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    @SubscribeEvent public static void register(RegisterShadersEvent event){
        shader=null;
        try{
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation(BiyiAtlas.MODID,"atlas_fog"),DefaultVertexFormat.POSITION_TEX),s->shader=s);
        }catch(java.io.IOException e){
            com.mojang.logging.LogUtils.getLogger().warn("云雾合成着色器不可用，使用兼容绘制",e);
        }
    }

    static boolean draw(GuiGraphics g,ResourceLocation cloud,int mask,double[] bounds,boolean exterior,
                        double cx,double cz,double zoom,int width,int height,int l,int top,int r,int bottom){
        var s=shader;if(s==null)return false;
        if(r<=l||bottom<=top)return true;
        // Flush labels/fills before the immediate quad; their RenderTypes change blend state.
        g.flush();
        boolean blend=GL11.glIsEnabled(GL11.GL_BLEND);
        int srcRgb=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB),dstRgb=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB);
        int srcAlpha=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
        var previous=RenderSystem.getShader();
        int tex0=RenderSystem.getShaderTexture(0),tex1=RenderSystem.getShaderTexture(1);
        try{
            configureBlend();
            RenderSystem.setShader(()->s);
            RenderSystem.setShaderTexture(0,cloud);RenderSystem.setShaderTexture(1,mask);
            s.safeGetUniform("MaskBounds").set((float)(bounds[0]/750),(float)(bounds[1]/750),
                    (float)(bounds[2]/750),(float)(bounds[3]/750));
            s.safeGetUniform("OutsideAlpha").set(exterior?1f:0f);
            s.safeGetUniform("Tint").set(exterior?.17f:1f,exterior?.22f:1f,exterior?.24f:1f);
            float u0=(float)((cx+(l-width/2.0)/zoom)/750),u1=(float)((cx+(r-width/2.0)/zoom)/750);
            float v0=(float)((cz+(top-height/2.0)/zoom)/750),v1=(float)((cz+(bottom-height/2.0)/zoom)/750);
            var b=Tesselator.getInstance().getBuilder();var m=g.pose().last().pose();
            b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX);
            b.vertex(m,l,top,0).uv(u0,v0).endVertex();b.vertex(m,l,bottom,0).uv(u0,v1).endVertex();
            b.vertex(m,r,bottom,0).uv(u1,v1).endVertex();b.vertex(m,r,top,0).uv(u1,v0).endVertex();
            BufferUploader.drawWithShader(b.end());return true;
        }finally{
            RenderSystem.setShaderTexture(0,tex0);RenderSystem.setShaderTexture(1,tex1);
            if(previous!=null)RenderSystem.setShader(()->previous);
            RenderSystem.blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);
            if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();
        }
    }
}
