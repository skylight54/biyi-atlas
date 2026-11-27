package com.biyi.atlas.net;

import com.biyi.atlas.map.*;
import com.biyi.atlas.server.*;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** 有界区域底图传输：0请求，1作者发布，2服务端下发。双方均限制压缩与解压大小。 */
public record AreaTerrainMessage(int mode,String area,int transfer,int index,int total,byte[] payload){
    private static final int CHUNK=16384,MAX_PARTS=384,MAX_RAW=16*1024*1024;
    private record Assembly(int total,long created,Map<Integer,byte[]> parts){}
    // 集成服务端的客户端/服务端线程共享类静态字段，传输池必须支持并发。
    private static final Map<String,Assembly> ASSEMBLIES=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID,String> ACTIVE=new HashMap<>();
    private static final Map<UUID,Long> REQUESTS=new HashMap<>();
    public static void projectSnapshot(ServerPlayer p,String json){var tag=new CompoundTag();tag.putByteArray("project",json.getBytes(java.nio.charset.StandardCharsets.UTF_8));send(9,"project",tag,p);}
    public static void request(String area){AtlasNetwork.CHANNEL.sendToServer(new AreaTerrainMessage(0,area,0,0,1,new byte[0]));}
    public static void publish(String area,CompoundTag tag){send(1,area,tag,null);}
    public static void publishArt(String area,CompoundTag tag){send(6,area,tag,null);}
    public static void selectArt(String area,String id){AtlasNetwork.CHANNEL.sendToServer(new AreaTerrainMessage(8,area,0,0,1,id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    public static void prepare(String area){AtlasNetwork.CHANNEL.sendToServer(new AreaTerrainMessage(4,area,0,0,1,new byte[0]));}
    public static void cancelPrepare(){AtlasNetwork.CHANNEL.sendToServer(new AreaTerrainMessage(5,"",0,0,1,new byte[0]));}
    public static void progress(ServerPlayer player,String area,String text){
        AtlasNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new AreaTerrainMessage(7,area,0,0,1,text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public static void encode(AreaTerrainMessage m,FriendlyByteBuf b){
        b.writeByte(m.mode);b.writeUtf(m.area,256);b.writeInt(m.transfer);b.writeVarInt(m.index);b.writeVarInt(m.total);b.writeByteArray(m.payload);
    }
    public static AreaTerrainMessage decode(FriendlyByteBuf b){return new AreaTerrainMessage(b.readUnsignedByte(),b.readUtf(256),b.readInt(),b.readVarInt(),b.readVarInt(),b.readByteArray(CHUNK));}
    private static void send(int mode,String area,CompoundTag tag,ServerPlayer player){
        try{
            ByteArrayOutputStream out=new ByteArrayOutputStream();NbtIo.writeCompressed(tag,out);
            byte[] bytes=out.toByteArray();int count=(bytes.length+CHUNK-1)/CHUNK;
            if(count>MAX_PARTS)throw new IOException("区域底图过大，请拆分区域");
            int id=java.util.concurrent.ThreadLocalRandom.current().nextInt();
            for(int i=0;i<count;i++){
                var m=new AreaTerrainMessage(mode,area,id,i,count,Arrays.copyOfRange(bytes,i*CHUNK,Math.min(bytes.length,(i+1)*CHUNK)));
                if(player==null)AtlasNetwork.CHANNEL.sendToServer(m);
                else AtlasNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(()->player),m);
            }
        }catch(Exception e){
            if(player!=null)player.displayClientMessage(net.minecraft.network.chat.Component.literal("区域底图传输失败："+e.getMessage()),false);
            else throw new IllegalStateException("区域底图传输失败："+e.getMessage(),e);
        }
    }
    public static void handle(AreaTerrainMessage m,Supplier<NetworkEvent.Context> supplier){
        var ctx=supplier.get();boolean server=ctx.getDirection().getReceptionSide().isServer();
        ctx.enqueueWork(()->{
            ServerPlayer player=ctx.getSender();
            if(server){
                if(player==null||m.mode==2)return;
                if(m.mode==5){RegionPreparation.cancel(player);return;}
                AtlasRegionRegistry.refreshIfStale(player.server);
                var area=AtlasRegionRegistry.entry(m.area);
                if(area==null||!area.mapArea()){
                    if(m.mode==4)progress(player,m.area,"加载失败：请先设为地图范围");
                    if(m.mode==1)player.displayClientMessage(net.minecraft.network.chat.Component.literal("区域定义尚未同步或不是地图范围，请稍后重新发布"),false);
                    return;
                }
                if(m.mode==4){ACTIVE.put(player.getUUID(),m.area);RegionPreparation.start(player,m.area);return;}
                if(m.mode==8){
                    try{PresentationStore.select(player,m.area,new String(m.payload,java.nio.charset.StandardCharsets.UTF_8));
                        for(var online:player.server.getPlayerList().getPlayers())if(m.area.equals(ACTIVE.get(online.getUUID())))download(online,m.area);
                    }catch(Exception error){player.displayClientMessage(net.minecraft.network.chat.Component.literal("切换方案失败："+error.getMessage()),false);}
                    return;
                }
                if(m.mode==0){
                    long now=System.currentTimeMillis();
                    if(now-REQUESTS.getOrDefault(player.getUUID(),0L)<1000)return;
                    REQUESTS.put(player.getUUID(),now);
                    if(!player.isCreative()&&!player.hasPermissions(2)&&!AtlasVisibilityService.visibleFor(player).contains(m.area))return;
                    ACTIVE.put(player.getUUID(),m.area);download(player,m.area);return;
                }
                if(m.mode!=1&&m.mode!=6)return;
                if(!player.isCreative()&&!player.hasPermissions(2)){
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("底图发布被拒绝：需要创造模式或权限等级2"),false);return;
                }
            }else if(m.mode==7){com.biyi.atlas.client.AtlasMapSession.preparation(new String(m.payload,java.nio.charset.StandardCharsets.UTF_8));return;}
            else if(m.mode==3){com.biyi.atlas.client.AtlasMapSession.unpublished(m.area);return;}
            else if(m.mode!=2&&m.mode!=9)return;
            if(!server&&m.mode!=9){var active=com.biyi.atlas.client.AtlasMapSession.active();if(active==null||!active.id().equals(m.area))return;}
            String key=(server?player.getUUID():"client")+"/"+m.area+"/"+m.transfer;
            ASSEMBLIES.entrySet().removeIf(e->System.currentTimeMillis()-e.getValue().created>30000);
            if(m.total<1||m.total>MAX_PARTS||m.index<0||m.index>=m.total||m.payload.length>CHUNK)return;
            if(!ASSEMBLIES.containsKey(key)&&ASSEMBLIES.size()>=16)return;
            var a=ASSEMBLIES.computeIfAbsent(key,k->new Assembly(m.total,System.currentTimeMillis(),new HashMap<>()));
            if(a.total!=m.total){ASSEMBLIES.remove(key);return;}
            a.parts.put(m.index,m.payload);if(a.parts.size()!=m.total)return;
            ASSEMBLIES.remove(key);
            try{
                ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<m.total;i++)out.writeBytes(a.parts.get(i));
                byte[] raw;
                try(var in=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(out.toByteArray()))){raw=in.readNBytes(MAX_RAW+1);}
                if(raw.length>MAX_RAW)return;
                CompoundTag tag=NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw)),new NbtAccounter(32L*1024*1024));
                if(!server&&m.mode==9){
                    if(com.biyi.atlas.client.AtlasAuthoring.allowed())AtlasContent.get().installProjectJson(new String(tag.getByteArray("project"),java.nio.charset.StandardCharsets.UTF_8));return;
                }
                if(server&&m.mode==6){
                    PresentationStore.store(player,m.area,tag);
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("展示成品图已发布："+m.area),false);
                    for(var online:player.server.getPlayerList().getPlayers())if(m.area.equals(ACTIVE.get(online.getUUID())))download(online,m.area);
                    return;
                }
                if(server){
                    var region=AtlasRegionRegistry.entry(m.area);TerrainSurvey data=new TerrainSurvey();data.load(tag);
                    data=data.filtered((x,z)->MapPolygon.contains(region.outline(),x,z));
                    if(data.size()==0)throw new IOException("区域内没有有效采样，未覆盖已有底图");
                    Path path=file(player,m.area);Files.createDirectories(path.getParent());
                    boolean complete=false;
                    if(Files.exists(path)){
                        var prior=NbtIo.readCompressed(path.toFile());
                        complete=prior.getBoolean("complete")&&prior.getString("shape").equals(PresentationSpec.shape(region.dimension(),region.layer(),region.outline()));
                        TerrainSurvey previous=new TerrainSurvey();previous.load(NbtIo.readCompressed(path.toFile()));
                        previous.mergeFrom(data);data=previous.filtered((x,z)->MapPolygon.contains(region.outline(),x,z));
                    }
                    CompoundTag merged=data.save();merged.putBoolean("complete",complete);merged.putString("shape",PresentationSpec.shape(region.dimension(),region.layer(),region.outline()));
                    Path tmp=path.resolveSibling(path.getFileName()+".tmp");NbtIo.writeCompressed(merged,tmp.toFile());
                    try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("区域底图已发布："+m.area+"（"+data.size()+"个采样点）"),false);
                    for(var online:player.server.getPlayerList().getPlayers())if(m.area.equals(ACTIVE.get(online.getUUID())))download(online,m.area);
                }else com.biyi.atlas.client.AtlasMapSession.receive(m.area,tag);
            }catch(Exception e){
                com.mojang.logging.LogUtils.getLogger().error("区域底图处理失败 area={} mode={}",m.area,m.mode,e);
                if(server)player.displayClientMessage(net.minecraft.network.chat.Component.literal("区域底图处理失败："+e.getMessage()),false);
            }
        });ctx.setPacketHandled(true);
    }
    private static Path file(ServerPlayer p,String id)throws Exception{
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return p.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/biyi_atlas_terrain/"+hash+".nbt");
    }
    public static void storePrepared(ServerPlayer p,String id,TerrainSurvey data){
        try{
            var area=AtlasRegionRegistry.entry(id);if(area==null)return;
            var filtered=data.filtered((x,z)->MapPolygon.contains(area.outline(),x,z));
            Path path=file(p,id);Files.createDirectories(path.getParent());
            if(filtered.size()==0)throw new IOException("区域内无有效采样，未替换旧底图");
            CompoundTag prepared=filtered.save();prepared.putBoolean("complete",true);
            prepared.putString("shape",PresentationSpec.shape(area.dimension(),area.layer(),area.outline()));
            Path tmp=path.resolveSibling(path.getFileName()+".tmp");NbtIo.writeCompressed(prepared,tmp.toFile());
            try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("区域准备并发布完成："+id+"，"+filtered.size()+"个样本"),false);
            progress(p,id,"区域加载完成 · "+filtered.size()+" 个采样点，已自动发布");
            for(var online:p.server.getPlayerList().getPlayers())if(online==p||id.equals(ACTIVE.get(online.getUUID())))download(online,id);
        }catch(Exception e){p.displayClientMessage(net.minecraft.network.chat.Component.literal("区域保存失败："+e.getMessage()),false);}
    }
    public static void previewPrepared(ServerPlayer p,String id,TerrainSurvey data){
        var area=AtlasRegionRegistry.entry(id);if(area==null)return;
        var tag=data.filtered((x,z)->MapPolygon.contains(area.outline(),x,z)).save();tag.putBoolean("complete",false);send(2,id,tag,p);
    }
    private static void download(ServerPlayer p,String id){
        try{
            if(!p.isCreative()&&!p.hasPermissions(2)&&!AtlasVisibilityService.visibleFor(p).contains(id))return;
            Path file=file(p,id);if(!Files.exists(file)){
                AtlasNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new AreaTerrainMessage(3,id,0,0,1,new byte[0]));return;
            }
            CompoundTag stored=NbtIo.readCompressed(file.toFile());
            TerrainSurvey data=new TerrainSurvey();data.load(stored);
            Set<String> visible=AtlasVisibilityService.visibleFor(p);
            var region=AtlasRegionRegistry.entry(id);
            double minX=region.outline().stream().mapToDouble(v->v[0]).min().orElse(0),maxX=region.outline().stream().mapToDouble(v->v[0]).max().orElse(0);
            double minZ=region.outline().stream().mapToDouble(v->v[1]).min().orElse(0),maxZ=region.outline().stream().mapToDouble(v->v[1]).max().orElse(0);
            boolean author=p.isCreative()||p.hasPermissions(2);
            var hidden=AtlasRegionRegistry.entries().stream().filter(r->r.dimension().equals(region.dimension())&&r.layer()==region.layer()
                    &&!author&&!visible.contains(r.id())&&(!r.parent().isEmpty()?r.childLocksContent():true)
                    &&MapPolygon.intersectsRect(r.outline(),minX,minZ,maxX,maxZ)).toList();
            var filtered=data.filtered((x,z)->MapPolygon.contains(region.outline(),x,z)
                    &&hidden.stream().noneMatch(r->MapPolygon.contains(r.outline(),x,z)));
            CompoundTag delivery=filtered.save();delivery.putBoolean("complete",stored.getBoolean("complete")&&stored.getString("shape").equals(PresentationSpec.shape(region.dimension(),region.layer(),region.outline())));
            try{com.biyi.atlas.server.PresentationStore.attach(p,id,delivery,hidden);}
            catch(Exception optionalFailure){com.mojang.logging.LogUtils.getLogger().warn("可选展示图读取失败，继续发送保底地形 area={}",id,optionalFailure);}
            send(2,id,delivery,p);
        }catch(Exception error){
            com.mojang.logging.LogUtils.getLogger().error("读取区域底图失败 area={}",id,error);
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("读取区域底图失败："+error.getMessage()),false);
        }
    }
    public static void forget(UUID id){ACTIVE.remove(id);REQUESTS.remove(id);ASSEMBLIES.keySet().removeIf(k->k.startsWith(id.toString()+"/"));}
    public static void clearClient(){ASSEMBLIES.keySet().removeIf(k->k.startsWith("client/"));}
}
