package com.biyi.atlas.server;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.map.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 作者显式请求的区域准备，一次只处理一个区块，每4个服务端tick推进一次。 */
@Mod.EventBusSubscriber(modid=BiyiAtlas.MODID)
public final class RegionPreparation {
    private static Job job;
    private static int cooldown;
    private static final class Job {
        final java.util.UUID author;final String area;
        final net.minecraft.server.MinecraftServer server;
        final net.minecraft.server.level.ServerLevel level;
        final java.util.List<int[]> chunks;final TerrainSurvey data=new TerrainSurvey();
        final AtlasRegionRegistry.Entry definition;
        int next,failed;long previewAt;
        Job(ServerPlayer p,AtlasRegionRegistry.Entry a,java.util.List<int[]> chunks,net.minecraft.server.level.ServerLevel level){
            author=p.getUUID();server=p.server;area=a.id();this.chunks=chunks;this.level=level;definition=a;
        }
    }
    public static void start(ServerPlayer p,String id){
        if(!p.isCreative()&&!p.hasPermissions(2))return;
        var area=AtlasRegionRegistry.entry(id);if(area==null||!area.mapArea())return;
        if(job!=null){p.displayClientMessage(Component.literal("已有区域正在准备，请等待完成或取消"),false);return;}
        var dimension=ResourceLocation.tryParse(area.dimension());if(dimension==null||area.layer()!=0||!area.dimension().equals("minecraft:overworld"))return;
        var level=p.server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,dimension));
        if(level==null||area.outline().size()<3)return;
        double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
        for(var v:area.outline()){minX=Math.min(minX,v[0]);maxX=Math.max(maxX,v[0]);minZ=Math.min(minZ,v[1]);maxZ=Math.max(maxZ,v[1]);}
        int x1=Math.floorDiv((int)Math.floor(minX),16),x2=Math.floorDiv((int)Math.ceil(maxX),16);
        int z1=Math.floorDiv((int)Math.floor(minZ),16),z2=Math.floorDiv((int)Math.ceil(maxZ),16);
        if((long)(x2-x1+1)*(z2-z1+1)>32768){p.displayClientMessage(Component.literal("区域超过32768区块，请拆分后加载"),false);return;}
        java.util.List<int[]> chunks=new java.util.ArrayList<>();
        for(int z=z1;z<=z2;z++)for(int x=x1;x<=x2;x++)
            if(MapPolygon.intersectsRect(area.outline(),x*16.0,z*16.0,(x+1)*16.0,(z+1)*16.0))chunks.add(new int[]{x,z});
        if(chunks.isEmpty())return;
        job=new Job(p,area,chunks,level);cooldown=0;
        com.biyi.atlas.net.AreaTerrainMessage.progress(p,id,"开始加载 · 0 / "+chunks.size()+" 区块");
        p.displayClientMessage(Component.literal("开始准备区域："+id+"，共 "+chunks.size()+" 区块（可能加载/生成区域内区块）"),false);
    }
    public static void cancel(ServerPlayer p){
        if(job!=null&&(job.author.equals(p.getUUID())||p.hasPermissions(2))){com.biyi.atlas.net.AreaTerrainMessage.progress(p,job.area,"已取消区域加载，保留原底图");job=null;p.displayClientMessage(Component.literal("区域准备已取消，未发布半成品"),false);}
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END||job==null||--cooldown>0)return;
        cooldown=1;Job j=job;
        var p=j.server.getPlayerList().getPlayer(j.author);
        if(p==null||(!p.isCreative()&&!p.hasPermissions(2))){job=null;return;}
        var current=AtlasRegionRegistry.entry(j.area);
        if(current==null||!sameShape(current,j.definition)){
            job=null;p.displayClientMessage(Component.literal("区域边界或层级已改变，准备已取消，请重新开始"),false);return;
        }
        long deadline=System.nanoTime()+6_000_000L;
        int processed=0;
        do{
        int[] chunk=j.chunks.get(j.next);
        try{
            j.level.getChunk(chunk[0],chunk[1]);
            if(!SurfaceSurvey.sample(j.level,j.data,chunk[0],chunk[1]))j.failed++;
        }catch(Exception failure){j.failed++;com.mojang.logging.LogUtils.getLogger().warn("区域采样失败",failure);}
        j.next++;
        }while(j.next<j.chunks.size()&&++processed<4&&System.nanoTime()<deadline);
        if(j.server.getTickCount()%20==0){
            String text="区域加载："+j.next+" / "+j.chunks.size()+" 区块";
            p.displayClientMessage(Component.literal(text),true);com.biyi.atlas.net.AreaTerrainMessage.progress(p,j.area,text);
        }
        if(j.data.size()>0&&j.next<j.chunks.size()&&System.currentTimeMillis()-j.previewAt>=5000){
            j.previewAt=System.currentTimeMillis();com.biyi.atlas.net.AreaTerrainMessage.previewPrepared(p,j.area,j.data);
        }
        if(j.next==j.chunks.size()){
            job=null;
            if(j.failed>0)p.displayClientMessage(Component.literal("其中 "+j.failed+" 区块有空列或采样失败，缺失位置保持空白"),false);
            if(j.data.size()==0){p.displayClientMessage(Component.literal("区域没有有效地表，未发布"),false);return;}
            com.biyi.atlas.net.AreaTerrainMessage.storePrepared(p,j.area,j.data);
        }
    }
    @SubscribeEvent public static void stop(net.minecraftforge.event.server.ServerStoppedEvent event){if(job!=null&&job.server==event.getServer())job=null;}
    private static boolean sameShape(AtlasRegionRegistry.Entry a,AtlasRegionRegistry.Entry b){
        if(!a.dimension().equals(b.dimension())||a.layer()!=b.layer()||a.outline().size()!=b.outline().size())return false;
        for(int i=0;i<a.outline().size();i++)if(!java.util.Arrays.equals(a.outline().get(i),b.outline().get(i)))return false;
        return true;
    }
}
