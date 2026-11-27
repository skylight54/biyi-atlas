package com.biyi.atlas.client;

import com.biyi.atlas.item.WaypointWandItem;
import com.biyi.atlas.map.AtlasMapData;
import com.biyi.atlas.map.WaypointType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * 路标魔杖的逻辑：右键 NPC/实体 → 在地图上按当前类型标一个路标。
 * 路标主要给"传送点/任务点/剧情点"，玩家不能自己加。
 */
public final class AtlasWaypoints {

    private AtlasWaypoints() {
    }

    /** 潜行右键空气：循环切换要放置的路标类型。 */
    public static void cycleType(ItemStack stack) {
        WaypointType current = WaypointWandItem.typeOf(stack);
        WaypointType next = current.next();
        WaypointWandItem.setType(stack, next);
        message("路标类型：" + next.label() + "（右键 NPC 即可标注）");
    }

    /** 右键实体：按当前位置与名称生成路标。 */
    public static void placeAt(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ItemStack stack = minecraft.player.getMainHandItem();
        WaypointType type = WaypointWandItem.typeOf(stack);
        String name = entity.getDisplayName().getString();
        if (name == null || name.isEmpty()) {
            name = entity.getName().getString();
        }
        String description = "由 " + entity.getName().getString() + " 设置";
        ResourceLocation dimension = minecraft.level.dimension().location();
        AtlasMapData data = AtlasStorage.get(dimension);
        data.addWaypoint(name, type.name(), entity.getX(), entity.getZ(), description);
        AtlasStorage.markDirty();
        AtlasStorage.flush();
        message("已在地图标出「" + name + "」（" + type.label() + "）");
    }

    private static void message(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("[避役图志] " + text), false);
        }
    }
}
