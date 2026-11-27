package com.biyi.atlas.server;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.map.AtomicIo;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务端权威内容的**存档内副本**（A6 第②步）。
 *
 * <p>位置：{@code <存档>/data/biyi_atlas_content.json}。作者的客户端上传后写在这里，
 * 服务端据此计算几何/可见性——**不再依赖某个客户端的本地文件**，专用服务器也能用。
 * 写入走 {@link AtomicIo}（先写 .tmp 再原子替换）。</p>
 */
public final class AtlasServerStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "biyi_atlas_content.json";

    private AtlasServerStore() {
    }

    public static Path fileOf(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        try {
            Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
            Files.createDirectories(dataDir);
            return dataDir.resolve(FILE_NAME);
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] 无法定位存档 data 目录: {}", error.toString());
            return null;
        }
    }

    /** 读存档内的内容（不存在返回 null）。 */
    public static byte[] read(MinecraftServer server) {
        Path file = fileOf(server);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] 读取服务端内容失败: {}", error.toString());
            return null;
        }
    }

    public static String readText(MinecraftServer server) {
        byte[] data = read(server);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    /** 原子写入（作者上传后调用）。 */
    public static boolean write(MinecraftServer server, byte[] data) {
        Path file = fileOf(server);
        if (file == null || data == null || data.length == 0) {
            return false;
        }
        try {
            AtomicIo.writeAtomic(file, data);
            LOGGER.info("[BiyiAtlas] 服务端内容已保存：{} 字节 -> {}", data.length, file);
            return true;
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] 写入服务端内容失败: {}", error.toString());
            return false;
        }
    }
}
