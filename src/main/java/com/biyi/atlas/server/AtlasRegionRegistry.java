package com.biyi.atlas.server;

import com.biyi.atlas.BiyiAtlas;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端持有的**区域条目**（A6 第①步只存 id，第②步起需要够算可见性的字段）。
 *
 * <p>数据来源与 {@link AtlasRegionRegistry} 相同：{@code config/biyi_atlas/regions.json}
 * 与单人下的 {@code biyi_atlas/authored/<世界>/content.json}。</p>
 */
public final class AtlasRegionRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 一条区域的服务端视角记录。
     *
     * @param id              区域 id
     * @param parent          父区域 id（"" = 顶层）
     * @param unlockType      {@code license} / {@code advancement} / {@code always}
     * @param unlockId        解锁凭据 id（默认与 id 相同）
     * @param defaultUnlocked 作者标记"默认开放"
     */
    public record Entry(String id, String name, String parent, String unlockType, String unlockId,
                        boolean defaultUnlocked, boolean mapArea, boolean childLocksContent,
                        int lineRgb, int fillRgb, int fillAlpha, double labelX, double labelZ,
                        boolean showOutlineWhenLocked, java.util.List<double[]> outline, String image,
                        String dimension,int layer) {
        public Entry(String id,String name,String parent,String unlockType,String unlockId,
                     boolean defaultUnlocked,boolean mapArea,boolean childLocksContent,
                     int lineRgb,int fillRgb,int fillAlpha,double labelX,double labelZ,
                     boolean showOutlineWhenLocked,java.util.List<double[]> outline,String image){
            this(id,name,parent,unlockType,unlockId,defaultUnlocked,mapArea,childLocksContent,lineRgb,fillRgb,fillAlpha,
                    labelX,labelZ,showOutlineWhenLocked,outline,image,"minecraft:overworld",0);
        }
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static volatile boolean loaded;
    /** 两个数据源的修改时间（用于"内容变了就重读"，否则新画的区域服务端永远不认识）。 */
    private static long configMtime = -1L;
    private static long authoredMtime = -1L;

    private AtlasRegionRegistry() {
    }

    public static boolean isKnown(String regionId) {
        return regionId != null && !regionId.isEmpty() && ENTRIES.containsKey(regionId);
    }

    public static Set<String> ids() {
        return new LinkedHashSet<>(ENTRIES.keySet());
    }

    public static Collection<Entry> entries() {
        return new ArrayList<>(ENTRIES.values());
    }

    public static Entry entry(String id) {
        return ENTRIES.get(id);
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** 开服 / 重载时刷新。 */
    public static void reload(MinecraftServer server) {
        Map<String, Entry> parsed = new LinkedHashMap<>();
        // 本地与config只作回退；存档内权威副本必须最后合并。
        String store = AtlasServerStore.readText(server);
        Path configFile = FMLPaths.CONFIGDIR.get().resolve(BiyiAtlas.MODID).resolve("regions.json");
        readFromConfig(configFile, parsed);
        Path authored = authoredContentFile(server);
        if (authored != null) {
            readFromAuthored(authored, parsed);
        }
        if(store!=null&&!store.isEmpty()){
            try{parsed.clear();mergeDocument(store,parsed);}
            catch(Exception error){LOGGER.warn("[BiyiAtlas] 解析存档内内容失败",error);}
        }
        ENTRIES.clear();
        ENTRIES.putAll(parsed);
        configMtime = mtime(configFile);
        authoredMtime = mtime(authored);
        loaded = true;
        LOGGER.info("[BiyiAtlas] server region registry: {} 个区域条目（config={}, authored={}）",
                ENTRIES.size(), Files.isRegularFile(configFile), authored);
    }

    /**
     * 内容文件变了就重读（**关键**：作者在游戏里新画区域后，服务端必须能认识它，
     * 否则解锁会被拒、下发的可见集合里也没有它 → 新区域内的道路对玩家永远不可见）。
     */
    public static void refreshIfStale(MinecraftServer server) {
        if (server == null || server.isDedicatedServer()) {
            if (!loaded && server != null) {
                reload(server);
            }
            return;
        }
        Path config = configFile();
        Path authored = authoredContentFile(server);
        long cm = mtime(config);
        long am = mtime(authored);
        if (!loaded || cm != configMtime || am != authoredMtime) {
            reload(server);
        }
    }

    private static Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve(BiyiAtlas.MODID).resolve("regions.json");
    }

    private static long mtime(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return -1L;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception error) {
            return -1L;
        }
    }

    private static void readFromConfig(Path file, Map<String, Entry> out) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = GsonHelper.parse(Files.readString(file, StandardCharsets.UTF_8));
            collect(root.get("regions"), out);
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] 读取 {} 失败: {}", file, error.toString());
        }
    }

    private static void readFromAuthored(Path file, Map<String, Entry> out) {
        try {
            JsonObject root = GsonHelper.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (root.has("Regions") && root.get("Regions").isJsonObject()) {
                collect(root.getAsJsonObject("Regions").get("list"), out);
            } else {
                collect(root.get("regions"), out);
            }
            // 墓碑里的 id 也算"曾经存在"，避免删除后旧许可证立刻报未知
            if (root.has("Removed") && root.get("Removed").isJsonObject()) {
                collect(root.getAsJsonObject("Removed").get("regions"), out);
            }
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] 读取 {} 失败: {}", file, error.toString());
        }
    }

    public static void mergeDocument(String json,Map<String,Entry> out){
        JsonObject root=GsonHelper.parse(json);
        collect(root.has("Regions")?root.getAsJsonObject("Regions").get("list"):root.get("regions"),out);
        if(root.has("Removed")){
            var removed=root.getAsJsonObject("Removed").get("regions");
            if(removed!=null&&removed.isJsonArray())for(var id:removed.getAsJsonArray())out.remove(id.getAsString());
        }
    }
    private static void collect(JsonElement element, Map<String, Entry> out) {
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                if (item.isJsonPrimitive()) {
                    String id = item.getAsString();
                    if (!id.isEmpty()) {
                        out.putIfAbsent(id, new Entry(id, id, "", "license", id, false, true, true,
                                0x5A4632, 0x7C8C5A, 40, 0.0D, 0.0D, true, java.util.List.of(), ""));
                    }
                }
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String id = GsonHelper.getAsString(object, "id", "");
            if (id.isEmpty()) {
                continue;
            }
            String unlockType = "license";
            String unlockId = id;
            if (object.has("unlock") && object.get("unlock").isJsonObject()) {
                JsonObject unlock = object.getAsJsonObject("unlock");
                unlockType = GsonHelper.getAsString(unlock, "type", "license");
                unlockId = GsonHelper.getAsString(unlock, "id", id);
            }
            out.put(id, new Entry(id,
                    GsonHelper.getAsString(object, "name", id),
                    GsonHelper.getAsString(object, "parent", ""),
                    unlockType,
                    unlockId,
                    object.has("defaultUnlocked") && object.get("defaultUnlocked").getAsBoolean(),
                    !object.has("mapArea") || object.get("mapArea").getAsBoolean(),
                    !object.has("childLocksContent") || object.get("childLocksContent").getAsBoolean(),
                    parseRgb(GsonHelper.getAsString(object, "lineColor", "5A4632"), 0x5A4632),
                    parseRgb(GsonHelper.getAsString(object, "fillColor", "7C8C5A"), 0x7C8C5A),
                    GsonHelper.getAsInt(object, "fillAlpha", 40),
                    GsonHelper.getAsDouble(object, "labelX", 0.0D),
                    GsonHelper.getAsDouble(object, "labelZ", 0.0D),
                    !object.has("showOutlineWhenLocked") || object.get("showOutlineWhenLocked").getAsBoolean(),
                    readOutline(object),
                    GsonHelper.getAsString(object, "image", ""),
                    GsonHelper.getAsString(object,"dimension","minecraft:overworld"),GsonHelper.getAsInt(object,"layer",0)));
        }
    }

    private static java.util.List<double[]> readOutline(JsonObject object) {
        java.util.List<double[]> outline = new java.util.ArrayList<>();
        JsonElement element = object.get("outline");
        if (element == null || !element.isJsonArray()) {
            return outline;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonObject()) {
                JsonObject point = item.getAsJsonObject();
                outline.add(new double[]{GsonHelper.getAsDouble(point, "x", 0.0D),
                        GsonHelper.getAsDouble(point, "z", 0.0D)});
            } else if (item.isJsonArray() && item.getAsJsonArray().size() >= 2) {
                outline.add(new double[]{item.getAsJsonArray().get(0).getAsDouble(),
                        item.getAsJsonArray().get(1).getAsDouble()});
            }
        }
        return outline;
    }

    private static int parseRgb(String text, int fallback) {
        try {
            return Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16) & 0xFFFFFF;
        } catch (Exception error) {
            return fallback;
        }
    }

    /** 原始内容文本：优先存档内副本，其次单人下的作者文件（供元素过滤用）。 */
    public static String rawContentText(MinecraftServer server) {
        String stored = AtlasServerStore.readText(server);
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        Path authored = authoredContentFile(server);
        if (authored == null || !Files.isRegularFile(authored)) {
            return null;
        }
        try {
            return Files.readString(authored, StandardCharsets.UTF_8);
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] 读取 {} 失败: {}", authored, error.toString());
            return null;
        }
    }

    /** 祖先链（不含自己，最多 8 层，自带环保护）。 */
    public static List<String> ancestors(String id) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Entry entry = ENTRIES.get(id);
        int depth = 0;
        while (entry != null && entry.parent() != null && !entry.parent().isEmpty()
                && depth++ < 8 && visited.add(entry.parent())) {
            chain.add(entry.parent());
            entry = ENTRIES.get(entry.parent());
        }
        return chain;
    }

    private static Path authoredContentFile(MinecraftServer server) {
        if (server == null || server.isDedicatedServer()) {
            return null;   // 专用服务器只认 config（客户端内容不参与服务端权威）
        }
        try {
            String folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
            return FMLPaths.GAMEDIR.get().resolve(BiyiAtlas.MODID).resolve("authored")
                    .resolve("sp_" + folder).resolve("content.json");
        } catch (Exception error) {
            return null;
        }
    }
}
