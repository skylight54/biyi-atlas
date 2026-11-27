package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从**旅行地图（JourneyMap）**导入俯视图。
 *
 * <p>已核实的磁盘布局（JourneyMap 5.10.3 / 1.20.1 Forge）：</p>
 * <pre>
 * journeymap/data/sp/&lt;存档文件夹名&gt;/&lt;维度&gt;/&lt;图层&gt;/&lt;区域X&gt;,&lt;区域Z&gt;.png
 * </pre>
 * <ul>
 *   <li>每张 PNG = 512×512 像素，覆盖 512×512 方块（1 像素 = 1 方块）</li>
 *   <li>图层目录：{@code day}（白天地表）/ {@code night} / {@code topo}（地形）/ {@code biome}（生物群系）</li>
 *   <li><b>数字目录 = 不同 Y 轴切面</b>（例如 {@code 0} {@code 2} {@code 9}），这就是"分层俯视图"的来源</li>
 * </ul>
 */
public final class JourneyMapImport {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int REGION = 512;
    private static final String[] PREFERRED = {"day", "topo", "biome", "night"};

    private static String layer = "day";
    private static boolean paperTone = true;

    private JourneyMapImport() {
    }

    public static String layer() {
        return layer;
    }

    public static boolean paperTone() {
        return paperTone;
    }

    public static void togglePaperTone() {
        paperTone = !paperTone;
    }

    /** 该维度可用的图层（优先 day/topo/biome/night，然后是数字 Y 切面，按 Y 排序）。 */
    /** 当前维度 + 当前图层的目录（作为底片渲染时用）。 */
    public static Path currentLayerDir() {
        Path dir = dimensionDir();
        if (dir == null) {
            return null;
        }
        String folder = layer.startsWith("Y=") ? layer.substring(2) : layer;
        Path layerDir = dir.resolve(folder);
        return Files.isDirectory(layerDir) ? layerDir : null;
    }

    /** 区域边长（方块 / 像素）：旅行地图每张切片 512×512。 */
    public static int regionSize() {
        return REGION;
    }

    public static List<String> layers() {
        List<String> result = new ArrayList<>();
        Path dir = dimensionDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return result;
        }
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path)) {
                    names.add(path.getFileName().toString());
                }
            }
        } catch (IOException ignored) {
            return result;
        }
        for (String preferred : PREFERRED) {
            if (names.contains(preferred)) {
                result.add(preferred);
            }
        }
        names.stream()
                .filter(name -> name.matches("-?\\d+"))
                .sorted(Comparator.comparingInt(Integer::parseInt))
                .forEach(name -> result.add("Y=" + name));
        return result;
    }

    public static void cycleLayer() {
        List<String> all = layers();
        if (all.isEmpty()) {
            return;
        }
        int index = all.indexOf(layer);
        layer = all.get((index + 1) % all.size());
    }

    public static void setLayer(String value) {
        if (value != null && !value.isEmpty()) {
            layer = value;
        }
    }

    /** 把旅行地图的对应切片拼成一张图，再登记为地图元素。 */
    public static String importArea(String name, double minX, double minZ, double maxX, double maxZ) {
        Path dir = dimensionDir();
        if (dir == null) {
            return "旅行地图没有这个维度的数据";
        }
        String folder = layer.startsWith("Y=") ? layer.substring(2) : layer;
        Path layerDir = dir.resolve(folder);
        if (!Files.isDirectory(layerDir)) {
            return "旅行地图没有图层 " + layer;
        }
        int width = (int) Math.round(maxX - minX);
        int height = (int) Math.round(maxZ - minZ);
        if (width < 8 || height < 8 || width > 2048 || height > 2048) {
            return "范围不合适（" + width + "×" + height + " 方块，8~2048）";
        }
        NativeImage output = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        int[][] colors = new int[width][height];
        int missing = 0;
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int worldX = (int) Math.floor(minX) + px;
                int worldZ = (int) Math.floor(minZ) + py;
                int regionX = Math.floorDiv(worldX, REGION);
                int regionZ = Math.floorDiv(worldZ, REGION);
                int rgb = sampleRegion(layerDir, regionX, regionZ,
                        Math.floorMod(worldX, REGION), Math.floorMod(worldZ, REGION));
                if (rgb < 0) {
                    missing++;
                    rgb = 0xD6C49E; // 没有数据的地方用纸色
                }
                colors[px][py] = rgb;
            }
        }
        // 平滑 + 手绘化（第二遍，这样 3×3 平均能拿到完整邻居）
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int smooth = LocationCapture.average3x3(colors, px, py, width, height);
                int value = paperTone ? LocationCapture.paperTone(smooth) : (0xFF000000 | smooth);
                output.setPixelRGBA(px, py, LocationCapture.abgr(value));
            }
        }
        if (LocationCapture.outline()) {
            LocationCapture.inkEdges(output, colors, width, height);
        }
        String result = LocationCapture.saveAndRegister(output, name, minX, minZ, 1,
                "旅行地图导入 · " + layer + " · " + width + "×" + height + " 方块");
        LOGGER.info("[BiyiAtlas] journey map import layer={} region-missing-px={} -> {}", layer, missing, result);
        return result + (missing > 0 ? "（" + missing + " 像素无数据，已填纸色）" : "");
    }

    /** 读取一个区域 PNG 的某个像素；返回 RGB，读取失败返回 -1。 */
    private static int sampleRegion(Path layerDir, int regionX, int regionZ, int localX, int localZ) {
        Path file = layerDir.resolve(regionX + "," + regionZ + ".png");
        if (!Files.isRegularFile(file)) {
            return -1;
        }
        try {
            NativeImage image = NativeImage.read(Files.newInputStream(file));
            try {
                if (localX >= image.getWidth() || localZ >= image.getHeight()) {
                    return -1;
                }
                int abgr = image.getPixelRGBA(localX, localZ);
                int alpha = (abgr >>> 24) & 0xFF;
                if (alpha < 8) {
                    return -1; // 未探索（透明）→ 当没有数据
                }
                int b = (abgr >> 16) & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int r = abgr & 0xFF;
                return (r << 16) | (g << 8) | b;
            } finally {
                image.close();
            }
        } catch (IOException e) {
            return -1;
        }
    }

    private static Path dimensionDir() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        Path base = baseDir();
        if (base == null) {
            return null;
        }
        Path current = base.resolve(minecraft.level.dimension().location().getPath());
        if (Files.isDirectory(current)) {
            return current;
        }
        // 当前维度没有数据时回退 overworld（很多存档只逛过地表）
        Path overworld = base.resolve("overworld");
        return Files.isDirectory(overworld) ? overworld : null;
    }

    /** 旅行地图里这个存档的根目录（用于诊断提示）。 */
    public static Path baseDir() {
        Minecraft minecraft = Minecraft.getInstance();
        String key = AtlasStorage.currentWorldKey();
        String world = key.startsWith("sp_") ? key.substring(3)
                : key.startsWith("mp_") ? key.substring(3) : key;
        File gameDir = minecraft.gameDirectory;
        return gameDir.toPath().resolve("journeymap").resolve("data")
                .resolve(key.startsWith("mp_") ? "mp" : "sp").resolve(world);
    }

    /** 给玩家看的诊断信息：到底在找哪个目录。 */
    public static String diagnostic() {
        Path base = baseDir();
        return base == null ? "找不到游戏目录" : base.toString();
    }
}
