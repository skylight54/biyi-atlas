package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.map.AtlasMarker;
import com.biyi.atlas.map.AtlasPalette;
import com.biyi.atlas.map.AtlasRevealedRegion;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 作者可自定义的地图风格：
 * <ul>
 *     <li>资源包：{@code assets/biyi_atlas/atlas/style.json}（模组内置一份默认值）</li>
 *     <li>实例配置：{@code config/biyi_atlas/style.json}（优先级更高，改完 /reload 或重进世界生效）</li>
 * </ul>
 */
public class AtlasStyle extends SimplePreparableReloadListener<Void> {

    public static final AtlasStyle INSTANCE = new AtlasStyle();
    public static final ResourceLocation BUILTIN_LOCATION =
            new ResourceLocation(BiyiAtlas.MODID, "atlas/style.json");

    private static final Logger LOGGER = LogUtils.getLogger();

    private String title = "避役图志 · 山川舆图";
    private double zoomDefault = 2.0D;
    private double zoomMin = 0.6D;
    private double zoomMax = 10.0D;
    /** 是否把"玩家探索过的地形色块"当地图底图（默认关闭：这是手绘羊皮纸地图）。 */
    private boolean showTerrain;
    private final List<AtlasMarker> markers = new ArrayList<>();
    private final List<AtlasRevealedRegion> revealed = new ArrayList<>();

    private AtlasStyle() {
    }

    public String title() {
        return title;
    }

    public double zoomDefault() {
        return zoomDefault;
    }

    public double zoomMin() {
        return zoomMin;
    }

    public double zoomMax() {
        return zoomMax;
    }

    public List<AtlasMarker> markers() {
        return Collections.unmodifiableList(markers);
    }

    public List<AtlasRevealedRegion> revealed() {
        return Collections.unmodifiableList(revealed);
    }

    /** 默认 false：地图是作者手绘的羊皮纸，不画探索色块。 */
    public boolean showTerrain() {
        return showTerrain;
    }

    /** 某个世界坐标是否落在作者声明的"已知区域"里；命中就返回该区域的颜色，否则 -1。 */
    public int revealedColorAt(ResourceLocation dimension, double x, double z) {
        for (AtlasRevealedRegion region : revealed) {
            if (region.contains(dimension, x, z)) {
                return 0xFF000000 | region.rgb();
            }
        }
        return -1;
    }

    @Override
    protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return null;
    }

    @Override
    protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
        JsonObject root = readOverride();
        if (root == null) {
            root = readBuiltin(resourceManager);
        }
        if (root == null) {
            LOGGER.warn("[BiyiAtlas] no style.json found, keeping defaults");
            return;
        }
        try {
            parse(root);
            LOGGER.info("[BiyiAtlas] style loaded: title='{}', markers={}", title, markers.size());
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] failed to parse style.json: {}", e.toString());
        }
    }

    private JsonObject readOverride() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(BiyiAtlas.MODID).resolve("style.json");
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject object = GsonHelper.parse(reader);
            LOGGER.info("[BiyiAtlas] using config override {}", path);
            return object;
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] cannot read {}: {}", path, e.toString());
            return null;
        }
    }

    private JsonObject readBuiltin(ResourceManager resourceManager) {
        Optional<Resource> resource = resourceManager.getResource(BUILTIN_LOCATION);
        if (resource.isEmpty()) {
            return null;
        }
        try (Reader reader = resource.get().openAsReader()) {
            return GsonHelper.parse(reader);
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] cannot read builtin style: {}", e.toString());
            return null;
        }
    }

    private void parse(JsonObject root) {
        title = GsonHelper.getAsString(root, "title", title);
        zoomDefault = clamp(GsonHelper.getAsDouble(root, "zoomDefault", zoomDefault));
        zoomMin = Math.max(0.1D, GsonHelper.getAsDouble(root, "zoomMin", zoomMin));
        zoomMax = Math.max(0.1D, GsonHelper.getAsDouble(root, "zoomMax", zoomMax));
        if (zoomMax < zoomMin) {
            double t = zoomMax;
            zoomMax = zoomMin;
            zoomMin = t;
        }
        zoomDefault = Math.max(zoomMin, Math.min(zoomMax, zoomDefault));

        showTerrain = !root.has("showTerrain") || root.get("showTerrain").getAsBoolean();

        if (root.has("colors")) {
            applyColors(root.get("colors"));
        }
        markers.clear();
        if (root.has("markers")) {
            parseMarkers(GsonHelper.getAsJsonArray(root, "markers"));
        }
        revealed.clear();
        if (root.has("prerevealed")) {
            parseRevealed(GsonHelper.getAsJsonArray(root, "prerevealed"));
        }
    }

    /** 作者声明的"已知区域"：{"dimension":"minecraft:overworld","x1":-512,"z1":-512,"x2":512,"z2":512,"color":"9A8F73"} */
    private void parseRevealed(JsonArray array) {
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String dimension = GsonHelper.getAsString(object, "dimension", "minecraft:overworld");
            ResourceLocation location = ResourceLocation.tryParse(dimension);
            if (location == null) {
                LOGGER.warn("[BiyiAtlas] prerevealed region has invalid dimension '{}'", dimension);
                continue;
            }
            try {
                double x1 = GsonHelper.getAsDouble(object, "x1", 0.0D);
                double z1 = GsonHelper.getAsDouble(object, "z1", 0.0D);
                double x2 = GsonHelper.getAsDouble(object, "x2", 0.0D);
                double z2 = GsonHelper.getAsDouble(object, "z2", 0.0D);
                int rgb = parseRgb(GsonHelper.getAsString(object, "color", "9A8F73"));
                revealed.add(new AtlasRevealedRegion(location, x1, z1, x2, z2, rgb));
            } catch (RuntimeException e) {
                LOGGER.warn("[BiyiAtlas] bad prerevealed region: {}", e.toString());
            }
        }
    }

    private static double clamp(double value) {
        return Math.max(0.1D, Math.min(64.0D, value));
    }

    private void applyColors(JsonElement element) {
        int[] palette = AtlasPalette.defaults();
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                try {
                    int id = Integer.parseInt(entry.getKey());
                    int rgb = parseRgb(entry.getValue().getAsString());
                    if (id >= 0 && id < palette.length) {
                        palette[id] = rgb;
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("[BiyiAtlas] bad palette entry '{}'", entry.getKey());
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size() && i < palette.length; i++) {
                try {
                    palette[i] = parseRgb(array.get(i).getAsString());
                } catch (RuntimeException ignored) {
                    // 保留默认值
                }
            }
        }
        AtlasPalette.setColors(palette);
    }

    private static int parseRgb(String text) {
        String hex = text.startsWith("#") ? text.substring(1) : text;
        if (hex.length() == 8) {
            hex = hex.substring(2); // 忽略 alpha
        }
        return Integer.parseInt(hex, 16) & 0xFFFFFF;
    }

    private void parseMarkers(JsonArray array) {
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String icon = GsonHelper.getAsString(object, "icon", AtlasMarker.DEFAULT_ICON);
            String dimension = GsonHelper.getAsString(object, "dimension", "minecraft:overworld");
            double x = GsonHelper.getAsDouble(object, "x", 0.0D);
            double z = GsonHelper.getAsDouble(object, "z", 0.0D);
            String label = GsonHelper.getAsString(object, "label", "");
            ResourceLocation location = ResourceLocation.tryParse(dimension);
            if (location == null) {
                LOGGER.warn("[BiyiAtlas] marker '{}' has invalid dimension '{}'", label, dimension);
                continue;
            }
            markers.add(new AtlasMarker(icon, location, x, z, label));
        }
    }
}
