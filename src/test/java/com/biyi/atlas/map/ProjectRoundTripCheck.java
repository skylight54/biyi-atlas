package com.biyi.atlas.map;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;

/** Real exported bundle -> renamed world -> exported bundle, without touching the running game. */
public final class ProjectRoundTripCheck {
    public static void main(String[] args)throws Exception{
        Path source=Path.of(args[0]);var before=ProjectBundle.inspect(source);
        Path parent=Path.of("build/qa/v16").toAbsolutePath();Files.createDirectories(parent);
        Path root=Files.createTempDirectory(parent,"roundtrip-");
        Path game=root.resolve("receiver"),world=game.resolve("saves/different-world-name");
        ProjectBundle.install(source,world,game);
        Path again=root.resolve("reexport.biyi.zip");ProjectBundle.export(world,game,again);
        var after=ProjectBundle.inspect(again);
        if(!before.keySet().equals(after.keySet()))throw new AssertionError("Bundle inventory changed after world rename");
        int checked=0;
        for(var e:before.entrySet())if(!e.getKey().equals("manifest.json")){
            if(!Arrays.equals(e.getValue(),after.get(e.getKey())))throw new AssertionError("Bundle bytes changed: "+e.getKey());checked++;
        }
        Path document=world.resolve("data/biyi_atlas_content.json");
        var json=JsonParser.parseString(Files.readString(document)).getAsJsonObject();
        var regions=json.getAsJsonObject("Regions").getAsJsonArray("list");
        if(regions.size()==0)throw new AssertionError("Expected real regions for deletion replay");
        String deleted=regions.remove(0).getAsJsonObject().get("id").getAsString();
        var removed=json.has("Removed")?json.getAsJsonObject("Removed"):new JsonObject();
        var ids=removed.has("regions")?removed.getAsJsonArray("regions"):new JsonArray();ids.add(deleted);removed.add("regions",ids);json.add("Removed",removed);
        AtomicIo.writeAtomic(document,json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var registry=new LinkedHashMap<String,com.biyi.atlas.server.AtlasRegionRegistry.Entry>();
        com.biyi.atlas.server.AtlasRegionRegistry.mergeDocument(new String(before.get("content.json"),java.nio.charset.StandardCharsets.UTF_8),registry);
        com.biyi.atlas.server.AtlasRegionRegistry.mergeDocument(Files.readString(document),registry);
        if(registry.containsKey(deleted))throw new AssertionError("Deleted borrowed region returned on registry replay");
        Files.writeString(root.resolve("report.txt"),"PASS: "+checked+" payload files byte-identical after renamed-world install/reexport.\nPASS: disk deletion/tombstone registry replay for "+deleted+".\nOriginal game untouched.\n");
        System.out.println("ProjectRoundTripCheck PASS: "+checked+" payload files, renamed world, persistent deletion replay; evidence="+root);
    }
}
