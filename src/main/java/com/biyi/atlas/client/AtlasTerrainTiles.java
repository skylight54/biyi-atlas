package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.map.AtlasMapData;
import com.biyi.atlas.map.TerrainSurvey;
import com.biyi.atlas.map.TerrainRaster;
import com.biyi.atlas.map.TerrainTilePos;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.LinkedHashMap;
import java.util.function.BiPredicate;

/** 按世界图块缓存栅格地图；移动缩放不重新烘焙，同步调查仅使邻接图块失效。 */
public final class AtlasTerrainTiles {
    private record Tile(ResourceLocation texture, int revision) {}
    private static final LinkedHashMap<Long,Tile> CACHE = new LinkedHashMap<>(128,.75F,true);
    private static TerrainSurvey owner;
    private static long tick = Long.MIN_VALUE;
    private static int uploads;
    private static final java.util.concurrent.ExecutorService WORKER=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"biyi-map-bake");t.setDaemon(true);return t;});
    private static boolean busy;
    private static long generation;
    private static final java.util.Map<Long,java.util.List<int[]>> MASKS=new java.util.HashMap<>();
    private static int maskRevision;
    private AtlasTerrainTiles() {}
    public static void reset() {
        var manager=Minecraft.getInstance().getTextureManager();
        CACHE.values().forEach(t->manager.release(t.texture()));
        CACHE.clear();owner=null;generation++;MASKS.clear();
    }
    private static Tile tile(TerrainSurvey data,int tx,int tz) {
        long key=AtlasMapData.key(tx,tz);
        Tile old=CACHE.get(key);
        int revision=data.tileRevision(tx,tz);
        if(old!=null && old.revision()==revision)return old;
        if(uploads>=2)return old;
        if(busy)return old;
        uploads++;busy=true;
        long expected=generation;
        TerrainSurvey snapshot=data.tileSnapshot(tx,tz);
        WORKER.execute(()->{
            try{
                int[] pixels=TerrainRaster.bakePadded(snapshot,tx,tz);
                Minecraft.getInstance().execute(()->{
                    busy=false;
                    if(expected==generation&&data==owner)upload(data,tx,tz,revision,pixels);
                });
            }catch(Exception failure){
                com.mojang.logging.LogUtils.getLogger().warn("[BiyiAtlas] 地形后台烘焙失败",failure);
                Minecraft.getInstance().execute(()->busy=false);
            }
        });
        return old;
    }
    private static void upload(TerrainSurvey data,int tx,int tz,int revision,int[] pixels){
        long key=AtlasMapData.key(tx,tz);
        Tile old=CACHE.get(key);
        NativeImage image=new NativeImage(130,130,false);
        for(int y=0;y<130;y++)for(int x=0;x<130;x++){
            int c=pixels[y*130+x];
            image.setPixelRGBA(x,y,(c&0xFF00FF00)|((c>>>16)&255)|((c&255)<<16));
        }
        var manager=Minecraft.getInstance().getTextureManager();
        ResourceLocation id=new ResourceLocation(BiyiAtlas.MODID,"ink_terrain/"+tx+"_"+tz);
        if(old!=null)manager.release(old.texture());
        DynamicTexture texture=new DynamicTexture(image);
        texture.setFilter(true,false);
        manager.register(id,texture);
        Tile result=new Tile(id,revision);CACHE.put(key,result);
        while(CACHE.size()>128){var it=CACHE.entrySet().iterator();var e=it.next();manager.release(e.getValue().texture());it.remove();}
    }
    public static void render(GuiGraphics g,TerrainSurvey data,int width,int height,
                              double cx,double cz,double scale,BiPredicate<Double,Double> visible){
        if(data!=owner){reset();owner=data;}
        int current=AtlasMapSession.visibilityStamp();
        if(current!=maskRevision){MASKS.clear();maskRevision=current;}
        long now=Minecraft.getInstance().level.getGameTime();
        if(now!=tick){tick=now;uploads=0;}
        var first = TerrainTilePos.atBlock((int)Math.floor(cx-width/(2*scale)), (int)Math.floor(cz-height/(2*scale)));
        var last = TerrainTilePos.atBlock((int)Math.floor(cx+width/(2*scale)), (int)Math.floor(cz+height/(2*scale)));
        try{
            for(int tz=first.z();tz<=last.z();tz++)for(int tx=first.x();tx<=last.x();tx++){
                if(!data.hasTile(tx,tz))continue;
                if(!AtlasMapSession.tileInScope(tx,tz))continue;
                Tile t=tile(data,tx,tz);if(t==null)continue;
                long maskKey=AtlasMapData.key(tx,tz);
                var rectangles=MASKS.get(maskKey);
                if(rectangles==null){
                    boolean[] visibleCells=new boolean[1024];
                    for(int row=0;row<32;row++)for(int col=0;col<32;col++){
                        double wx=tx*128+col*4,wz=tz*128+row*4;
                        visibleCells[row*32+col]=visible.test(wx,wz)&&visible.test(wx+4,wz)
                                &&visible.test(wx,wz+4)&&visible.test(wx+4,wz+4);
                    }
                    rectangles=com.biyi.atlas.map.TileMask.rectangles(visibleCells,32);
                    MASKS.put(maskKey,rectangles);
                }                for(int[] rect:rectangles){
                    int x1=(int)Math.round(width/2.0+(tx*128+rect[0]*4-cx)*scale);
                    int y1=(int)Math.round(height/2.0+(tz*128+rect[1]*4-cz)*scale);
                    int x2=(int)Math.round(width/2.0+(tx*128+rect[2]*4-cx)*scale);
                    int y2=(int)Math.round(height/2.0+(tz*128+rect[3]*4-cz)*scale);
                    int a=Math.max(16,x1),b=Math.max(16,y1),c=Math.min(width-16,x2),d=Math.min(height-16,y2);
                    if(c<=a||d<=b||x2<=x1||y2<=y1)continue;
                    double sourceWidth=(rect[2]-rect[0])*4.0,sourceHeight=(rect[3]-rect[1])*4.0;
                    g.blit(t.texture(),a,b,c-a,d-b,
                            (float)(1+rect[0]*4+(a-x1)*sourceWidth/(x2-x1)),
                            (float)(1+rect[1]*4+(b-y1)*sourceHeight/(y2-y1)),
                            Math.max(1,(int)Math.round((c-a)*sourceWidth/(x2-x1))),
                            Math.max(1,(int)Math.round((d-b)*sourceHeight/(y2-y1))),130,130);
                }            }
        }finally{g.setColor(1,1,1,1);}
    }
}
