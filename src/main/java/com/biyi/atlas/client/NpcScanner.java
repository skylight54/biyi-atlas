package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasMapData;
import com.biyi.atlas.map.WaypointType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * NPC 批量扫描（**可选**作者操作）：把附近的 NPC 一次性标成路标，
 * 省得用路标魔杖一个个右键。默认把 CNPC（customnpcs）NPC 当作传送点/任务点候选。
 */
public final class NpcScanner {

    private NpcScanner() {
    }

    public static String scan(double radius) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return "没有世界";
        }
        AABB box = minecraft.player.getBoundingBox().inflate(radius);
        List<Entity> entities = minecraft.level.getEntities(minecraft.player, box);
        AtlasMapData data = AtlasStorage.get(minecraft.level.dimension().location());
        int added = 0;
        int skipped = 0;
        for (Entity entity : entities) {
            if (entity instanceof Player || !(entity instanceof LivingEntity)) {
                continue;
            }
            if (!isNpcCandidate(entity)) {
                continue;
            }
            String name = entity.getDisplayName().getString();
            if (name == null || name.isBlank()) {
                name = entity.getName().getString();
            }
            if (data.hasWaypointAt(name, entity.getX(), entity.getZ())) {
                skipped++;
                continue;
            }
            data.addWaypoint(name, WaypointType.TELEPORT.name(), entity.getX(), entity.getZ(), "NPC 扫描");
            added++;
        }
        AtlasStorage.markDirty();
        AtlasStorage.flush();
        return "扫描完成：新增 " + added + " 个路标，跳过重复 " + skipped + " 个（半径 " + (int) radius + "）";
    }

    /** CNPC 的 NPC 或"有自定义名字的生物"都算候选。 */
    private static boolean isNpcCandidate(Entity entity) {
        var key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (key != null && "customnpcs".equals(key.getNamespace())) {
            return true;
        }
        return entity.hasCustomName();
    }
}
