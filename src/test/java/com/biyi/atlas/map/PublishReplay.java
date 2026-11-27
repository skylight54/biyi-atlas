package com.biyi.atlas.map;

import java.nio.file.*;
import com.biyi.atlas.server.AtlasRegionRegistry;
import net.minecraft.nbt.NbtIo;

/** 只读重放实际发布数据，验证旧来源覆盖导致0采样与新优先级的结果。 */
public final class PublishReplay {
    public static void main(String[] args)throws Exception{
        String stored=Files.readString(Path.of(args[0])),old=Files.readString(Path.of(args[1]));
        TerrainSurvey terrain=AtlasMapData.load(NbtIo.readCompressed(Path.of(args[2]).toFile())).terrain();
        var broken=new java.util.LinkedHashMap<String,AtlasRegionRegistry.Entry>();
        AtlasRegionRegistry.mergeDocument(stored,broken);AtlasRegionRegistry.mergeDocument(old,broken);
        var fixed=new java.util.LinkedHashMap<String,AtlasRegionRegistry.Entry>();
        AtlasRegionRegistry.mergeDocument(old,fixed);AtlasRegionRegistry.mergeDocument(stored,fixed);
        var before=terrain.filtered((x,z)->MapPolygon.contains(broken.get("region_1").outline(),x,z));
        var after=terrain.filtered((x,z)->MapPolygon.contains(fixed.get("region_1").outline(),x,z));
        if(before.size()!=0||after.size()==0)throw new AssertionError("实际数据未复现预期结果");
        System.out.println("REAL publish replay: source="+terrain.size()+", old priority="+before.size()+", fixed priority="+after.size());
        Path target=Path.of("build/qa/repaired-region_1.nbt");Files.createDirectories(target.getParent());
        NbtIo.writeCompressed(after.save(),target.toFile());
    }
}
