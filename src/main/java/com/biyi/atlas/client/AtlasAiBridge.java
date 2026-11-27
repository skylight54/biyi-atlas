package com.biyi.atlas.client;

import com.biyi.atlas.map.*;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** 本地文件桥。只在作者打开地图时消费请求，MCP进程不接触世界存档。 */
public final class AtlasAiBridge {
    private static int ticks;
    private static long heartbeat,lastOperation;
    private static volatile String action="暂无操作",outcome="等待请求";
    private static volatile boolean working;
    public static String connectionStatus(){return System.currentTimeMillis()-heartbeat<10000?"MCP已连接（心跳活跃）":"等待MCP连接";}
    public static String connectionBadge(){return System.currentTimeMillis()-heartbeat<10000?"MCP在线":"MCP待连接";}
    public static java.util.List<String> infoLines(){return java.util.List.of("最近操作："+action,"结果："+outcome,
            "最近处理："+(lastOperation==0?"尚无":java.time.Instant.ofEpochMilli(lastOperation).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)),
            AtlasMapSession.preparation(),AtlasPresentation.status(),"完整底图就绪后可导出AI美化素材","展示图失效时自动回到底图");}
    private AtlasAiBridge(){}
    private static String digest(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    public static void tick(){
        if(++ticks%20!=0)return;
        Path dir=FMLPaths.GAMEDIR.get().resolve("biyi_atlas/ai_bridge");
        try{heartbeat=Files.getLastModifiedTime(dir.resolve("heartbeat.json")).toMillis();}catch(Exception ignored){}
        var mc=Minecraft.getInstance();
        boolean map=mc.screen instanceof AtlasScreen;
        boolean held=mc.player!=null&&AtlasHeldRenderer.holdingMap(mc.player)&&(mc.screen==null||mc.screen instanceof AtlasHeldSettingsScreen);
        if(working||!AtlasAuthoring.allowed()||!(map||held)||mc.level==null)return;
        Path request=dir.resolve("request.json");if(!Files.isRegularFile(request))return;
        JsonObject result=new JsonObject();
        try{
            if(Files.size(request)>65536)throw new IllegalArgumentException("请求过大");
            var r=JsonParser.parseString(Files.readString(request)).getAsJsonObject();
            result.addProperty("id",r.get("id").getAsString());
            Files.delete(request);
            if(Math.abs(System.currentTimeMillis()-r.get("created").getAsLong())>60000)throw new IllegalArgumentException("请求已过期，请重新请求");
            var content=AtlasContent.get();
            action=r.get("action").getAsString();
            if(!map&&!action.equals("capture"))throw new IllegalStateException("此操作需要打开作者地图");
            lastOperation=System.currentTimeMillis();outcome="正在处理";
            if(action.equals("export_art")){
                if(!digest(content.exportAuthoredBytes()).equals(r.get("revision").getAsString()))throw new IllegalStateException("地图版本已改变");
                var area=AtlasMapSession.active();
                if(area==null||!area.id().equals(r.get("area").getAsString())||!AtlasMapSession.complete())throw new IllegalStateException("请先一键加载整个区域并等待完成");
                var snapshot=new TerrainSurvey();snapshot.load(AtlasMapSession.terrain().save());
                Path merged=content.exportMerged();if(merged==null)throw new IllegalStateException("导出地图失败");
                Files.copy(merged,dir.resolve("map.json"),StandardCopyOption.REPLACE_EXISTING);
                working=true;
                java.util.concurrent.CompletableFuture.runAsync(()->{
                    try{PresentationExporter.export(area,snapshot,dir.resolve("art-export"));result.addProperty("exportDirectory",dir.resolve("art-export").toString());result.addProperty("ok",true);outcome="AI底图与世界信息已导出";}
                    catch(Exception e){result.addProperty("ok",false);result.addProperty("error",e.toString());outcome=e.getMessage();}
                    finally{writeResponse(dir,result);working=false;}
                });return;
            }
            if(r.get("action").getAsString().equals("road_style")){
                if(!digest(content.exportAuthoredBytes()).equals(r.get("revision").getAsString()))throw new IllegalStateException("地图已改变，请重新拉取");
                var road=content.roadByKeyPublic(r.get("road").getAsString());
                if(road==null)throw new IllegalArgumentException("道路不存在");
                int width=r.get("width").getAsInt(),rgb=r.get("rgb").getAsInt();
                if(width<1||width>12||rgb<0||rgb>0xFFFFFF)throw new IllegalArgumentException("道路样式超出范围");
                Files.createDirectories(dir.resolve("backups"));
                Files.write(dir.resolve("backups/"+System.currentTimeMillis()+".json"),content.exportAuthoredBytes());
                content.replaceRoad(road,new AtlasRoad(road.id(),road.name(),road.dimension(),road.layer(),road.region(),road.points(),width,rgb,r.get("dashed").getAsBoolean(),road.nodes(),road.branchOf()));
                content.saveAuthored();
                if(mc.player!=null)mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("AI已调整道路样式："+road.name()+"（原始内容已备份）"),false);
            }else if(action.equals("show_view")){
                String mode=r.get("mode").getAsString(),panel=r.get("panel").getAsString();
                if(!java.util.Set.of("author","player").contains(mode)||!java.util.Set.of("none","details","mcp","regions","view").contains(panel))throw new IllegalArgumentException("视图参数无效");
                if(mode.equals("player")&&panel.equals("mcp"))throw new IllegalArgumentException("MCP状态只在作者端显示");
                AtlasScreen.showForAi(mode.equals("author"),panel);
            }else if(action.equals("prepare_area")){
                if(!digest(content.exportAuthoredBytes()).equals(r.get("revision").getAsString()))throw new IllegalStateException("地图版本已改变");
                result.addProperty("prepareRequest",AtlasMapSession.prepare(r.get("area").getAsString()));
            }else if(action.equals("import_art")){
                if(!digest(content.exportAuthoredBytes()).equals(r.get("revision").getAsString()))throw new IllegalStateException("地图版本已改变");
                var manifest=JsonParser.parseString(Files.readString(dir.resolve("art-upload.json"))).getAsJsonObject();
                if(!manifest.get("area").getAsString().equals(r.get("area").getAsString()))throw new IllegalArgumentException("展示图区域不匹配");
                byte[] png=Files.readAllBytes(dir.resolve("art-upload.png"));PresentationSpec.validatePng(png);
                var art=new net.minecraft.nbt.CompoundTag();art.putByteArray("png",png);art.putString("shape",manifest.get("shape").getAsString());art.putString("base",manifest.get("base").getAsString());art.putString("name",manifest.has("variantName")?manifest.get("variantName").getAsString():"");
                PresentationRegistration.fromManifest(manifest).writeTo(art);
                com.biyi.atlas.net.AreaTerrainMessage.publishArt(r.get("area").getAsString(),art);
                result.addProperty("artRequest","已提交，等待服务端版本校验");
            }else if(r.get("action").getAsString().equals("publish_area")){
                if(!digest(content.exportAuthoredBytes()).equals(r.get("revision").getAsString()))throw new IllegalStateException("地图已改变，请重新拉取");
                String id=r.get("area").getAsString();
                var area=content.regionById(id);if(area==null||!area.mapArea())throw new IllegalArgumentException("地图范围不存在");
                AtlasMapSession.select(area);
                result.addProperty("publishRequest",AtlasMapSession.publish(id));
            }else if(!r.get("action").getAsString().equals("capture"))throw new IllegalArgumentException("未知操作");
            Path merged=content.exportMerged();if(merged==null)throw new IllegalStateException("导出地图失败");
            Files.copy(merged,dir.resolve("map.json"),StandardCopyOption.REPLACE_EXISTING);
            result.addProperty("revision",digest(content.exportAuthoredBytes()));
            result.addProperty("capturedAt",System.currentTimeMillis());
            result.addProperty("screen",map?"atlas":mc.screen instanceof AtlasHeldSettingsScreen?"held_settings":"held");
            result.addProperty("heldZoom",AtlasSettings.heldZoom());
            if(mc.player!=null)result.addProperty("pitch",mc.player.getXRot());
            result.addProperty("terrainStatus",AtlasMapSession.status());
            result.addProperty("activeArea",AtlasMapSession.active()==null?"":AtlasMapSession.active().id());
            result.addProperty("receivedSamples",AtlasMapSession.terrain().size());
            result.addProperty("preparation",AtlasMapSession.preparation());
            result.addProperty("complete",AtlasMapSession.complete());
            result.addProperty("presentation",AtlasPresentation.status());
            result.addProperty("presentationChoice",AtlasMapSession.active()==null?"":AtlasPresentation.choiceLabel(AtlasMapSession.active().id()));
            result.add("presentationRegistration",new Gson().toJsonTree(AtlasPresentation.registration()));
            result.addProperty("unlockReveal",mc.screen instanceof AtlasScreen screen?screen.revealStatus():"地图未打开");
            // 按需获取真实游戏帧，每次请求一次；不在每帧/定时循环截图。
            boolean capture=r.get("action").getAsString().equals("capture");
            if(capture)try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(dir.resolve("map.png"));}
            result.addProperty("screenshot",capture);
            result.addProperty("ok",true);
            outcome="已处理";
        }catch(Exception e){outcome=e.getMessage();result.addProperty("ok",false);result.addProperty("error",e.toString());try{Files.deleteIfExists(request);}catch(Exception ignored){}}
        writeResponse(dir,result);
    }
    private static void writeResponse(Path dir,JsonObject result){
        try{
            Files.createDirectories(dir);
            Path tmp=dir.resolve("response.tmp");Files.writeString(tmp,result.toString(),StandardCharsets.UTF_8);
            Files.move(tmp,dir.resolve("response.json"),StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception e){com.mojang.logging.LogUtils.getLogger().warn("AI地图桥响应失败",e);}
    }
}
