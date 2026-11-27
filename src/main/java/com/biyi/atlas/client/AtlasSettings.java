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

    private static double heldZoom=com.biyi.atlas.map.HeldMapView.DEFAULT;
    public static double heldZoom(){ensureLoaded();return heldZoom;}
    public static void setHeldZoom(double value){ensureLoaded();heldZoom=com.biyi.atlas.map.HeldMapView.clamp(value);}
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
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = GsonHelper.parse(Files.readString(file, StandardCharsets.UTF_8));
            heldZoom=com.biyi.atlas.map.HeldMapView.clamp(GsonHelper.getAsDouble(root,"heldZoom",com.biyi.atlas.map.HeldMapView.DEFAULT));
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
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("heldZoom",heldZoom);
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
