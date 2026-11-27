package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端全局设置（作者地图左上角设置面板可改，落盘跨会话保留）。
 *
 * <p>文件：{@code <gameDir>/biyi_atlas/settings.json}</p>
 */
public final class AtlasSettings {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 是否绘制战争迷雾贴图。 */
    private static boolean fogEnabled = true;
    /** 未解锁区域是否封锁其中的道路。 */
    private static boolean lockRoads = true;
    /** 未解锁区域是否封锁其中的路标。 */
    private static boolean lockWaypoints = true;
    /** 未解锁区域是否封锁其中的地点。 */
    private static boolean lockPlaces = true;
    /** 未解锁区域是否封锁其中的地点图（大型俯视图）。 */
    private static boolean lockImages = true;
    /** 道路节点标记是否显示给玩家（**默认否**：节点是作者的编辑辅助，玩家不需要看）。 */
    private static boolean showNodesToPlayers = false;

    enum Corner { 右上, 右下, 左下, 左上 }
    private static Corner corner = Corner.右上;
    private static double zoom = 1;
    private static boolean enabled = true, northUp = true, markers = true;
    static Corner corner() { ensureLoaded(); return corner; }
    static boolean enabled() { ensureLoaded(); return enabled; }
    static boolean northUp() { ensureLoaded(); return northUp; }
    static boolean markers() { ensureLoaded(); return markers; }
    static double zoom() { ensureLoaded(); return zoom; }
    static void cycleCorner() { corner = Corner.values()[(corner().ordinal() + 1) % 4]; save(); }
    static void toggleEnabled() { enabled = !enabled(); save(); }
    static void toggleNorth() { northUp = !northUp(); save(); }
    static void toggleMarkers() { markers = !markers(); save(); }
    static void adjustZoom(double amount) { zoom = clampZoom(zoom() + amount); save(); }
    static double clampZoom(double value) {
        return Double.isFinite(value) && value > 0 ? Math.max(.5, Math.min(3, value)) : 1;
    }
    static com.biyi.atlas.map.MapViewport hudCamera(int extent, double x, double z) {
        return new com.biyi.atlas.map.MapViewport(extent, extent, x, z, heldZoom());
    }

    public static double heldZoom() { return zoom() * com.biyi.atlas.map.HeldMapView.DEFAULT; }
    private static boolean loaded;

    private AtlasSettings() {
    }

    public static boolean fogEnabled() {
        ensureLoaded();
        return fogEnabled;
    }

    public static boolean lockRoads() {
        ensureLoaded();
        return lockRoads;
    }

    public static boolean lockWaypoints() {
        ensureLoaded();
        return lockWaypoints;
    }

    public static boolean lockPlaces() {
        ensureLoaded();
        return lockPlaces;
    }

    public static boolean lockImages() {
        ensureLoaded();
        return lockImages;
    }

    public static void toggleFog() {
        ensureLoaded();
        fogEnabled = !fogEnabled;
        save();
    }

    public static void toggleLockRoads() {
        ensureLoaded();
        lockRoads = !lockRoads;
        save();
    }

    public static void toggleLockWaypoints() {
        ensureLoaded();
        lockWaypoints = !lockWaypoints;
        save();
    }

    public static void toggleLockPlaces() {
        ensureLoaded();
        lockPlaces = !lockPlaces;
        save();
    }

    public static boolean showNodesToPlayers() {
        return showNodesToPlayers;
    }

    public static void toggleShowNodesToPlayers() {
        showNodesToPlayers = !showNodesToPlayers;
        save();
    }

    public static void toggleLockImages() {
        ensureLoaded();
        lockImages = !lockImages;
        save();
    }

    public static String summary() {
        ensureLoaded();
        return "迷雾 " + on(fogEnabled) + " · 锁路 " + on(lockRoads) + " · 锁路标 " + on(lockWaypoints)
                + " · 锁地点 " + on(lockPlaces) + " · 锁地图图 " + on(lockImages);
    }

    private static String on(boolean value) {
        return value ? "开" : "关";
    }

    public static void load() {
        load(file());
    }

    static void load(Path file) {
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = GsonHelper.parse(Files.readString(file, StandardCharsets.UTF_8));
            double legacy = GsonHelper.getAsDouble(root, "heldZoom", com.biyi.atlas.map.HeldMapView.DEFAULT);
            zoom = clampZoom(GsonHelper.getAsDouble(root, "hudZoom", legacy / com.biyi.atlas.map.HeldMapView.DEFAULT));
            enabled = GsonHelper.getAsBoolean(root, "hudEnabled", true);
            northUp = GsonHelper.getAsBoolean(root, "hudNorthUp", true);
            markers = GsonHelper.getAsBoolean(root, "hudMarkers", true);
            try { corner = Corner.valueOf(GsonHelper.getAsString(root, "hudCorner", "右上")); }
            catch (IllegalArgumentException ignored) { corner = Corner.右上; }
            fogEnabled = GsonHelper.getAsBoolean(root, "fogEnabled", fogEnabled);
            lockRoads = GsonHelper.getAsBoolean(root, "lockRoads", lockRoads);
            lockWaypoints = GsonHelper.getAsBoolean(root, "lockWaypoints", lockWaypoints);
            lockPlaces = GsonHelper.getAsBoolean(root, "lockPlaces", lockPlaces);
            lockImages = GsonHelper.getAsBoolean(root, "lockImages", lockImages);
            LOGGER.info("[BiyiAtlas] settings loaded: {}", summary());
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] cannot read settings: {}", error.toString());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static void save() {
        save(file());
    }

    static void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("hudZoom", zoom);
            root.addProperty("hudEnabled", enabled);
            root.addProperty("hudNorthUp", northUp);
            root.addProperty("hudMarkers", markers);
            root.addProperty("hudCorner", corner.name());
            root.addProperty("fogEnabled", fogEnabled);
            root.addProperty("lockRoads", lockRoads);
            root.addProperty("lockWaypoints", lockWaypoints);
            root.addProperty("lockPlaces", lockPlaces);
            root.addProperty("lockImages", lockImages);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] cannot save settings: {}", error.toString());
        }
    }

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve(BiyiAtlas.MODID).resolve("settings.json");
    }
}
