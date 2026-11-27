package com.biyi.atlas.map;

import net.minecraft.nbt.*;
import java.nio.file.*;

/** 每个大区域的候选库：原图全部保留，只切换索引，不覆盖其他候选。 */
public final class PresentationLibrary {
    private final Path dir;
    public PresentationLibrary(Path dir){this.dir=dir;}
    private CompoundTag index()throws Exception{return Files.exists(dir.resolve("index.nbt"))?NbtIo.readCompressed(dir.resolve("index.nbt").toFile()):new CompoundTag();}
    private void write(Path path,CompoundTag tag)throws Exception{
        Files.createDirectories(dir);Path tmp=path.resolveSibling(path.getFileName()+".tmp");NbtIo.writeCompressed(tag,tmp.toFile());Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);
    }
    public void migrate(Path legacy)throws Exception{if(!Files.exists(dir.resolve("index.nbt"))&&Files.exists(legacy)){var old=NbtIo.readCompressed(legacy.toFile());old.putString("name","原展示方案");add(old);}}
    public String add(CompoundTag art)throws Exception{
        var registration=PresentationRegistration.from(art);
        var hash=java.security.MessageDigest.getInstance("SHA-256");hash.update(art.getByteArray("png"));
        hash.update(art.getString("shape").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        hash.update(art.getString("base").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var matrix=java.nio.ByteBuffer.allocate(48);for(double v:new double[]{registration.a(),registration.b(),registration.tx(),registration.c(),registration.d(),registration.tz()})matrix.putDouble(v);
        hash.update(matrix.array());
        String id=java.util.HexFormat.of().formatHex(hash.digest()).substring(0,24);
        var index=index();var list=index.getList("variants",10);boolean exists=false;
        for(int i=0;i<list.size();i++)if(list.getCompound(i).getString("id").equals(id))exists=true;
        if(!exists){
            if(list.size()>=32)throw new IllegalArgumentException("候选库最多32张，已有图均已保留");
            var row=new CompoundTag();row.putString("id",id);row.putString("name",art.getString("name").isBlank()?"方案 "+(list.size()+1):art.getString("name"));
            row.putString("shape",art.getString("shape"));row.putString("base",art.getString("base"));list.add(row);
            write(dir.resolve(id+".nbt"),art);
        }
        index.put("variants",list);index.putString("selected",id);write(dir.resolve("index.nbt"),index);return id;
    }
    public void select(String id)throws Exception{
        var index=index();boolean found=id.equals("base");var list=index.getList("variants",10);
        for(int i=0;i<list.size();i++)if(list.getCompound(i).getString("id").equals(id))found=true;
        if(!found)throw new IllegalArgumentException("展示方案不存在");index.putString("selected",id);write(dir.resolve("index.nbt"),index);
    }
    public CompoundTag catalog(String shape,String base)throws Exception{
        var index=index();var result=new CompoundTag();var rows=new ListTag();var list=index.getList("variants",10);
        for(int i=0;i<list.size();i++){var row=list.getCompound(i).copy();row.putBoolean("compatible",shape.equals(row.getString("shape"))&&base.equals(row.getString("base")));rows.add(row);}
        result.put("variants",rows);result.putString("selected",index.getString("selected"));return result;
    }
    public CompoundTag selected()throws Exception{String id=index().getString("selected");return id.matches("[0-9a-f]{24}")&&Files.exists(dir.resolve(id+".nbt"))?NbtIo.readCompressed(dir.resolve(id+".nbt").toFile()):null;}
}
