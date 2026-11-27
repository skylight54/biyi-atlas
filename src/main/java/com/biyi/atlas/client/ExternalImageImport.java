package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部图片导入：作者自己画的（或别的工具导出的）PNG 丢进
 * {@code <游戏目录>/biyi_atlas/authoring/<世界>/import/}，
 * 在游戏里循环选一张，点一下就能挂到当前位置成为地图元素。
 */
public final class ExternalImageImport {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static int index;
    /** 导入图片默认比例：1 像素 = 多少方块。 */
    private static int blocksPerPixel = 4;

    private ExternalImageImport() {
    }

    public static int blocksPerPixel() {
        return blocksPerPixel;
    }

    public static void cycleScale() {
        blocksPerPixel = switch (blocksPerPixel) {
            case 1 -> 2;
            case 2 -> 4;
            case 4 -> 8;
            default -> 1;
        };
    }

    public static List<Path> files() {
        Path dir = LocationCapture.importDir();
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            result.addAll(stream
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .toList());
        } catch (IOException ignored) {
            // 目录读不到就当没有
        }
        return result;
    }

    public static String currentName() {
        List<Path> files = files();
        if (files.isEmpty()) {
            return "（把 PNG 放进 import 目录）";
        }
        index = Math.floorMod(index, files.size());
        return files.get(index).getFileName().toString();
    }

    public static void cycle() {
        List<Path> files = files();
        if (!files.isEmpty()) {
            index = (index + 1) % files.size();
        }
    }

    /** 弹系统文件选择框，让作者直接从电脑里挑一张图片导入。 */
    public static void pickAndPlace() {
        pickFile(ExternalImageImport::importFile);
    }

    /** 选一张图片导入成"自定义标记图标"（路标/地点用）。 */
    public static void pickIconFile() {
        pickFile(file -> report(CustomIcons.importIcon(file)));
    }

    /** 弹系统文件选择框，把选中的文件交给回调处理（例如挂到区域上）。 */
    public static void pickFile(java.util.function.Consumer<Path> onPicked) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            report("当前 JVM 是无界面模式，弹不出系统文件框；请把 PNG 放进 import 目录后用「导入目录图」");
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                java.awt.FileDialog dialog = new java.awt.FileDialog((java.awt.Frame) null,
                        "选择要导入的地图图片", java.awt.FileDialog.LOAD);
                dialog.setFilenameFilter((dir, name) -> {
                    String lower = name.toLowerCase();
                    return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
                });
                dialog.setVisible(true);
                String directory = dialog.getDirectory();
                String file = dialog.getFile();
                if (directory == null || file == null) {
                    report("已取消文件选择");
                    return;
                }
                java.nio.file.Path source = java.nio.file.Paths.get(directory, file);
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> {
                    try {
                        onPicked.accept(source);
                    } catch (Throwable error) {
                        report("导入失败：" + error);
                    }
                });
            } catch (Throwable error) {
                LOGGER.warn("[BiyiAtlas] 打开文件选择框失败: {}", error.toString());
                report("打不开系统文件框（" + error.getClass().getSimpleName() + "）；"
                        + "可把 PNG 放进 import 目录后用「导入目录图」");
            }
        }, "biyi-atlas-file-picker");
        thread.setDaemon(true);
        thread.start();
    }

    /** 把一个提示直接给玩家（界面里调用，例如图片选择器）。 */
    public static void reportForScreen(String text) {
        report(text);
    }

    /** 把结果直接告诉玩家（聊天栏 + 地图内提示）。 */
    private static void report(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("[避役图志] " + text), false);
        }
    }

    /** 把 import 目录里当前选中的图片挂到玩家位置。 */
    public static String placeAtPlayer() {
        List<Path> files = files();
        if (files.isEmpty()) {
            return "import 目录里没有 PNG：" + LocationCapture.importDir();
        }
        index = Math.floorMod(index, files.size());
        return importFile(files.get(index));
    }

    /** 导入指定图片文件。 */
    public static String importFile(Path source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return "没有世界";
        }
        if (source == null || !Files.isRegularFile(source)) {
            return "找不到文件：" + source;
        }
        try {
            String base = source.getFileName().toString().replaceAll("(?i)\\.(png|jpg|jpeg)$", "");
            String name = "导入图 " + base;
            ResourceLocation texture = new ResourceLocation(BiyiAtlas.MODID,
                    "textures/generated/import_" + LocationCapture.safeName(base) + ".png");
            // 复制到地点图目录，保证下次启动时能被自动注册
            Path copy = LocationCapture.locationDir()
                    .resolve("import_" + LocationCapture.safeName(base) + ".png");
            Files.createDirectories(copy.getParent());
            NativeImage sourceImage = NativeImage.read(Files.newInputStream(source));
            sourceImage.writeToFile(copy.toFile());
            int imageWidth = sourceImage.getWidth();
            int imageHeight = sourceImage.getHeight();
            sourceImage.close();
            LocationCapture.register(texture, copy);
            double originX = minecraft.player.getX() - imageWidth * blocksPerPixel / 2.0D;
            double originZ = minecraft.player.getZ() - imageHeight * blocksPerPixel / 2.0D;
            NativeImage copyImage = NativeImage.read(Files.newInputStream(copy));
            String result = LocationCapture.saveAndRegister(copyImage, name, originX, originZ,
                    blocksPerPixel, "外部导入 · 1px=" + blocksPerPixel + "方块");
            LOGGER.info("[BiyiAtlas] imported external image {} -> {}", source, result);
            return result;
        } catch (IOException e) {
            return "导入失败：" + e.getMessage();
        }
    }
}
