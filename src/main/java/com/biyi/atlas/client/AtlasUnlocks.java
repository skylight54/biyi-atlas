package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.map.AtlasContent;
import com.biyi.atlas.map.AtlasRegion;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 已解锁的区域（许可证）。按世界存在客户端：{@code <gameDir>/biyi_atlas/unlocked/<世界>.json}，
 * 服务端通过命令或许可证物品"授权"（见 PLAN-区域与图层.md）。
 */
public final class AtlasUnlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<String> UNLOCKED = new HashSet<>();
    private static String worldKey = "unknown";
    private static boolean loaded;

    private AtlasUnlocks() {
    }

    public static void onWorldLoaded(String key) {
        if (key != null && !key.equals(worldKey)) {
            worldKey = key;
            UNLOCKED.clear();
            loaded = false;
        }
        load();
    }

    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            for (String id : tag.getList("unlocked", 8).stream().map(t -> t.getAsString()).toList()) {
                UNLOCKED.add(id);
            }
            LOGGER.info("[BiyiAtlas] unlocks loaded: {}", UNLOCKED);
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] cannot read unlocks: {}", e.toString());
        }
    }

    public static void save() {
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            CompoundTag tag = new CompoundTag();
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (String id : UNLOCKED) {
                list.add(net.minecraft.nbt.StringTag.valueOf(id));
            }
            tag.put("unlocked", list);
            NbtIo.writeCompressed(tag, file.toFile());
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] cannot save unlocks: {}", e.toString());
        }
    }

    public static boolean isUnlocked(String regionId) {
        load();
        return regionId != null && !regionId.isEmpty() && UNLOCKED.contains(regionId);
    }

    /** 服务端同步：**并集合并**（不再清空本地）。
     *
     * <p>历史实现是"清空后覆盖"，于是作者/单机在客户端解锁的区域，会在下次登录被服务端账本
     * （通常为空）整包冲掉——表现就是"这次解锁了，下次进来又未解锁"。</p>
     */
    public static void setAll(java.util.Collection<String> regionIds) {
        load();
        boolean changed = false;
        if (regionIds != null) {
            for (String id : regionIds) {
                if (id != null && !id.isEmpty() && UNLOCKED.add(id)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            save();
        }
        LOGGER.info("[BiyiAtlas] unlocks synced from server (union): {}", UNLOCKED);
    }

    public static void unlock(String regionId) {
        load();
        if (regionId != null && !regionId.isEmpty() && UNLOCKED.add(regionId)) {
            save();
            LOGGER.info("[BiyiAtlas] region unlocked: {}", regionId);
        }
    }

    public static void lock(String regionId) {
        load();
        if (UNLOCKED.remove(regionId)) {
            save();
        }
    }

    public static void toggle(String regionId) {
        boolean nowUnlocked = !isUnlocked(regionId);
        if (nowUnlocked) {
            unlock(regionId);
        } else {
            lock(regionId);
        }
        // 写回服务端账本（否则登录时会被服务端整包同步冲掉 → "下次进来又未解锁"）
        if (regionId != null && !regionId.isEmpty()
                && Minecraft.getInstance().getConnection() != null) {
            try {
                com.biyi.atlas.net.AtlasNetwork.sendToggleUnlock(regionId, nowUnlocked);
            } catch (Exception error) {
                LOGGER.warn("[BiyiAtlas] cannot send unlock toggle: {}", error.toString());
            }
        }
    }

    public static Set<String> all() {
        load();
        return new HashSet<>(UNLOCKED);
    }

    /**
     * 区域内容对玩家是否可见：无区域限制 → 可见；作者标记默认开放 → 可见；
     * 已解锁 → 可见；解锁条件是可查的进度且玩家已完成 → 可见（零持久化）。
     */
    public static boolean isVisible(String regionId) {
        if (regionId == null || regionId.isEmpty()) {
            return true;
        }
        AtlasRegion region = AtlasContent.get().regionById(regionId);
        if (region == null) {
            return true;
        }
        return selfVisible(region) && ancestorsVisible(region);
    }

    /** 自身条件（许可证 / 默认开放 / 进度）。 */
    private static boolean selfVisible(AtlasRegion region) {
        if (region.defaultUnlocked() || AtlasRegion.UNLOCK_ALWAYS.equals(region.unlockType())) {
            return true;
        }
        // 解锁口径统一：区域 id 与 unlock.id 允许不同，两个都要判定
        if (isUnlocked(region.id()) || isUnlocked(region.unlockId())) {
            return true;
        }
        if (AtlasRegion.UNLOCK_ADVANCEMENT.equals(region.unlockType())) {
            return advancementDone(region.unlockId());
        }
        return false;
    }

    /** 祖先链全部可见才可见（带访问集合与深度上限，防成环栈溢出）。 */
    private static boolean ancestorsVisible(AtlasRegion region) {
        java.util.Set<String> visited = new java.util.HashSet<>();
        String parent = region.parent();
        int depth = 0;
        while (parent != null && !parent.isEmpty() && depth++ < 8 && visited.add(parent)) {
            AtlasRegion ancestor = AtlasContent.get().regionById(parent);
            if (ancestor == null) {
                // 悬空父级（父已被删）→ 按顶层处理，避免子区域永久不可见
                return true;
            }
            if (!selfVisible(ancestor)) {
                return false;
            }
            parent = ancestor.parent();
        }
        return true;
    }

    /** 未解锁时给玩家看的提示文案。 */
    public static String lockedHint(String regionId) {
        AtlasRegion region = AtlasContent.get().regionById(regionId);
        if (region == null) {
            return "未解锁";
        }
        if (AtlasRegion.UNLOCK_ADVANCEMENT.equals(region.unlockType())) {
            return "未解锁：" + region.name() + "（需完成进度）";
        }
        return "未解锁：" + region.name() + "（需《" + region.unlockId() + "》许可证）";
    }

    private static boolean advancementDone(String advancementId) {
        // 1.20.1 客户端拿不到进度完成状态（PlayerAdvancements 在服务端，ClientAdvancements
        // 没有公开的 progress 读取口）。所以 advancement 条件目前只能由服务端用
        // /biyi_atlas unlock 授权；这里保守返回 false，避免误显示未解锁内容。
        return false;
    }

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve(BiyiAtlas.MODID).resolve("unlocked")
                .resolve(worldKey.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json.nbt");
    }
}
