package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.map.AtlasContent;
import com.biyi.atlas.map.AtlasMapImage;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * "地点俯视图"生成器：把一个矩形范围从上往下采样成**手绘羊皮纸风格**的图片，
 * 保存为地图元素（可被玩家追踪、被作者编辑）。
 *
 * <p>三条来源共用同一套落盘/注册逻辑：① 游戏内采样 ② 旅行地图切片导入 ③ 外部图片导入。</p>
 */
public final class LocationCapture {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 每个图像素 = 多少世界格（1 = 最清晰，2 = 更省内存）。 */
    public static final int BLOCKS_PER_PIXEL = 2;
    private static final int MAX_PIXELS = 512;

    // ---- 俯视图质量选项（作者在面板上切换） ----
    private static boolean roofView = true;
    private static boolean heightShade = true;
    private static boolean outline = true;

    private LocationCapture() {
    }

    public static boolean roofView() {
        return roofView;
    }

    public static void toggleRoofView() {
        roofView = !roofView;
    }

    public static boolean heightShade() {
        return heightShade;
    }

    public static void toggleHeightShade() {
        heightShade = !heightShade;
    }

    public static boolean outline() {
        return outline;
    }

    public static void toggleOutline() {
        outline = !outline;
    }

    /** 以玩家为中心抓取（半径，方块）。 */
    public static String capture(int radiusBlocks, String name) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return "没有玩家";
        }
        double cx = minecraft.player.getX();
        double cz = minecraft.player.getZ();
        return captureArea(name, cx - radiusBlocks, cz - radiusBlocks, cx + radiusBlocks, cz + radiusBlocks);
    }

    /** 抓取任意矩形范围（世界坐标，左上角 → 右下角）。 */
    public static String captureArea(String name, double minX, double minZ, double maxX, double maxZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return "没有世界";
        }
        Level level = minecraft.level;
        int pixels = (int) Math.round((maxX - minX) / BLOCKS_PER_PIXEL);
        int pixelsZ = (int) Math.round((maxZ - minZ) / BLOCKS_PER_PIXEL);
        if (pixels < 8 || pixelsZ < 8 || pixels > MAX_PIXELS || pixelsZ > MAX_PIXELS) {
            return "范围不合适（" + pixels + "×" + pixelsZ + " 像素，上限 " + MAX_PIXELS + "）";
        }
        Heightmap.Types type = roofView ? Heightmap.Types.WORLD_SURFACE
                : Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, pixels, pixelsZ, false);
        int[][] colors = new int[pixels][pixelsZ];
        int[][] heights = new int[pixels][pixelsZ];
        // 第一遍：只采样
        for (int py = 0; py < pixelsZ; py++) {
            for (int px = 0; px < pixels; px++) {
                int worldX = (int) Math.floor(minX + px * BLOCKS_PER_PIXEL);
                int worldZ = (int) Math.floor(minZ + py * BLOCKS_PER_PIXEL);
                BlockPos surfacePos = level.getHeightmapPos(type,
                        new BlockPos(worldX, level.getMinBuildHeight(), worldZ));
                // 注意：getHeightmapPos 返回的是"地表上方那一格"，直接取方块会拿到空气（MapColor=0 → 全黑）
                BlockPos samplePos = surfacePos.below();
                BlockState state = level.getBlockState(samplePos);
                MapColor mapColor = state.getMapColor(level, samplePos);
                colors[px][py] = mapColor == null || mapColor.col == 0 ? 0x8B8B84 : (mapColor.col & 0xFFFFFF);
                heights[px][py] = samplePos.getY();
            }
        }
        // 第二遍：平滑 + 手绘化
        for (int py = 0; py < pixelsZ; py++) {
            for (int px = 0; px < pixels; px++) {
                int smooth = average3x3(colors, px, py, pixels, pixelsZ);
                int shaded = heightShade ? shadeByHeight(smooth, heights[px][py]) : smooth;
                image.setPixelRGBA(px, py, abgr(paperTone(shaded)));
            }
        }
        if (outline) {
            inkEdges(image, colors, pixels, pixelsZ);
        }
        return saveAndRegister(image, name, minX, minZ,
                "俯视图 · " + (int) (maxX - minX) + "×" + (int) (maxZ - minZ) + " 方块");
    }

    /** 落盘 + 注册贴图 + 登记为地图元素。行程结束前不会关闭图像。 */
    public static String saveAndRegister(NativeImage image, String name, double originX, double originZ,
                                         String description) {
        return saveAndRegister(image, name, originX, originZ, BLOCKS_PER_PIXEL, description);
    }

    /** 同上，可指定"每像素多少方块"。 */
    public static String saveAndRegister(NativeImage image, String name, double originX, double originZ,
                                         int blocksPerPixel, String description) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            image.close();
            return "没有世界";
        }
        String file = sanitize(name);
        Path target = locationDir().resolve(file + ".png");
        ResourceLocation texture = new ResourceLocation(BiyiAtlas.MODID,
                "textures/generated/" + safeName(name) + ".png");
        try {
            Files.createDirectories(target.getParent());
            image.writeToFile(target.toFile());
            register(texture, target);
        } catch (IOException e) {
            LOGGER.warn("[BiyiAtlas] 保存地点图失败: {}", e.toString());
            image.close();
            return "保存失败：" + e.getMessage();
        }
        AtlasMapImage element = new AtlasMapImage(name, minecraft.level.dimension().location(), 0, "",
                texture, originX, originZ, image.getWidth(), image.getHeight(), blocksPerPixel, 255,
                name, description);
        AtlasContent.get().addMapImage(element);
        AtlasContent.get().saveAuthored();
        return "已生成「" + name + "」（" + image.getWidth() + "×" + image.getHeight() + " 像素）";
    }

    /** 重开游戏后把磁盘上的地点图重新注册成贴图（否则地图上是紫黑格）。 */
    public static void loadAll() {
        Path dir = locationDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().endsWith(".png")).toList()) {
                String fileName = path.getFileName().toString();
                String bare = fileName.endsWith(".png")
                        ? fileName.substring(0, fileName.length() - 4) : fileName;
                register(new ResourceLocation(BiyiAtlas.MODID,
                        "textures/generated/" + safeName(bare) + ".png"), path);
            }
        } catch (IOException e) {
            LOGGER.warn("[BiyiAtlas] 读取地点图目录失败: {}", e.toString());
        }
    }

    public static Path locationDir() {
        File gameDir = Minecraft.getInstance().gameDirectory;
        return gameDir.toPath().resolve(BiyiAtlas.MODID).resolve("authoring")
                .resolve(AtlasStorage.currentWorldKey()).resolve("locations");
    }

    /** 外部图片导入目录：作者把 PNG 丢这里。 */
    public static Path importDir() {
        File gameDir = Minecraft.getInstance().gameDirectory;
        return gameDir.toPath().resolve(BiyiAtlas.MODID).resolve("authoring")
                .resolve(AtlasStorage.currentWorldKey()).resolve("import");
    }

    /** 动态注册贴图的尺寸（ResourceManager 查不到动态贴图，必须自己记）。 */
    private static final java.util.Map<ResourceLocation, int[]> TEXTURE_SIZES = new java.util.HashMap<>();

    public static void register(ResourceLocation texture, Path path) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            NativeImage image = NativeImage.read(Files.newInputStream(path));
            DynamicTexture dynamic = new DynamicTexture(image);
            dynamic.setFilter(false, false);
            minecraft.getTextureManager().register(texture, dynamic);
            TEXTURE_SIZES.put(texture, new int[]{Math.max(1, image.getWidth()), Math.max(1, image.getHeight())});
        } catch (IOException e) {
            LOGGER.warn("[BiyiAtlas] 注册贴图失败 {}: {}", texture, e.toString());
        }
    }

    /** 动态贴图尺寸；没注册过返回 null。 */
    public static int[] sizeOf(ResourceLocation texture) {
        return TEXTURE_SIZES.get(texture);
    }

    // ---------------------------------------------------------------- 手绘化处理

    /** 墨线勾边：3×3 平均 + 量化比较，避免把贴图噪声当成边界。 */
    public static void inkEdges(NativeImage image, int[][] colors, int width, int height) {
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                boolean edge = (px + 1 < width && boundary(colors, px, py, px + 1, py, width, height))
                        || (py + 1 < height && boundary(colors, px, py, px, py + 1, width, height));
                if (edge) {
                    image.setPixelRGBA(px, py, abgr(0xFF3A2A1B));
                }
            }
        }
    }

    private static boolean boundary(int[][] colors, int x1, int y1, int x2, int y2, int width, int height) {
        int a = average3x3(colors, x1, y1, width, height);
        int b = average3x3(colors, x2, y2, width, height);
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) >> 4;
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) >> 4;
        int db = Math.abs((a & 0xFF) - (b & 0xFF)) >> 4;
        return dr + dg + db >= 7;
    }

    /** 3×3 平均色：抹掉旅行地图切片里的逐像素噪声，让画面更像手绘。 */
    public static int average3x3(int[][] colors, int x, int y, int width, int height) {
        int r = 0;
        int g = 0;
        int b = 0;
        int n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int px = x + dx;
                int py = y + dy;
                if (px < 0 || py < 0 || px >= width || py >= height) {
                    continue;
                }
                int c = colors[px][py];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return 0x8B8B84;
        }
        return ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    /** 旧纸色调：把真实色往羊皮纸色混，降低饱和度。 */
    public static int paperTone(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int gray = (r * 30 + g * 59 + b * 11) / 100;
        r = (int) (gray * 0.40D + r * 0.22D + 232 * 0.38D);
        g = (int) (gray * 0.40D + g * 0.22D + 213 * 0.38D);
        b = (int) (gray * 0.40D + b * 0.22D + 169 * 0.38D);
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    /** 高度着色：越高越亮（手绘地图的起伏感）。 */
    public static int shadeByHeight(int rgb, int height) {
        double factor = Math.max(0.84D, Math.min(1.16D, 1.0D + (height - 64) * 0.0045D));
        int r = (int) (((rgb >> 16) & 0xFF) * factor);
        int g = (int) (((rgb >> 8) & 0xFF) * factor);
        int b = (int) ((rgb & 0xFF) * factor);
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    public static boolean differ(int a, int b) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return dr + dg + db > 96;
    }

    public static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** NativeImage.setPixelRGBA 用的是 ABGR 顺序。 */
    public static int abgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * **资源路径安全名**：Minecraft 的 ResourceLocation 只允许 {@code [a-z0-9/._-]}，
     * 中文/空格/大写都会让 {@code new ResourceLocation(...)} 直接抛异常（贴图根本注册不上）。
     * 这里把非法字符换成 {@code _}，并附上原名的短哈希保证唯一（不同中文名不会撞车）。
     */
    public static String safeName(String name) {
        String cleaned = sanitize(name);
        StringBuilder builder = new StringBuilder();
        for (char c : cleaned.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.') {
                builder.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                builder.append(Character.toLowerCase(c));
            } else {
                builder.append('_');
            }
        }
        String base = builder.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            base = "image";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        return base + "_" + Integer.toHexString(cleaned.hashCode());
    }

    public static String sanitize(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|,]", "_").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "location";
        }
        return cleaned;
    }
}
