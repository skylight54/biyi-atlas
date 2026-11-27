package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 自定义标记图标：作者可以导入自己的 PNG 作为路标/地点的图标，和预设素材一样出现在调色板里。
 *
 * <p>落盘位置：{@code <游戏目录>/biyi_atlas/authoring/<世界>/icons/<名字>.png}</p>
 */
public final class CustomIcons {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> NAMES = new ArrayList<>();
    private static boolean loaded;

    private CustomIcons() {
    }

    public static Path iconsDir() {
        return LocationCapture.locationDir().resolveSibling("icons");
    }

    public static List<String> names() {
        ensureLoaded();
        return NAMES;
    }

    public static boolean isCustom(String name) {
        ensureLoaded();
        return NAMES.contains(name);
    }

    /** 自定义图标的贴图位置（与预设图标同一套渲染路径）。 */
    public static ResourceLocation texture(String name) {
        return new ResourceLocation(BiyiAtlas.MODID,
                "textures/generated/icons/" + LocationCapture.safeName(name) + ".png");
    }

    /** 启动/进世界时注册磁盘上已有的自定义图标。 */
    public static void loadAll() {
        NAMES.clear();
        Path dir = iconsDir();
        if (!Files.isDirectory(dir)) {
            loaded = true;
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted().toList()) {
                String file = path.getFileName().toString();
                String name = file.substring(0, file.length() - 4);
                LocationCapture.register(texture(name), path);
                NAMES.add(name);
            }
        } catch (IOException error) {
            LOGGER.warn("[BiyiAtlas] 读取自定义图标目录失败: {}", error.toString());
        }
        loaded = true;
        LOGGER.info("[BiyiAtlas] custom icons loaded: {}", NAMES);
    }

    private static void ensureLoaded() {
        if (!loaded) {
            loadAll();
        }
    }

    /** 把一张图片导入成自定义图标；返回给玩家看的提示。 */
    public static String importIcon(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return "找不到文件：" + source;
        }
        String base = LocationCapture.sanitize(
                source.getFileName().toString().replaceAll("(?i)\\.(png|jpg|jpeg)$", ""));
        Path target = iconsDir().resolve(LocationCapture.safeName(base) + ".png");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            // 统一转成 32x32（图标渲染按 32x32 取样，其他尺寸也能用，只是会缩放）
            LocationCapture.register(texture(base), target);
        } catch (IOException error) {
            return "导入图标失败：" + error.getMessage();
        }
        if (!NAMES.contains(base)) {
            NAMES.add(base);
        }
        return "已导入标记图标「" + base + "」，现在可以在素材格里选它";
    }

    /** 删除最后导入的图标（作者点错时的快捷撤销）。 */
    public static String removeLast() {
        ensureLoaded();
        if (NAMES.isEmpty()) {
            return "还没有导入过自定义图标";
        }
        String name = NAMES.remove(NAMES.size() - 1);
        try {
            Files.deleteIfExists(iconsDir().resolve(LocationCapture.safeName(name) + ".png"));
        } catch (IOException ignored) {
            // 删不掉也不影响使用
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getTextureManager().release(texture(name));
        return "已移除自定义图标「" + name + "」";
    }

    /** 供外部（图片选择器）确认文件是不是图片。 */
    public static boolean isImage(Path path) {
        if (path == null) {
            return false;
        }
        String lower = path.getFileName().toString().toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    /** 占位：把图片读成 NativeImage（供将来做尺寸校验）。 */
    public static int[] sizeOf(Path path) {
        try {
            NativeImage image = NativeImage.read(Files.newInputStream(path));
            try {
                return new int[]{image.getWidth(), image.getHeight()};
            } finally {
                image.close();
            }
        } catch (IOException error) {
            return new int[]{0, 0};
        }
    }
}
