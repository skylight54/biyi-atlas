package com.biyi.atlas.client;

import com.biyi.atlas.map.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import java.util.*;

/** One cached fog texture per polygon; animation raster work stays off the render thread. */
public final class AtlasFogRenderer {
    private static final ResourceLocation CLOUD=new ResourceLocation("biyi_atlas","textures/atlas/fog_ink.png");
    private static final java.util.concurrent.ExecutorService WORKER=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"biyi-fog");t.setDaemon(true);return t;});
    private static final Map<String,Tile> CACHE=new LinkedHashMap<>(24,.75f,true);
    private static final Set<String> PENDING=new HashSet<>();
    private static final Set<String> FAILED=new HashSet<>();
    private static int generation;
    private static volatile int cloudWidth=1024,cloudHeight=1024;
    private static class Tile {FogRaster.Mask mask;DynamicTexture texture;ResourceLocation id;int frame=-1;boolean busy;}
    public static void reset(){generation++;for(var t:CACHE.values())Minecraft.getInstance().getTextureManager().release(t.id);CACHE.clear();PENDING.clear();FAILED.clear();}
    private static String key(AtlasRegion r){return r.id()+":"+PresentationSpec.shape(r.dimension().toString(),r.layer(),r.outline());}
    private static NativeImage image(int[] pixels){
        var image=new NativeImage(FogRaster.SIZE,FogRaster.SIZE,false);
        for(int y=0;y<FogRaster.SIZE;y++)for(int x=0;x<FogRaster.SIZE;x++){
            int c=pixels[y*FogRaster.SIZE+x];image.setPixelRGBA(x,y,(c&0xFF00FF00)|((c>>>16)&255)|((c&255)<<16));
        }
        return image;
    }
    private static Tile request(AtlasRegion r){
        return request(r,false);
    }
    private static Tile request(AtlasRegion r,boolean exterior){
        String key=(exterior?"outside:":"")+key(r);var existing=CACHE.get(key);if(existing!=null)return existing;
        if(FAILED.contains(key)||PENDING.size()>=4||!PENDING.add(key))return null;
        int expected=generation;var polygon=r.outline().stream().map(double[]::clone).toList();double[] bounds=r.bounds().clone();
        WORKER.execute(()->{
            try{
                java.awt.image.BufferedImage source;
                try(var stream=Minecraft.getInstance().getResourceManager().getResourceOrThrow(CLOUD).open()){source=javax.imageio.ImageIO.read(stream);}
                int w=source.getWidth(),h=source.getHeight();
                cloudWidth=w;cloudHeight=h;
                var mask=FogRaster.bake(polygon,bounds,source.getRGB(0,0,w,h,null,0,w),w,h,exterior);
                Minecraft.getInstance().execute(()->{
                    if(expected!=generation)return;PENDING.remove(key);
                    var tile=new Tile();tile.mask=mask;tile.texture=new DynamicTexture(image(mask.pixels()));tile.texture.setFilter(true,false);
                    tile.id=Minecraft.getInstance().getTextureManager().register("biyi_fog",tile.texture);CACHE.put(key,tile);
                    while(CACHE.size()>24){var it=CACHE.entrySet().iterator();var first=it.next();Minecraft.getInstance().getTextureManager().release(first.getValue().id);it.remove();}
                });
            }catch(Exception e){Minecraft.getInstance().execute(()->{if(expected==generation){PENDING.remove(key);FAILED.add(key);}});com.mojang.logging.LogUtils.getLogger().warn("云雾烘焙失败",e);}
        });return null;
    }
    public static boolean ready(AtlasRegion r){return request(r)!=null;}
    public static void cloudRect(GuiGraphics g,double cx,double cz,double zoom,int width,int height,int l,int top,int r,int bottom){
        if(r<=l||bottom<=top)return;
        double period=Math.max(750,80/Math.max(.001,zoom));
        g.setColor(.17f,.22f,.24f,1);
        try{
            for(int y=top;y<bottom;){
                double wz=cz+(y-height/2.0)/zoom;long iz=(long)Math.floor(wz/period);
                int endY=Math.max(y+1,Math.min(bottom,(int)Math.ceil(height/2.0+((iz+1)*period-cz)*zoom)));
                double v=(wz/period-iz)*cloudHeight,vh=(endY-y)/zoom/period*cloudHeight;
                if((iz&1)!=0){v=cloudHeight-v;vh=-vh;}
                for(int x=l;x<r;){
                    double wx=cx+(x-width/2.0)/zoom;long ix=(long)Math.floor(wx/period);
                    int endX=Math.max(x+1,Math.min(r,(int)Math.ceil(width/2.0+((ix+1)*period-cx)*zoom)));
                    double u=(wx/period-ix)*cloudWidth,uw=(endX-x)/zoom/period*cloudWidth;
                    if((ix&1)!=0){u=cloudWidth-u;uw=-uw;}
                    g.blit(CLOUD,x,y,endX-x,endY-y,(float)u,(float)v,(int)Math.copySign(Math.max(1,Math.abs(uw)),uw),(int)Math.copySign(Math.max(1,Math.abs(vh)),vh),cloudWidth,cloudHeight);x=endX;
                }y=endY;
            }
        }finally{g.setColor(1,1,1,1);}
    }
    public static boolean outside(GuiGraphics g,AtlasRegion r,double cx,double cz,double zoom,int width,int height){
        var tile=request(r,true);if(tile==null)return false;
        double[] b=tile.mask.bounds();
        if(AtlasFogShader.draw(g,CLOUD,tile.texture.getId(),b,true,cx,cz,zoom,width,height,16,16,width-16,height-16))return true;
        int l=Math.max(16,Math.min(width-16,(int)Math.floor(width/2.0+(b[0]-cx)*zoom)));
        int right=Math.max(16,Math.min(width-16,(int)Math.ceil(width/2.0+(b[2]-cx)*zoom)));
        int top=Math.max(16,Math.min(height-16,(int)Math.floor(height/2.0+(b[1]-cz)*zoom)));
        int bottom=Math.max(16,Math.min(height-16,(int)Math.ceil(height/2.0+(b[3]-cz)*zoom)));
        cloudRect(g,cx,cz,zoom,width,height,16,16,width-16,top);
        cloudRect(g,cx,cz,zoom,width,height,16,bottom,width-16,height-16);
        cloudRect(g,cx,cz,zoom,width,height,16,top,l,bottom);
        cloudRect(g,cx,cz,zoom,width,height,right,top,width-16,bottom);
        return drawTile(g,tile,cx,cz,zoom,width,height,true);
    }
    public static boolean draw(GuiGraphics g,AtlasRegion r,double cx,double cz,double zoom,int width,int height,double progress){
        var t=request(r);if(t==null)return false;
        int frame=progress<0?-1:Math.min(48,(int)Math.floor(progress*48));
        if(t.frame!=frame&&!t.busy){
            t.busy=true;int expected=generation;WORKER.execute(()->{
                int[] pixels=frame<0?t.mask.pixels():FogRaster.dissolve(t.mask,frame/48.0);
                Minecraft.getInstance().execute(()->{
                    if(expected!=generation||CACHE.get(key(r))!=t)return;
                    // Update the existing texture rather than register/release each animation frame.
                    var image=t.texture.getPixels();
                    if(image!=null){for(int y=0;y<FogRaster.SIZE;y++)for(int x=0;x<FogRaster.SIZE;x++){int c=pixels[y*FogRaster.SIZE+x];image.setPixelRGBA(x,y,(c&0xFF00FF00)|((c>>>16)&255)|((c&255)<<16));}t.texture.upload();}
                    t.frame=frame;t.busy=false;
                });
            });
        }
        if(frame<0&&t.frame>=0)return false;
        return drawTile(g,t,cx,cz,zoom,width,height,false);
    }
    private static boolean drawTile(GuiGraphics g,Tile t,double cx,double cz,double zoom,int width,int height,boolean exterior){
        double[] b=t.mask.bounds();
        double x1=width/2.0+(b[0]-cx)*zoom,x2=width/2.0+(b[2]-cx)*zoom;
        double y1=height/2.0+(b[1]-cz)*zoom,y2=height/2.0+(b[3]-cz)*zoom;
        int l=Math.max(16,(int)Math.floor(x1)),top=Math.max(16,(int)Math.floor(y1)),right=Math.min(width-16,(int)Math.ceil(x2)),bottom=Math.min(height-16,(int)Math.ceil(y2));
        if(right<=l||bottom<=top)return true;
        if(AtlasFogShader.draw(g,CLOUD,t.texture.getId(),b,exterior,cx,cz,zoom,width,height,l,top,right,bottom))return true;
        float u=(float)((l-x1)/(x2-x1)*FogRaster.SIZE),v=(float)((top-y1)/(y2-y1)*FogRaster.SIZE);
        int uw=Math.max(1,(int)Math.ceil((right-l)/(x2-x1)*FogRaster.SIZE)),vh=Math.max(1,(int)Math.ceil((bottom-top)/(y2-y1)*FogRaster.SIZE));
        g.flush();boolean blend=GL11.glIsEnabled(GL11.GL_BLEND);
        try{AtlasFogShader.configureBlend();g.blit(t.id,l,top,right-l,bottom-top,u,v,uw,vh,FogRaster.SIZE,FogRaster.SIZE);}
        finally{if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();}
        return true;
    }
}
