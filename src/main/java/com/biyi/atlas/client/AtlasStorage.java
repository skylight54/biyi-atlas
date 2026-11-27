package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.map.AtlasMapData;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 探索数据的落盘与内存缓存（客户端）。 */
public final class AtlasStorage {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, AtlasMapData> DATA = new HashMap<>();
    private static final int AUTOSAVE_INTERVAL_TICKS = 20 * 60;

    private static String worldKey;
    private static boolean migrated;
    private static boolean dirty;
    private static int autosaveTimer = AUTOSAVE_INTERVAL_TICKS;

    private AtlasStorage() {
    }

    public static AtlasMapData get(ResourceLocation dimension) {
        return DATA.computeIfAbsent(dimension, AtlasStorage::loadFromDisk);
    }

    /** 已经载入（有数据或路标）的维度，供界面上的维度切换使用。 */
    public static java.util.Set<ResourceLocation> loadedDimensions() {
        return new java.util.LinkedHashSet<>(DATA.keySet());
    }

    public static void markDirty() {
        dirty = true;
    }

    /** 每 tick 调用；到点自动存盘。 */
    public static void tickAutosave() {
        if (!dirty) {
            return;
        }
        if (--autosaveTimer <= 0) {
            autosaveTimer = AUTOSAVE_INTERVAL_TICKS;
            flush();
        }
    }

    /** 把当前所有维度数据写到磁盘。 */
    public static void flush() {
        if (DATA.isEmpty()) {
            dirty = false;
            return;
        }
        Path dir = dataDir();
        try {
            Files.createDirectories(dir);
            for (Map.Entry<ResourceLocation, AtlasMapData> entry : DATA.entrySet()) {
                Path file = dir.resolve(fileName(entry.getKey()));
                CompoundTag tag = entry.getValue().save();
                NbtIo.writeCompressed(tag, file.toFile());
            }
            dirty = false;
            LOGGER.debug("[BiyiAtlas] saved {} dimension(s) to {}", DATA.size(), dir);
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] failed to save map data: {}", e.toString());
        }
    }

    /** 退出世界时：存盘并清空缓存。 */
    public static void flushAndReset() {
        flush();
        DATA.clear();
        worldKey = null;
        dirty = false;
        autosaveTimer = AUTOSAVE_INTERVAL_TICKS;
    }

    public static void reset() {
        DATA.clear();
        worldKey = null;
        dirty = false;
    }

    private static AtlasMapData loadFromDisk(ResourceLocation dimension) {
        Path file = dataDir().resolve(fileName(dimension));
        if (!Files.isRegularFile(file)) {
            return new AtlasMapData();
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            return AtlasMapData.load(tag);
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] failed to read {}: {}", file, e.toString());
            return new AtlasMapData();
        }
    }

    private static String fileName(ResourceLocation dimension) {
        return dimension.getNamespace() + "_" + dimension.getPath().replace('/', '_') + ".nbt";
    }

    private static Path dataDir() {
        File gameDir = Minecraft.getInstance().gameDirectory;
        return gameDir.toPath().resolve(BiyiAtlas.MODID).resolve("data").resolve(currentWorldKey());
    }

    /** 当前世界的稳定标识：单人存档名 / 多人服务器地址。 */
    public static String currentWorldKey() {
        if (worldKey != null) {
            return worldKey;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            worldKey = "sp_" + sanitize(singleplayerKey(mc.getSingleplayerServer()));
        } else {
            ServerData server = mc.getCurrentServer();
            if (server != null && server.ip != null && !server.ip.isEmpty()) {
                worldKey = "mp_" + server.ip.replaceAll("[^A-Za-z0-9._-]", "_");
            } else {
                worldKey = "unknown";
            }
        }
        LOGGER.info("[BiyiAtlas] world key = {}", worldKey);
        migrateLegacy(worldKey);
        return worldKey;
    }

    /**
     * 一次性迁移：历史上世界 key 退化成 {@code sp_.}/{@code sp_}（所有存档共用一份），
     * 把这些旧数据搬到当前世界的目录，避免"换了世界地图就空了"。
     */
    private static void migrateLegacy(String newKey) {
        if (migrated || newKey.equals("unknown")) {
            return;
        }
        migrated = true;
        Path base = Minecraft.getInstance().gameDirectory.toPath().resolve(BiyiAtlas.MODID);
        for (String legacy : new String[]{"sp_.", "sp_", "unknown"}) {
            if (legacy.equals(newKey)) {
                continue;
            }
            copyTreeIfMissing(base.resolve("authored").resolve(legacy), base.resolve("authored").resolve(newKey));
            copyTreeIfMissing(base.resolve("data").resolve(legacy), base.resolve("data").resolve(newKey));
            Path from = base.resolve("unlocked").resolve(legacy + ".json.nbt");
            Path to = base.resolve("unlocked").resolve(newKey + ".json.nbt");
            if (Files.isRegularFile(from) && !Files.exists(to)) {
                try {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to);
                } catch (IOException ignored) {
                    // 迁移失败不影响使用
                }
            }
        }
    }

    private static void copyTreeIfMissing(Path from, Path to) {
        if (!Files.isDirectory(from) || Files.exists(to)) {
            return;
        }
        try (var stream = Files.walk(from)) {
            for (Path path : stream.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
            LOGGER.info("[BiyiAtlas] migrated legacy map data {} -> {}", from, to);
        } catch (IOException ignored) {
            // 迁移失败不影响使用
        }
    }

    /** 单人存档的稳定标识：存档文件夹名（不能用 LevelResource.ROOT，它的 getFileName() 是 "."）。 */
    private static String singleplayerKey(MinecraftServer server) {
        // level.dat 的父目录名 = 存档文件夹名，最可靠
        try {
            Path levelData = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
            Path folder = levelData == null ? null : levelData.getParent();
            if (folder != null && folder.getFileName() != null) {
                String name = folder.getFileName().toString();
                if (!name.isBlank() && !".".equals(name)) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // 继续尝试其它来源
        }
        try {
            Path root = server.getWorldPath(LevelResource.ROOT);
            Path folder = root == null ? null : root.getParent();
            if (folder != null && folder.getFileName() != null) {
                String name = folder.getFileName().toString();
                if (!name.isBlank() && !".".equals(name)) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // 继续尝试其它来源
        }
        try {
            String name = server.getWorldData().getLevelName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Exception ignored) {
            // 落到种子
        }
        return "unnamed";
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        // "." / ".." 之类不能作为目录名（历史 bug：世界 key 曾退化成 "sp_."，导致所有存档共用一份地图）
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "unnamed";
        }
        return cleaned;
    }
}
