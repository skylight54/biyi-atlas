package com.biyi.atlas.map;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Per-world durable edit history. Only author JSON is stored, never terrain or art PNGs. */
public final class EditHistory {
    private final Path content, journal;
    public EditHistory(Path content) {
        this.content=content;
        this.journal=content.resolveSibling(content.getFileName()+".history.json");
    }
    private String current() throws java.io.IOException {
        return Files.exists(content)?Files.readString(content,StandardCharsets.UTF_8):"";
    }
    private void store(JsonObject root) throws java.io.IOException {
        AtomicIo.writeAtomic(journal,root.toString().getBytes(StandardCharsets.UTF_8));
    }
    private JsonObject load() throws java.io.IOException {
        JsonObject root;
        if(Files.exists(journal))root=JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
        else {root=new JsonObject();root.addProperty("schema",1);root.addProperty("cursor",0);root.add("steps",new JsonArray());}
        if(root.get("schema").getAsInt()!=1)throw new IllegalStateException("撤销历史版本不兼容");
        // A write-ahead record distinguishes an interrupted content write from an external edit.
        if(root.has("pending")) {
            var p=root.getAsJsonObject("pending");String actual=current();
            if(actual.equals(p.get("after").getAsString()))root.remove("pending");
            else if(actual.equals(p.get("before").getAsString()))root=p.getAsJsonObject("previous").deepCopy();
            else throw new IllegalStateException("地图被外部修改，历史恢复已停止");
            store(root);
        }
        int cursor=root.get("cursor").getAsInt();
        if(cursor<0||cursor>root.getAsJsonArray("steps").size())throw new IllegalStateException("撤销历史损坏");
        return root;
    }
    private void commit(JsonObject previous,JsonObject next,String before,String after) throws java.io.IOException {
        if(!current().equals(before))throw new IllegalStateException("地图版本已变化，请重新操作");
        JsonObject pending=new JsonObject();pending.addProperty("before",before);pending.addProperty("after",after);
        pending.add("previous",previous.deepCopy());next.add("pending",pending);store(next);
        AtomicIo.writeAtomic(content,after.getBytes(StandardCharsets.UTF_8));
        next.remove("pending");store(next);
    }
    public void write(byte[] payload) throws java.io.IOException {
        String before=current(),after=new String(payload,StandardCharsets.UTF_8);
        if(before.equals(after))return;
        var previous=load();var next=previous.deepCopy();var steps=next.getAsJsonArray("steps");
        int cursor=next.get("cursor").getAsInt();
        while(steps.size()>cursor)steps.remove(steps.size()-1);
        if(!before.isEmpty()) {
            var step=new JsonObject();step.addProperty("before",before);step.addProperty("after",after);
            steps.add(step);
        }
        while(steps.size()>50 || (steps.size()>1&&next.toString().length()>16*1024*1024))steps.remove(0);
        next.addProperty("cursor",steps.size());commit(previous,next,before,after);
    }
    public boolean move(boolean redo) throws java.io.IOException {
        var previous=load();var next=previous.deepCopy();var steps=next.getAsJsonArray("steps");
        int cursor=next.get("cursor").getAsInt(),index=redo?cursor:cursor-1;
        if(index<0||index>=steps.size())return false;
        var step=steps.get(index).getAsJsonObject();
        String before=step.get(redo?"before":"after").getAsString(),after=step.get(redo?"after":"before").getAsString();
        if(!current().equals(before))throw new IllegalStateException("地图已被其他操作修改，拒绝覆盖；请使用备份恢复");
        next.addProperty("cursor",cursor+(redo?1:-1));commit(previous,next,before,after);return true;
    }
}
