package com.biyi.atlas.client;

import com.biyi.atlas.map.*;
import net.minecraft.client.Minecraft;

/** 一个玩家活动地图范围；磁盘探索库与活动区域/GPU缓存分离。 */
public final class AtlasMapSession {
    private static AtlasRegion active;
    private static TerrainSurvey terrain=new TerrainSurvey();
    private static boolean renderingAuthor;
    private static String renderKey="";
    private static int timer;
    private static String status="未选择地图范围";
    private static long lastRequest;
    private static AreaMapStatus.State state = AreaMapStatus.State.NO_AREA;
    private static String preparation="尚未启动区域加载";
    public static String preparation(){return preparation;}
    public static boolean complete(){return state == AreaMapStatus.State.COMPLETE;}
    public static AreaMapStatus baseMapStatus() {
        return new AreaMapStatus(active == null ? "" : active.id(), state, terrain.size(), status);
    }
    public static void preparation(String text){preparation=text;}
    public static String status(){return status;}
    public static void unpublished(String id){
        if(active!=null&&active.id().equals(id)) {
            state = AreaMapStatus.State.UNPUBLISHED;
            status="区域底图尚未发布（当前仅本地预览）";
        }
    }
    public static AtlasRegion active(){return active;}
    public static void reset(){active=null;state=AreaMapStatus.State.NO_AREA;preparation="尚未启动区域加载";AtlasPresentation.resetLibrary();terrain=new TerrainSurvey();renderKey="";timer=0;status="未选择地图范围";com.biyi.atlas.net.AreaTerrainMessage.clearClient();}
    public static void tick(){
        var c=Minecraft.getInstance();
        if(c.player==null||c.level==null||++timer%20!=0)return;
        if(!AtlasAuthoring.known())return;
        if(active!=null){
            var current=AtlasContent.get().regionById(active.id());
            if(current!=null&&current!=active)select(current);
            if(state == AreaMapStatus.State.REQUESTING && System.currentTimeMillis()-lastRequest>5000){
                lastRequest=System.currentTimeMillis();com.biyi.atlas.net.AreaTerrainMessage.request(active.id());
            }
        }
        AtlasRegion candidate=null;
        double best=Double.MAX_VALUE;
        for(var area:AtlasContent.get().mapAreas(c.level.dimension().location())){
            if(area.layer()!=0||!AtlasUnlocks.isVisible(area.id()))continue;
            double[] b=area.bounds();
            double score=area.contains(c.player.getX(),c.player.getZ())?-1:
                    Math.hypot((b[0]+b[2])/2-c.player.getX(),(b[1]+b[3])/2-c.player.getZ());
            if(score<best){candidate=area;best=score;}
        }
        if (c.screen == null && candidate != null && best < 0 && active != candidate) {
            select(candidate);
        } else if(active==null){if(candidate!=null)select(candidate);}
        else if(!AtlasUnlocks.isVisible(active.id())||AtlasContent.get().regionById(active.id())==null){reset();AtlasTerrainTiles.reset();}
    }
    public static void select(AtlasRegion area){
        var c=Minecraft.getInstance();
        com.biyi.atlas.net.AreaTerrainMessage.clearClient();
        AtlasPresentation.clear();
        active=area;state=AreaMapStatus.State.REQUESTING;
        status="正在请求区域底图…";
        lastRequest=System.currentTimeMillis();
        terrain=AtlasAuthoring.confirmed()?AtlasStorage.get(area.dimension()).terrain().filtered((x,z)->area.contains(x,z)):new TerrainSurvey();
        AtlasTerrainTiles.reset();
        if(c.getConnection()!=null)com.biyi.atlas.net.AreaTerrainMessage.request(area.id());
    }
    public static TerrainSurvey terrain(){return terrain;}
    public static void receive(String id,net.minecraft.nbt.CompoundTag tag){
        if(active==null||!active.id().equals(id))return;
        TerrainSurvey incoming=new TerrainSurvey();incoming.load(tag);
        terrain=incoming;AtlasTerrainTiles.reset();
        state=tag.getBoolean("complete") ? AreaMapStatus.State.COMPLETE : AreaMapStatus.State.PARTIAL;
        if(AtlasAuthoring.allowed()){
            AtlasStorage.get(active.dimension()).terrain().mergeFrom(incoming);AtlasStorage.markDirty();
        }
        AtlasPresentation.receive(active,tag);
        status=(complete()?"区域底图已载入 · ":"正在生成底图 · ")+incoming.size()+" 个采样点";
    }
    public static void rendering(boolean author,AtlasRegion area){
        renderingAuthor=author;
        String key=(author?"author":area==null?"none":area.id());
        if(!key.equals(renderKey)){renderKey=key;AtlasTerrainTiles.reset();}
    }
    public static boolean tileInScope(int tx,int tz){
        if(renderingAuthor)return true;
        if(active==null)return false;
        double[] b=active.bounds();return tx*128<b[2]&&(tx+1)*128>b[0]&&tz*128<b[3]&&(tz+1)*128>b[1];
    }
    public static boolean inside(double x,double z){return active!=null&&active.contains(x,z);}
    public static int visibilityStamp(){
        return java.util.Objects.hash(renderKey,AtlasContent.get().contentRevision(),AtlasServerView.ids(),
                AtlasServerView.authoritative(),(int)(System.currentTimeMillis()/2000));
    }
    public static String publish(String id){
        AtlasRegion area=AtlasContent.get().regionById(id);
        if(!AtlasAuthoring.allowed()||area==null||!area.mapArea())return "请选择一个有效的地图范围单位（mapArea）";
        if(area.layer()!=0||!area.dimension().equals(net.minecraft.world.level.Level.OVERWORLD.location()))return "底图采集发布当前支持主世界地表；其他层保留作者图片与元素";
        TerrainSurvey filtered=AtlasStorage.get(area.dimension()).terrain().filtered((x,z)->area.contains(x,z));
        if(filtered.size()==0)return "该区域尚无已采集底图，请先在区域内调查";
        ClientEvents.uploadContent();
        try{com.biyi.atlas.net.AreaTerrainMessage.publish(id,filtered.save());}
        catch(Exception e){return e.getMessage();}
        return "正在增量发布底图，成功以服务端回执为准；完整底图请用准备整个区域";
    }
    public static String prepare(String id){
        var c=Minecraft.getInstance();var area=AtlasContent.get().regionById(id);
        if(!AtlasAuthoring.allowed()||c.player==null||area==null||!area.mapArea())return "请先选择作者地图范围";
        if(area.layer()!=0||!area.dimension().equals(net.minecraft.world.level.Level.OVERWORLD.location()))return "区域准备当前只支持主世界地表层";
        ClientEvents.uploadContent();
        select(area);preparation="正在请求加载整个区域…";
        com.biyi.atlas.net.AreaTerrainMessage.prepare(id);
        return "已请求服务端分批准备区域，进度会显示在聊天/动作栏";
    }
}
