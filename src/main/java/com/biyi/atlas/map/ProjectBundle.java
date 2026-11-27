package com.biyi.atlas.map;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;

/** Versioned, checksummed atlas-only archive. World blocks and player unlock progress are excluded. */
public final class ProjectBundle {
    private static final long MAX=256L*1024*1024;
    private ProjectBundle(){}
    public static void main(String[] args)throws Exception{System.out.println(export(Path.of(args[0]),Path.of(args[1]),Path.of(args[2])));}

    public static Path export(Path world,Path game,Path output)throws Exception{
        String key="sp_"+world.toAbsolutePath().normalize().getFileName();
        Map<String,byte[]> files=new TreeMap<>();
        Path content=world.resolve("data/biyi_atlas_content.json");
        if(!Files.isRegularFile(content))content=game.resolve("biyi_atlas/authored/"+key+"/content.json");
        var json=JsonParser.parseString(Files.readString(content)).getAsJsonObject();json.addProperty("ProjectSnapshot",true);
        files.put("content.json",json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        collect(files,"terrain/",world.resolve("data/biyi_atlas_terrain"));
        collect(files,"authoring/",game.resolve("biyi_atlas/authoring/"+key));
        collect(files,"local/",game.resolve("biyi_atlas/data/"+key));
        var manifest=new JsonObject();manifest.addProperty("format",1);manifest.addProperty("scope","atlas-only; excludes world blocks, player unlocks and external mods/resource packs");
        var warnings=new JsonArray();
        if(json.has("Regions"))for(var element:json.getAsJsonObject("Regions").getAsJsonArray("list")){
            var area=element.getAsJsonObject();if(!area.has("mapArea")||!area.get("mapArea").getAsBoolean())continue;
            String id=area.get("id").getAsString();String file="terrain/"+hash(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".nbt";
            if(!files.containsKey(file))warnings.add("区域未包含已发布底图："+id);
            else try(var in=new ByteArrayInputStream(files.get(file))){
                if(!net.minecraft.nbt.NbtIo.readCompressed(in).getBoolean("complete"))warnings.add("底图尚未生成完整："+id);
            }
        }
        manifest.add("warnings",warnings);
        var hashes=new JsonObject();long size=0;
        for(var e:files.entrySet()){size+=e.getValue().length;if(size>MAX)throw new IOException("地图工程超过256MiB，请拆分");hashes.addProperty(e.getKey(),hash(e.getValue()));}
        manifest.add("sha256",hashes);files.put("manifest.json",manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.createDirectories(output.toAbsolutePath().getParent());Path tmp=output.resolveSibling(output.getFileName()+".tmp");
        try(var zip=new ZipOutputStream(Files.newOutputStream(tmp))){for(var e:files.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}
        Files.move(tmp,output,StandardCopyOption.REPLACE_EXISTING);return output;
    }
    private static void collect(Map<String,byte[]> files,String prefix,Path root)throws Exception{
        if(!Files.isDirectory(root))return;
        long size=files.values().stream().mapToLong(b->b.length).sum();
        try(var walk=Files.walk(root)){for(var f:walk.filter(Files::isRegularFile).toList()){
            if(Files.isSymbolicLink(f)||Files.size(f)>MAX)throw new IOException("资源不支持或过大："+f);
            size+=Files.size(f);if(size>MAX)throw new IOException("工程超过256MiB");
            if(files.size()>20000)throw new IOException("工程文件过多");
            String name=root.relativize(f).toString().replace('\\','/');
            if(name.endsWith(".tmp"))continue;files.put(prefix+name,Files.readAllBytes(f));
        }}
    }
    public static Map<String,byte[]> inspect(Path archive)throws Exception{
        Map<String,byte[]> files=new TreeMap<>();long size=0;
        try(var zip=new ZipInputStream(Files.newInputStream(archive))){ZipEntry e;while((e=zip.getNextEntry())!=null){
            String n=e.getName();
            if(e.isDirectory()||n.contains("\\")||n.startsWith("/")||n.contains("//")||n.endsWith("/")||Arrays.asList(n.split("/")).contains(".")||Arrays.asList(n.split("/")).contains("..")||n.contains(":"))throw new IOException("无效工程路径");
            if(!n.equals("manifest.json")&&!n.equals("content.json")&&!n.startsWith("terrain/")&&!n.startsWith("local/")&&!n.startsWith("authoring/"))throw new IOException("未知工程文件："+n);
            byte[] b=zip.readNBytes((int)Math.min(MAX-size+1,MAX+1));size+=b.length;
            if(size>MAX||files.size()>20000||files.putIfAbsent(n,b)!=null)throw new IOException("工程大小/重复文件无效");
        }}
        if(!files.containsKey("content.json")||!files.containsKey("manifest.json"))throw new IOException("缺少工程内容/清单");
        var m=JsonParser.parseString(new String(files.get("manifest.json"),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if(m.get("format").getAsInt()!=1)throw new IOException("不支持的工程版本");
        var hashes=m.getAsJsonObject("sha256");
        if(hashes.size()!=files.size()-1)throw new IOException("工程清单不完整");
        for(var e:files.entrySet())if(!e.getKey().equals("manifest.json")&&(!hashes.has(e.getKey())||!hash(e.getValue()).equals(hashes.get(e.getKey()).getAsString())))throw new IOException("工程校验失败："+e.getKey());
        var doc=JsonParser.parseString(new String(files.get("content.json"),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if(!doc.has("Regions")||!doc.has("Roads")||!doc.has("Places"))throw new IOException("缺少区域/道路/地点定义");
        return files;
    }
    public static Path install(Path archive,Path world,Path game)throws Exception{
        var files=inspect(archive);String key="sp_"+world.toAbsolutePath().normalize().getFileName();
        Path backup=game.resolve("biyi_atlas/project-backups/"+UUID.randomUUID());Files.createDirectories(backup);
        Map<Path,byte[]> writes=new LinkedHashMap<>();
        writes.put(world.resolve("data/biyi_atlas_content.json"),files.get("content.json"));
        writes.put(game.resolve("biyi_atlas/authored/"+key+"/content.json"),files.get("content.json"));
        for(var e:files.entrySet()){
            String n=e.getKey();Path target=null;
            if(n.startsWith("terrain/"))target=world.resolve("data/biyi_atlas_terrain").resolve(n.substring(8));
            if(n.startsWith("authoring/"))target=game.resolve("biyi_atlas/authoring/"+key).resolve(n.substring(10));
            if(n.startsWith("local/"))target=game.resolve("biyi_atlas/data/"+key).resolve(n.substring(6));
            if(target!=null)writes.put(target,e.getValue());
        }
        Map<Path,Path> saved=new LinkedHashMap<>(),roots=new LinkedHashMap<>();List<Path> done=new ArrayList<>();
        try{
            for(Path root:List.of(world.resolve("data/biyi_atlas_terrain"),game.resolve("biyi_atlas/data/"+key),game.resolve("biyi_atlas/authoring/"+key))){
                if(Files.isSymbolicLink(root))throw new IOException("目标目录不允许符号链接");
                if(Files.exists(root)){Path old=backup.resolve("directory-"+roots.size());Files.move(root,old);roots.put(root,old);}
            }
            for(var e:writes.entrySet()){
            Path target=e.getKey();if(Files.exists(target)){Path copy=backup.resolve("file-"+saved.size());Files.copy(target,copy);saved.put(target,copy);}
            AtomicIo.writeAtomic(target,e.getValue());done.add(target);
        }}catch(Exception failure){
            for(Path target:done){if(saved.containsKey(target))Files.copy(saved.get(target),target,StandardCopyOption.REPLACE_EXISTING);else Files.deleteIfExists(target);}
            for(var e:roots.entrySet()){
                if(Files.exists(e.getKey()))try(var walk=Files.walk(e.getKey())){for(Path f:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(f);}
                Files.move(e.getValue(),e.getKey());
            }
            throw failure;
        }
        var index=new JsonObject();saved.forEach((target,copy)->index.addProperty(target.toAbsolutePath().toString(),copy.getFileName().toString()));
        roots.forEach((target,copy)->index.addProperty(target.toAbsolutePath().toString(),copy.getFileName().toString()));
        Files.writeString(backup.resolve("restore-index.json"),index.toString());return backup;
    }
    private static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));}
}
