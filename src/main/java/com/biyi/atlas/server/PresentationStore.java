package com.biyi.atlas.server;

import com.biyi.atlas.map.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.*;
import java.util.*;

/** 可选展示图只接受作者上传，按区域形状和底图指纹失效。 */
public final class PresentationStore {
    private PresentationStore(){}
    private static Path file(ServerPlayer p,String id)throws Exception{
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return p.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/biyi_atlas_terrain/"+hash+".art.nbt");
    }
    public static void store(ServerPlayer p,String id,CompoundTag art)throws Exception{
        if(!p.isCreative()&&!p.hasPermissions(2))throw new IllegalArgumentException("需要作者权限");
        var area=AtlasRegionRegistry.entry(id);if(area==null||!area.mapArea())throw new IllegalArgumentException("地图范围不存在");
        byte[] png=art.getByteArray("png");PresentationSpec.validatePng(png);
        var registration=PresentationRegistration.from(art);registration.writeTo(art);
        String shape=PresentationSpec.shape(area.dimension(),area.layer(),area.outline());
        if(!shape.equals(art.getString("shape")))throw new IllegalArgumentException("区域边界已改变，请重新导出底图");
        Path target=file(p,id),base=target.resolveSibling(target.getFileName().toString().replace(".art.nbt",".nbt"));
        if(!Files.exists(base))throw new IllegalArgumentException("请先一键加载区域");
        var tag=NbtIo.readCompressed(base.toFile());var survey=new TerrainSurvey();survey.load(tag);
        if(!tag.getBoolean("complete")||!survey.fingerprint().equals(art.getString("base")))throw new IllegalArgumentException("完整底图尚未就绪或版本已改变");
        var decoded=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        if(decoded==null)throw new IllegalArgumentException("PNG无法解码");
        var image=new java.awt.image.BufferedImage(decoded.getWidth(),decoded.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();graphics.drawImage(decoded,0,0,null);graphics.dispose();
        double minX=area.outline().stream().mapToDouble(v->v[0]).min().orElseThrow(),maxX=area.outline().stream().mapToDouble(v->v[0]).max().orElseThrow();
        double minZ=area.outline().stream().mapToDouble(v->v[1]).min().orElseThrow(),maxZ=area.outline().stream().mapToDouble(v->v[1]).max().orElseThrow();
        // 强制裁剪到真实已知采样，AI不能填入区域外或未知地形。
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            double u=(x+.5)/image.getWidth(),v=(y+.5)/image.getHeight();
            double wx=minX+registration.mapX(u,v)*(maxX-minX),wz=minZ+registration.mapZ(u,v)*(maxZ-minZ);
            if(!MapPolygon.contains(area.outline(),wx,wz)||!survey.has((int)Math.floor(wx/4),(int)Math.floor(wz/4)))image.setRGB(x,y,0);
        }
        var normalized=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",normalized);
        byte[] masked=normalized.toByteArray();PresentationSpec.validatePng(masked);art.putByteArray("png",masked);
        var library=new PresentationLibrary(target.resolveSibling(target.getFileName()+".gallery"));library.migrate(target);library.add(art);
    }
    public static void select(ServerPlayer p,String area,String id)throws Exception{
        if(!p.isCreative()&&!p.hasPermissions(2))throw new IllegalArgumentException("需要作者权限");
        Path path=file(p,area);var library=new PresentationLibrary(path.resolveSibling(path.getFileName()+".gallery"));library.migrate(path);library.select(id);
    }
    public static void attach(ServerPlayer p,String id,CompoundTag delivery,List<AtlasRegionRegistry.Entry> hidden)throws Exception{
        // 有未解锁内容时不下发整张美术图，避免客户端拿到隐藏地形。
        var area=AtlasRegionRegistry.entry(id);if(area==null||!hidden.isEmpty())return;
        Path path=file(p,id);var library=new PresentationLibrary(path.resolveSibling(path.getFileName()+".gallery"));library.migrate(path);
        var data=new TerrainSurvey();data.load(delivery);
        String shape=PresentationSpec.shape(area.dimension(),area.layer(),area.outline()),base=data.fingerprint();
        if(p.isCreative()||p.hasPermissions(2))delivery.put("gallery",library.catalog(shape,base));
        var art=library.selected();if(art==null)return;
        if(art.getString("shape").equals(PresentationSpec.shape(area.dimension(),area.layer(),area.outline()))
                &&art.getString("base").equals(data.fingerprint()))delivery.put("presentation",art);
    }
}
