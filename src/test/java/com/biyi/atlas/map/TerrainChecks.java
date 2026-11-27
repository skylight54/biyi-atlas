package com.biyi.atlas.map;

import net.minecraft.nbt.CompoundTag;

/** 不依赖游戏窗口的几何与存档回归检查。 */
public final class TerrainChecks {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        FoundationChecks.run();
        checkUiLayout();
        checkProjectBundle();
        check(HeldMapView.clamp(Double.NaN)==HeldMapView.DEFAULT,"异常缩放回到默认");
        double held=HeldMapView.DEFAULT;for(int i=0;i<100;i++)held=HeldMapView.scroll(held,1);
        check(held==HeldMapView.MAX,"滚轮放大达到上限停止");
        for(int i=0;i<200;i++)held=HeldMapView.scroll(held,-1);
        check(held==HeldMapView.MIN,"滚轮缩小达到下限停止");
        check(HeldMapView.scroll(.35,Double.POSITIVE_INFINITY)==.35,"异常滚轮输入不改变倍率");
        checkFogReveal();
        checkEditHistory();
        checkPresentationLibrary();
        checkPresentationRegistration();
        for(int[] window:new int[][]{{320,240},{420,240},{640,360},{853,533},{1280,720}}){
            var compact=com.biyi.atlas.client.AtlasUiLayout.frame(window[0],window[1],false);
            check(compact.drawer().width()*compact.drawer().height()<window[0]*window[1]*.2,"玩家详情卡片占屏小于20%而非半屏");
            check(compact.contentBottom()-compact.contentTop()>=20,"最小玩家卡片可滚动操作一行");
            check(compact.drawer().y()>compact.header().y()+compact.header().height(),"玩家卡片不与导航重叠");
        }
        TerrainSurvey fp1=new TerrainSurvey(),fp2=new TerrainSurvey();
        fp1.put(-2,3,64,0);fp1.put(4,5,72,2);fp2.put(4,5,72,2);fp2.put(-2,3,64,0);
        check(fp1.fingerprint().equals(fp2.fingerprint()),"展示图底图指纹与插入顺序无关");
        fp2.putStructure(-2,3,1);check(!fp1.fingerprint().equals(fp2.fingerprint()),"建筑变化使展示图失效");
        var pngHeader=java.nio.ByteBuffer.allocate(24);pngHeader.putLong(0x89504e470d0a1a0aL);pngHeader.putInt(16,2048);pngHeader.putInt(20,1024);
        PresentationSpec.validatePng(pngHeader.array());pngHeader.putInt(16,2049);
        boolean rejected=false;try{PresentationSpec.validatePng(pngHeader.array());}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"展示图尺寸上限验证");
        ContentFingerprint fingerprint=new ContentFingerprint();byte[] bytes={1,2,3};
        check(fingerprint.changed(bytes),"连接首次上传");fingerprint.sent(bytes);
        check(!fingerprint.changed(new byte[]{1,2,3}),"相同内容保存不重复同步");
        check(fingerprint.changed(new byte[]{1,2,4}),"真实编辑仍上传");fingerprint.reset();
        check(fingerprint.changed(bytes),"重连可重新同步");
        var registry=new java.util.LinkedHashMap<String,com.biyi.atlas.server.AtlasRegionRegistry.Entry>();
        com.biyi.atlas.server.AtlasRegionRegistry.mergeDocument("{\"regions\":[{\"id\":\"area\",\"outline\":[[0,0],[10,0],[10,10]]}]}",registry);
        com.biyi.atlas.server.AtlasRegionRegistry.mergeDocument("{\"Regions\":{\"list\":[{\"id\":\"area\",\"outline\":[[-3000,-3000],[-2000,-3000],[-2000,-2000]]}]}}",registry);
        check(registry.get("area").outline().get(0)[0]==-3000,"存档内权威区域覆盖旧来源边界");
        com.biyi.atlas.server.AtlasRegionRegistry.mergeDocument("{\"Regions\":{\"list\":[]},\"Removed\":{\"regions\":[\"area\"]}}",registry);
        check(registry.isEmpty(),"权威删除不被旧配置复活");
        check(java.nio.file.Path.of("saves/world/.").toAbsolutePath().normalize().getFileName().toString().equals("world"),"集成存档路径去除末尾点，不能生成sp_.");
        TerrainSurvey published=new TerrainSurvey();published.put(0,0,64,TerrainSurvey.LAND);published.put(1,0,70,TerrainSurvey.FOREST);
        TerrainSurvey update=new TerrainSurvey();update.put(0,0,66,TerrainSurvey.SNOW);update.putStructure(0,0,123);
        published.mergeFrom(update);
        check(published.size()==2&&TerrainSurvey.height(published.get(0,0))==66&&published.has(1,0),"局部发布保留完整底图其余采样");
        check(published.buildingAt(0,0),"发布合并建筑掩码");
        check(RoadStyle.width(12,10)<=3&&RoadStyle.width(1,.01)>=1,"道路宽度随缩放有界");
        boolean[] visibility=new boolean[1024];java.util.Arrays.fill(visibility,true);
        check(TileMask.rectangles(visibility,32).size()==1,"完整可见图块合并为一次绘制");
        for(int z=8;z<24;z++)for(int x=8;x<24;x++)visibility[z*32+x]=false;
        var rectangles=TileMask.rectangles(visibility,32);
        for(int z=0;z<32;z++)for(int x=0;x<32;x++){
            boolean covered=false;
            for(int[] r:rectangles)if(x>=r[0]&&x<r[2]&&z>=r[1]&&z<r[3])covered=true;
            check(covered==visibility[z*32+x],"合并不填充锁区孔洞或遗漏可见地形");
        }
        CoalescedSave sync=new CoalescedSave();sync.changed();
        check(sync.poll(1000),"首次编辑同步");
        sync.changed();check(!sync.poll(2000),"限速窗口不重复同步");
        sync.changed();check(sync.poll(6000),"限速窗口后的最后一次编辑不丢失");
        check(!sync.poll(11000),"没有编辑不重复上传");
        sync.changed();sync.reset();check(!sync.poll(16000),"断线清理同步状态");
        java.util.List<double[]> polygon=java.util.List.of(new double[]{0,0},new double[]{100,0},new double[]{100,100},new double[]{0,100});
        check(MapPolygon.contains(polygon,50,50)&&!MapPolygon.contains(polygon,150,50),"地图区域多边形内外判定");
        check(MapPolygon.intersectsRect(polygon,90,90,110,110)&&!MapPolygon.intersectsRect(polygon,110,110,120,120),"区域准备仅遍历相交区块");
        check(MapPolygon.intersectsRect(polygon,-10,-10,110,110),"区域完全在单个区块内也会被准备");
        TerrainSurvey source=new TerrainSurvey();source.put(10,10,64,TerrainSurvey.LAND);source.put(40,40,90,TerrainSurvey.SNOW);
        source.putStructure(10,10,65535);
        var scoped=source.filtered((x,z)->MapPolygon.contains(polygon,x,z));
        check(scoped.size()==1&&scoped.has(10,10)&&!scoped.has(40,40)&&scoped.buildingAt(40,40),"区域快照仅保留区域内部地形和建筑");
        var snapshot=source.tileSnapshot(0,0);source.put(10,10,100,TerrainSurvey.SNOW);
        check(TerrainSurvey.height(snapshot.get(10,10))==64,"后台烘焙快照与主线程修改隔离");
        var entry=new com.biyi.atlas.server.AtlasRegionRegistry.Entry("area","Area","","always","area",true,true,true,
                0,0,0,50,50,true,polygon,"","minecraft:the_nether",-2);
        var packet=com.biyi.atlas.net.RegionGeometryMessage.of(java.util.List.of(entry));
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try{
            com.biyi.atlas.net.RegionGeometryMessage.encode(packet,buffer);
            var decoded=com.biyi.atlas.net.RegionGeometryMessage.decode(buffer).regions().get(0);
            check(decoded.dimension().equals("minecraft:the_nether")&&decoded.layer()==-2&&decoded.mapArea(),"区域协议保留维度层级范围标记");
        }finally{buffer.release();}
        var areaPacket=new com.biyi.atlas.net.AreaTerrainMessage(1,"area",123,0,1,new byte[]{1,2,3});
        buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try{
            com.biyi.atlas.net.AreaTerrainMessage.encode(areaPacket,buffer);
            var decoded=com.biyi.atlas.net.AreaTerrainMessage.decode(buffer);
            check(decoded.mode()==1&&decoded.area().equals("area")&&decoded.transfer()==123&&decoded.payload().length==3,"区域底图分块包编解码");
        }finally{buffer.release();}
        check(BuildingMaterials.matches("minecraft:oak_planks") && BuildingMaterials.matches("minecraft:stone_brick_stairs"),"木板与加工台阶识别");
        check(!BuildingMaterials.matches("minecraft:stone")&&!BuildingMaterials.matches("minecraft:oak_log")
                &&!BuildingMaterials.matches("minecraft:terracotta"),"不把天然石头原木陶瓦视作建筑");
        TerrainSurvey buildings=new TerrainSurvey();
        for(int z=-2;z<36;z++)for(int x=-2;x<36;x++)buildings.put(x,z,64,TerrainSurvey.LAND);
        buildings.put(10,10,140,TerrainSurvey.BUILDING);
        check(TerrainRaster.naturalHeight(buildings,10,10)==64,"屋顶高程不作为自然山峰");
        buildings.putStructure(10,10,65535);
        check(buildings.buildingAt(40,40)&&buildings.buildingAt(43,43)&&!buildings.buildingAt(44,44),"一方块建筑轮廓边界");
        buildings.putStructure(-1,-1,1);
        check(buildings.buildingAt(-4,-4)&&!buildings.buildingAt(-1,-1),"负坐标建筑位掩码");
        TerrainSurvey reloaded=new TerrainSurvey();reloaded.load(buildings.save());
        check(reloaded.buildingAt(40,40)&&reloaded.buildingAt(-4,-4),"建筑轮廓存档往返");
        int[] buildingRaster=TerrainRaster.bake(buildings,0,0);
        check(buildingRaster[41*128+41]!=buildingRaster[40*128+40],"建筑浅色内部与轮廓分开");
        TerrainSurvey rasterData = new TerrainSurvey();
        for (int z=-2;z<37;z++)for(int x=-2;x<37;x++) rasterData.put(x,z,64+x/2,
                x<16?TerrainSurvey.LAND:TerrainSurvey.WATER);
        int[] raster=TerrainRaster.bake(rasterData,0,0);
        check(raster.length==128*128 && (raster[64*128+8]>>>24)>0,"真实采样烘焙有效图像");
        check(raster[64*128+62]!=raster[64*128+63],"水陆过渡插值不是单色色块");
        check(TerrainRaster.bake(new TerrainSurvey(),0,0)[0]==0,"未知区域透明，不生成地形");
        int revision=rasterData.tileRevision(1,0);
        rasterData.put(31,16,100,TerrainSurvey.SNOW);
        check(rasterData.tileRevision(1,0)>revision,"边界更新使邻接图块失效");
        check(TerrainSurface.find(-65, -64, 320, y -> y == 74) == 74,
                "客户端高度图未填充时回退真实地表，不能缓存-65");
        check(TerrainSurface.find(90, -64, 320, y -> y == 72) == 72, "跳过空气及树冠后找到地面");
        check(TerrainSurface.find(-65, -64, 320, y -> false) == Integer.MIN_VALUE, "空列不生成假地面");
        check(TerrainSurface.find(63, -64, 320, y -> y == 63 || y == 42) == 63, "保留液体最上层表面");
        TerrainSurvey invalid = new TerrainSurvey();
        invalid.put(0, 0, -65, TerrainSurvey.LAND);
        invalid.put(1, 0, -64, TerrainSurvey.LAND);
        invalid.put(2, 0, 319, TerrainSurvey.SNOW);
        invalid.put(3, 0, 320, TerrainSurvey.SNOW);
        check(invalid.removeInvalidHeights(-64, 320) == 2 && invalid.size() == 2, "只移除异常高度，不删除有效负Y和山地");
        check(MapStroke.clip(-1000, -10, 1000, -10, 0, 0, 100, 100) == null, "屏幕外长线不消耗预算");
        var clipped = MapStroke.clip(-1000, 50, 1000, 50, 0, 0, 100, 100);
        check(clipped != null && Math.abs(clipped[0] - 0.5) < 1e-9 && Math.abs(clipped[1] - 0.55) < 1e-9, "穿屏长线裁剪");
        var horizontal = MapStroke.spans(0, 50, 100, 50, 2, 0, 0, 100, 100);
        check(horizontal.size() == 2 && horizontal.get(0).left() == 0 && horizontal.get(0).right() == 100, "水平道路连续，无方块间隔");
        var diagonal = MapStroke.spans(1, 1, 99, 99, 2, 0, 0, 100, 100);
        var reversed = MapStroke.spans(99, 99, 1, 1, 2, 0, 0, 100, 100);
        check(diagonal.equals(reversed), "线段正反向绘制一致");
        for (int i = 1; i < diagonal.size(); i++) {
            check(diagonal.get(i).top() == diagonal.get(i - 1).top() + 1, "斜线扫描行无缺口");
        }
        check(MapStroke.spans(10, 10, 10, 10, 2, 0, 0, 100, 100).isEmpty(), "重复节点不产生异常几何");
        check(TerrainContours.atLevel(8, 8, 8, 8, 8).isEmpty(), "平地不产生等高线");
        var slope = TerrainContours.atLevel(0, 16, 16, 0, 8);
        check(slope.size() == 2, "穿过两个三角形的等高线连续");
        for (var segment : slope) {
            check(Math.abs(segment.x1() - 0.5) < 1e-9 && Math.abs(segment.x2() - 0.5) < 1e-9, "坡面交点插值");
        }
        check(TerrainContours.atLevel(-16, 0, 0, -16, -8).size() == 2, "支持负高度等高线");
        check(TerrainContours.atLevel(0, 16, 0, 16, 8).size() == 2, "鞍点分支不丢失");
        // 穷举边界相等、平坦边及所有顶点高低组合，确保无 NaN、越界、退化线。
        for (int a : new int[]{0, 8, 16}) for (int b : new int[]{0, 8, 16})
            for (int c : new int[]{0, 8, 16}) for (int d : new int[]{0, 8, 16}) {
                for (var segment : TerrainContours.atLevel(a, b, c, d, 8)) {
                    for (double v : new double[]{segment.x1(), segment.z1(), segment.x2(), segment.z2()})
                        check(Double.isFinite(v) && v >= 0 && v <= 1, "等高线交点有效");
                    check(Math.hypot(segment.x1() - segment.x2(), segment.z1() - segment.z2()) > 1e-8, "无退化线");
                }
            }

        AtlasMapData old = new AtlasMapData();
        old.put(-3, 5, old.biomeIndex("minecraft:forest"), 80);
        old.addWaypoint("旧路标", -24, 60);
        old.terrain().put(-12, 20, -48, TerrainSurvey.WATER);
        old.terrain().put(-11, 20, 320, TerrainSurvey.SNOW);
        var restored = AtlasMapData.load(old.save());
        check(restored.has(-3, 5) && restored.waypoints().size() == 1, "新存档保留旧区块及路标");
        check(restored.terrain().size() == 2, "新网格保存重载");
        check(TerrainSurvey.height(restored.terrain().get(-12, 20)) == -48, "负 Y 无符号截断回归");
        check(TerrainSurvey.height(restored.terrain().get(-11, 20)) == 320, "高于300的山地保持高度");
        check(TerrainSurvey.kind(restored.terrain().get(-12, 20)) == TerrainSurvey.WATER, "水域类型保存");
        CompoundTag legacy = old.save();
        legacy.remove("terrainSurvey");
        var migrated = AtlasMapData.load(legacy);
        check(migrated.terrain().size() == 0 && migrated.has(-3, 5) && migrated.waypoints().size() == 1, "旧存档缺少网格仍正常读取");
        TerrainSurvey grid = new TerrainSurvey();
        for (int x = -4; x < 0; x++) for (int z = -4; z < 0; z++) grid.put(x, z, 70, TerrainSurvey.LAND);
        check(grid.hasChunk(-1, -1) && !grid.hasChunk(0, 0), "负区块与采样网格对应");
        CompoundTag damaged = grid.save();
        damaged.putIntArray("values", new int[]{1});
        grid.load(damaged);
        check(grid.size() == 1, "不匹配的数组长度不越界");
        System.out.println("TerrainChecks PASS: UI layout, contour geometry, negative coordinates/heights, NBT roundtrip and legacy compatibility");
    }
    private static void checkProjectBundle(){
        try{
            var dir=java.nio.file.Files.createTempDirectory("atlas-project-check");
            var game=dir.resolve("game");var world=game.resolve("saves/source");
            java.nio.file.Files.createDirectories(world.resolve("data/biyi_atlas_terrain/x.art.nbt.gallery"));
            String json="{\"Regions\":{\"schema\":2,\"list\":[]},\"Roads\":{\"schema\":2,\"list\":[]},\"Places\":{\"schema\":2,\"list\":[]}}";
            java.nio.file.Files.writeString(world.resolve("data/biyi_atlas_content.json"),json);
            java.nio.file.Files.write(world.resolve("data/biyi_atlas_terrain/x.art.nbt.gallery/index.nbt"),new byte[]{1,2,3});
            var archive=game.resolve("biyi_atlas/projects/test.biyi.zip");ProjectBundle.export(world,game,archive);
            var target=game.resolve("saves/renamed");java.nio.file.Files.createDirectories(target.resolve("data/biyi_atlas_terrain"));
            java.nio.file.Files.writeString(target.resolve("data/biyi_atlas_terrain/stale.nbt"),"old");
            var backup=ProjectBundle.install(archive,target,game);
            check(java.nio.file.Files.isRegularFile(backup.resolve("restore-index.json")),"工程导入先备份旧文件");
            check(!java.nio.file.Files.exists(target.resolve("data/biyi_atlas_terrain/stale.nbt")),"完整工程导入移走旧底图，避免同id残留");
            check(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(target.resolve("data/biyi_atlas_terrain/x.art.nbt.gallery/index.nbt")),new byte[]{1,2,3}),"多候选图库跨世界名完整往返");
            check(java.nio.file.Files.readString(game.resolve("biyi_atlas/authored/sp_renamed/content.json")).contains("ProjectSnapshot"),"工程写入新世界作者库，完整快照标记保留");
            var bad=dir.resolve("bad.zip");
            try(var zip=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(bad))){zip.putNextEntry(new java.util.zip.ZipEntry("terrain/../outside"));zip.write(1);zip.closeEntry();}
            boolean rejected=false;try{ProjectBundle.inspect(bad);}catch(Exception expected){rejected=true;}
            check(rejected,"工程拒绝目录穿越");
        }catch(Exception e){throw new AssertionError("工程回归失败",e);}
        var borrowed=AtlasRegion.fromJson(com.google.gson.JsonParser.parseString("{\"id\":\"qa_deleted\",\"name\":\"borrowed\",\"outline\":[[0,0],[50,0],[0,50]]}").getAsJsonObject());
        var content=AtlasContent.get();check(content.addServerRegion(borrowed),"接收他人区域");
        content.removeRegion(borrowed);check(!content.addServerRegion(borrowed),"删除墓碑拒绝迟到服务端区域复活");
        String exported=new String(content.exportAuthoredBytes(),java.nio.charset.StandardCharsets.UTF_8);
        check(exported.contains("qa_deleted")&&exported.contains("ProjectSnapshot"),"借入区域删除后墓碑与完整快照参与上传");
        TerrainSurvey data=new TerrainSurvey();for(int z=-3;z<37;z++)for(int x=-3;x<70;x++)data.put(x,z,64+x/4,TerrainSurvey.LAND);
        int[] a=TerrainRaster.bakePadded(data,0,0),b=TerrainRaster.bakePadded(data,1,0);
        for(int y=1;y<129;y++)check(a[y*130+129]==b[y*130+1],"相邻图块的线性过滤外扩像素一致");
    }

    private static void checkFogReveal(){
        for(double pitch=-90,last=1;pitch<=90;pitch+=5){
            double tilt=HeldMapMotion.tilt((float)pitch);
            check(tilt>=0&&tilt<=last+1e-6,"抬头低头持图角度连续且有界");last=tilt;
        }
        check(HeldMapMotion.tilt(75)<HeldMapMotion.tilt(0),"俯视时地图立起，平视时地图下放");
        check(HeldMapMotion.twoHanded(true,true)&&!HeldMapMotion.twoHanded(true,false)&&!HeldMapMotion.twoHanded(false,true),"主手副手/持物情况下匹配原版持图分支");
        double low=1,high=0;
        for(int z=-100;z<=100;z++){
            double alpha=FogRaster.edgeAlpha(-5,10,100,z,false);low=Math.min(low,alpha);high=Math.max(high,alpha);
            check(FogRaster.edgeAlpha(1,10,100,z,false)==1,"小区域真边界内部不因扰动漏出内容");
            check(FogRaster.edgeAlpha(-1,10,100,z,true)==1,"大区域真边界外部始终遮挡");
        }
        check(high-low>.15,"直边上的云雾轮廓有连续自然起伏");
        var queue=new UnlockRevealQueue();
        check(queue.poll(0,id->true)==null,"没有显式解锁事件，登录快照不产生演出");
        queue.offer("",0);queue.offer("area",100);queue.offer("area",200);queue.offer("late-geometry",100);
        check(queue.poll(300,id->false)==null,"几何或权限未就绪时等待，不提前显示地图");
        check("area".equals(queue.poll(400,id->id.equals("area"))),"显式授权按顺序播放");
        check(queue.poll(500,id->id.equals("area"))==null,"重复排队去重");
        check("late-geometry".equals(queue.poll(600,id->true)),"晚到几何仍可播放");
        queue.offer("expired",0);check(queue.poll(30001,id->true)==null,"过期演出不抢占用户界面");
        queue.offer("logout",31000);queue.clear();check(queue.poll(32000,id->true)==null,"离线清除演出队列");
        for(double d:new double[]{0,.1,.4,.9,1.1}){
            double last=1;
            for(int i=0;i<=100;i++){double a=FogRaster.opacity(d,i/100.0);check(a>=0&&a<=last+1e-9,"迷雾透明度单调消散");last=a;}
            check(FogRaster.opacity(d,0)==1&&FogRaster.opacity(d,1)==0,"消散首尾无残留");
        }
        var square=java.util.List.of(new double[]{-100,-100},new double[]{100,-100},new double[]{100,100},new double[]{-100,100});
        int[] cloud=new int[16];java.util.Arrays.fill(cloud,0xFFE2E7DF);
        var mask=FogRaster.bake(square,new double[]{-100,-100,100,100},cloud,4,4);
        check((mask.pixels()[192*FogRaster.SIZE+192]>>>24)==255,"迷雾内部完全遮蔽，不用稀疏印章透出地形");
        check((mask.pixels()[0]>>>24)==0,"区域之外保留透明");
        boolean soft=false;for(int pixel:mask.pixels())if((pixel>>>24)>0&&(pixel>>>24)<255){soft=true;break;}
        check(soft,"多边形云雾边缘羽化");
        var outside=FogRaster.bake(square,new double[]{-100,-100,100,100},cloud,4,4,true);
        check((outside.pixels()[0]>>>24)==255&&(outside.pixels()[192*FogRaster.SIZE+192]>>>24)==0,"外部云墨完整遮挡区域外，区域深处透明");
        int outsideColor=outside.pixels()[0]&0xFFFFFF;
        check(outsideColor!=0x252F32&&outsideColor!=0xE2E7DF,"区域外采用暗色贴图采样而非旧纯色或浅雾");
        for(int pixel:FogRaster.dissolve(mask,1))check((pixel>>>24)==0,"动画结束全部透明");
        var concave=java.util.List.of(new double[]{0,0},new double[]{100,0},new double[]{100,20},new double[]{20,20},new double[]{20,100},new double[]{0,100});
        double[] origin=FogRaster.origin(concave,new double[]{0,0,100,100});
        check(MapPolygon.contains(concave,origin[0],origin[1]),"凹区域从内部消散，不在区域外开洞");
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try{
            var grant=new com.biyi.atlas.net.UnlockRegionMessage(java.util.List.of("area"),"area");
            com.biyi.atlas.net.UnlockRegionMessage.encode(grant,buffer);
            var read=com.biyi.atlas.net.UnlockRegionMessage.decode(buffer);
            check(read.announcement().equals("area")&&read.regionIds().equals(java.util.List.of("area")),"新授权通知协议往返");
            buffer.clear();com.biyi.atlas.net.UnlockRegionMessage.encode(new com.biyi.atlas.net.UnlockRegionMessage(java.util.List.of("area")),buffer);
            check(com.biyi.atlas.net.UnlockRegionMessage.decode(buffer).announcement().isEmpty(),"普通同步没有解锁动画标记");
        }finally{buffer.release();}
    }

    private static void checkEditHistory(){
        try{
            var dir=java.nio.file.Files.createTempDirectory("atlas-edit-history");
            var file=dir.resolve("content.json");var history=new EditHistory(file);
            String original="{\"Regions\":{\"list\":[{\"id\":\"area\"},{\"id\":\"child\",\"parent\":\"area\"}]},\"Roads\":{\"list\":[{\"id\":\"road\",\"region\":\"child\"}]},\"Removed\":{\"regions\":[]}}";
            String deleted="{\"Regions\":{\"list\":[]},\"Roads\":{\"list\":[{\"id\":\"road\"}]},\"Removed\":{\"regions\":[\"area\",\"child\"]}}";
            history.write(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            check(!history.move(false),"首次创建文件不撤销成空文件");
            history.write(deleted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            history=new EditHistory(file);
            check(history.move(false)&&java.nio.file.Files.readString(file).equals(original),"重启后撤销区域级联：父子关系/道路归属/墓碑完整恢复");
            check(history.move(true)&&java.nio.file.Files.readString(file).equals(deleted),"重做删除");
            check(history.move(false),"再次撤销");
            history.write("{\"newPlace\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            check(!history.move(true),"撤销后新编辑清空重做分支");
            java.nio.file.Files.writeString(file,"external");boolean refused=false;
            try{history.move(false);}catch(IllegalStateException expected){refused=true;}
            check(refused&&java.nio.file.Files.readString(file).equals("external"),"外部修改不被撤销静默覆盖");
            var capFile=dir.resolve("cap.json");var capped=new EditHistory(capFile);
            for(int i=0;i<55;i++)capped.write(("{\"version\":"+i+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int count=0;while(capped.move(false))count++;
            check(count==50,"历史最多保留50步");
            var journal=capFile.resolveSibling("cap.json.history.json");
            var previous=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(journal)).getAsJsonObject();
            var pending=new com.google.gson.JsonObject();pending.addProperty("before",java.nio.file.Files.readString(capFile));pending.addProperty("after","interrupted");pending.add("previous",previous.deepCopy());
            var interrupted=previous.deepCopy();interrupted.add("pending",pending);java.nio.file.Files.writeString(journal,interrupted.toString());
            check(!new EditHistory(capFile).move(false),"中断发生在写内容前时恢复旧历史游标");
            interrupted.add("pending",pending);java.nio.file.Files.writeString(journal,interrupted.toString());java.nio.file.Files.writeString(capFile,"interrupted");
            check(!new EditHistory(capFile).move(false),"中断发生在写内容后时完成历史提交");
            check(!com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(journal)).getAsJsonObject().has("pending"),"恢复后清理写前日志");
            AtlasMapData markers=new AtlasMapData();markers.terrain().put(1,1,70,TerrainSurvey.SNOW);
            var marker=markers.addWaypoint("被误删路标",12,34);markers.togglePin(marker);
            check(markers.removeWaypoint(marker),"删除路标");
            markers=AtlasMapData.load(markers.save());
            check(markers.restoreDeletedWaypoint()&&markers.waypoints().equals(java.util.List.of(marker))&&markers.isPinned(marker),"路标删除撤回跨存档保留属性和置顶");
            check(markers.terrain().size()==1&&!markers.restoreDeletedWaypoint(),"撤回路标不修改地形且不重复恢复");
        }catch(Exception e){throw new AssertionError("Edit history",e);}
    }

    private static void checkPresentationLibrary(){
        try{
            var root=java.nio.file.Path.of("build/qa");java.nio.file.Files.createDirectories(root);
            var dir=java.nio.file.Files.createTempDirectory(root,"gallery-check-");
            var library=new PresentationLibrary(dir.resolve("gallery"));
            var a=new CompoundTag();a.putByteArray("png",new byte[]{1,2,3});a.putString("shape","shape");a.putString("base","base");a.putString("name","方案一");
            var legacy=dir.resolve("legacy.nbt");net.minecraft.nbt.NbtIo.writeCompressed(a,legacy.toFile());
            library.migrate(legacy);library.migrate(legacy);
            check(library.catalog("shape","base").getList("variants",10).size()==1,"旧展示图只迁移一次且保留原文件");
            var b=a.copy();b.putByteArray("png",new byte[]{4,5,6});b.putString("name","方案二");String second=library.add(b);
            check(library.catalog("shape","base").getList("variants",10).size()==2,"候选图追加不覆盖旧方案");
            library.add(b);check(library.catalog("shape","base").getList("variants",10).size()==2,"相同图片不重复入库");
            library.select("base");check(library.selected()==null,"可切换回保底地形");
            library.select(second);check(library.selected().getString("name").equals("方案二"),"候选选择持久化");
            check(!library.catalog("changed","base").getList("variants",10).getCompound(0).getBoolean("compatible"),"区域变化时旧候选保留但标记过期");
            boolean rejected=false;try{library.select("../../outside");}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"图库选择仅接受索引中的候选，不接受路径");
            check(java.nio.file.Files.exists(legacy),"迁移不删除原始生成图");
            var registered=b.copy();new PresentationRegistration(1,0,0,0,1,.05).writeTo(registered);
            String aligned=library.add(registered);
            check(!aligned.equals(second),"同一PNG的不同配准保存成独立候选，不覆盖旧方案");
            var reloaded=new PresentationLibrary(dir.resolve("gallery"));reloaded.select(aligned);
            check(PresentationRegistration.from(reloaded.selected()).tz()==.05,"配准矩阵随候选跨重新读取保存");
            var changed=b.copy();changed.putString("base","new-base");
            check(!library.add(changed).equals(second),"相同PNG对应新底图时不能复用旧指纹候选");
        }catch(Exception error){throw new AssertionError("图库回归失败",error);}
    }
    private static void checkPresentationRegistration(){
        var identity=PresentationRegistration.from(new CompoundTag());
        check(identity.equals(PresentationRegistration.IDENTITY),"旧候选不带配准参数时保持原映射");
        var registration=new PresentationRegistration(1.006,-.002,-.003,-.005,1.078,-.004);
        for(int y=0;y<=20;y++)for(int x=0;x<=20;x++){
            double u=x/20.0,v=y/20.0,wx=registration.mapX(u,v),wz=registration.mapZ(u,v);
            check(Math.abs(registration.sourceX(wx,wz)-u)<1e-10&&Math.abs(registration.sourceZ(wx,wz)-v)<1e-10,"倾斜/纵向拉伸的正逆配准保持地标位置");
        }
        var square=java.util.List.of(new PresentationRegistration.Point(0,0),new PresentationRegistration.Point(0,1),new PresentationRegistration.Point(1,1),new PresentationRegistration.Point(1,0));
        var shifted=new PresentationRegistration(1,0,.1,0,1,.2);
        var clipped=shifted.clipSource(square);
        check(clipped.size()==4,"移动美化层仅裁剪源图覆盖范围");
        for(var p:clipped)check(p.x()>=.1-1e-9&&p.z()>=.2-1e-9&&p.x()<=1+1e-9&&p.z()<=1+1e-9,"校准后的UV不越界回绕");
        for(var p:registration.clipSource(square))check(registration.sourceX(p.x(),p.z())>=-1e-9&&registration.sourceX(p.x(),p.z())<=1+1e-9&&registration.sourceZ(p.x(),p.z())>=-1e-9&&registration.sourceZ(p.x(),p.z())<=1+1e-9,"剪切/拉伸后的顶点不采样图像另一侧");
        var tag=new CompoundTag();registration.writeTo(tag);check(PresentationRegistration.from(tag).equals(registration),"配准NBT往返不损失精度");
        for(double[] bad:new double[][]{{Double.NaN,0,0,0,1,0},{1,0,Double.POSITIVE_INFINITY,0,1,0},{-1,0,0,0,1,0},{1,0,.3,0,1,0}}){
            boolean rejected=false;try{new PresentationRegistration(bad[0],bad[1],bad[2],bad[3],bad[4],bad[5]);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"拒绝非有限、翻转或过量位移的配准");
        }
        var manifest=com.google.gson.JsonParser.parseString("{\"registration\":[1,0,0,0,1,0.05]}").getAsJsonObject();
        check(PresentationRegistration.fromManifest(manifest).tz()==.05,"MCP清单配准参数可读取");
    }
    private static void checkUiLayout(){
        for(int[] size:new int[][]{{320,240},{419,240},{420,240},{640,360},{960,540},{1280,720}}){
            var frame=com.biyi.atlas.client.AtlasUiLayout.frame(size[0],size[1]);
            var dock=frame.drawer();var header=frame.header();
            check(dock.x()>=8&&dock.x()+dock.width()<=size[0]-8,"侧栏在屏幕内");
            check(dock.y()>header.y()+header.height()&&dock.y()+dock.height()<=size[1]-20,"侧栏避开导航与底栏");
            check(frame.contentBottom()-frame.contentTop()>=20,"最小窗口可以操作一行按钮");
            var sizes=new java.util.ArrayList<com.biyi.atlas.client.AtlasUiLayout.Size>();
            for(int i=0;i<8;i++)sizes.add(new com.biyi.atlas.client.AtlasUiLayout.Size(42,18));
            var nav=com.biyi.atlas.client.AtlasUiLayout.flow(size[0]-32,sizes);
            for(var r:nav)check(34+r.y()+r.height()<=header.y()+header.height(),"导航换行不超出标题栏");
            sizes.clear();
            for(int i=0;i<100;i++)sizes.add(new com.biyi.atlas.client.AtlasUiLayout.Size(i%3==0?500:60,20+i%4));
            var rows=com.biyi.atlas.client.AtlasUiLayout.flow(dock.width()-20,sizes);
            for(int i=0;i<rows.size();i++){
                var a=rows.get(i);
                check(a.x()>=0&&a.x()+a.width()<=dock.width()-20,"长按钮限制在侧栏内");
                check(a.contains(a.x(),a.y())&&!a.contains(a.x()+a.width(),a.y()),"绘制命中采用半开边界");
                for(int j=0;j<i;j++){
                    var b=rows.get(j);
                    check(a.x()+a.width()<=b.x()||b.x()+b.width()<=a.x()||a.y()+a.height()<=b.y()||b.y()+b.height()<=a.y(),"自动换行按钮互不重叠");
                }
            }
            int fullHeight=com.biyi.atlas.client.AtlasUiLayout.contentHeight(rows);
            int viewport=frame.contentBottom()-frame.contentTop();
            int scroll=Math.max(0,fullHeight-viewport);
            for(var r:rows)check(r.y()+r.height()-scroll<=viewport,"滚动到底可覆盖最后一行");
        }
    }
}
