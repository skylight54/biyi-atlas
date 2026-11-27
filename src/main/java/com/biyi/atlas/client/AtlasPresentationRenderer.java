package com.biyi.atlas.client;

import com.biyi.atlas.map.PresentationRegistration;
import com.biyi.atlas.map.PresentationRegistration.Point;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Batch a registered image through the unchanged world polygon; source UVs never repeat. */
final class AtlasPresentationRenderer {
    private AtlasPresentationRenderer(){}
    static void draw(GuiGraphics g,ResourceLocation texture,PresentationRegistration registration,double[] bounds,
                     List<double[]> polygon,int width,int height,double cx,double cz,double zoom){
        double worldWidth=bounds[2]-bounds[0],worldHeight=bounds[3]-bounds[1];
        if(worldWidth<=0||worldHeight<=0||zoom<=0)return;
        int top=Math.max(16,(int)Math.floor(height/2.0+(bounds[1]-cz)*zoom));
        int bottom=Math.min(height-16,(int)Math.ceil(height/2.0+(bounds[3]-cz)*zoom));
        if(bottom<=top)return;
        g.flush();
        var previous=RenderSystem.getShader();int previousTexture=RenderSystem.getShaderTexture(0);
        float[] color=RenderSystem.getShaderColor().clone();
        boolean blend=GL11.glIsEnabled(GL11.GL_BLEND),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int src=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dst=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        var buffer=Tesselator.getInstance().getBuilder();boolean started=false;
        try{
            AtlasFogShader.configureBlend();RenderSystem.disableCull();RenderSystem.disableDepthTest();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);RenderSystem.setShaderTexture(0,texture);RenderSystem.setShaderColor(1,1,1,1);
            var pose=g.pose().last().pose();
            for(int y=top;y<bottom;y++){
                double worldZ=cz+(y+.5-height/2.0)/zoom;
                var cuts=new ArrayList<Double>();
                for(int i=0,j=polygon.size()-1;i<polygon.size();j=i++){
                    double[] p=polygon.get(i),q=polygon.get(j);
                    if((p[1]>worldZ)!=(q[1]>worldZ))cuts.add(p[0]+(worldZ-p[1])/(q[1]-p[1])*(q[0]-p[0]));
                }
                Collections.sort(cuts);
                double z0=(cz+(y-height/2.0)/zoom-bounds[1])/worldHeight;
                double z1=(cz+(y+1-height/2.0)/zoom-bounds[1])/worldHeight;
                for(int k=0;k+1<cuts.size();k+=2){
                    double left=Math.max(16,width/2.0+(cuts.get(k)-cx)*zoom);
                    double right=Math.min(width-16,width/2.0+(cuts.get(k+1)-cx)*zoom);
                    if(right<=left)continue;
                    double x0=(cx+(left-width/2.0)/zoom-bounds[0])/worldWidth;
                    double x1=(cx+(right-width/2.0)/zoom-bounds[0])/worldWidth;
                    var clipped=registration.clipSource(List.of(new Point(x0,z0),new Point(x0,z1),new Point(x1,z1),new Point(x1,z0)));
                    if(clipped.size()<3)continue;
                    if(!started){buffer.begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_TEX);started=true;}
                    for(int t=1;t+1<clipped.size();t++)for(Point p:List.of(clipped.get(0),clipped.get(t),clipped.get(t+1))){
                        float x=(float)(width/2.0+(bounds[0]+p.x()*worldWidth-cx)*zoom);
                        float z=(float)(height/2.0+(bounds[1]+p.z()*worldHeight-cz)*zoom);
                        float u=(float)Math.max(0,Math.min(1,registration.sourceX(p.x(),p.z())));
                        float v=(float)Math.max(0,Math.min(1,registration.sourceZ(p.x(),p.z())));
                        buffer.vertex(pose,x,z,0).uv(u,v).endVertex();
                    }
                }
            }
            if(started)BufferUploader.drawWithShader(buffer.end());
        }finally{
            RenderSystem.setShaderTexture(0,previousTexture);if(previous!=null)RenderSystem.setShader(()->previous);
            RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);RenderSystem.blendFuncSeparate(src,dst,srcAlpha,dstAlpha);
            if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();
            if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
            if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
        }
    }
}
