package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasContent;
import com.biyi.atlas.map.AtlasRegion;
import com.biyi.atlas.map.BestiaryEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/** 区域怪物图鉴的写入逻辑（图鉴魔杖右键 NPC 时调用）。 */
public final class AtlasBestiary {

    private AtlasBestiary() {
    }

    public static String addEntry(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return "没有世界";
        }
        BestiaryEntry entry = NpcBestiary.read(entity);
        ResourceLocation dimension = minecraft.level.dimension().location();
        AtlasRegion region = AtlasScreen.lastSelectedRegion();
        boolean forced = region != null && region.dimension().equals(dimension);
        if (!forced || !region.contains(entity.getX(), entity.getZ())) {
            // 按位置自动找区域：从地表层往下找
            AtlasRegion found = null;
            for (int layer : new int[]{0, 1, -1, 2, -2}) {
                AtlasRegion candidate = AtlasContent.get().regionAt(dimension, layer,
                        entity.getX(), entity.getZ());
                if (candidate != null) {
                    found = candidate;
                    break;
                }
            }
            if (found != null) {
                region = found;
            }
        }
        if (region == null) {
            return "这个位置不在任何区域内：先画区域，或在地图上选中目标区域再右键";
        }
        List<BestiaryEntry> entries = new ArrayList<>(region.bestiary());
        for (BestiaryEntry existing : entries) {
            if (existing.key().equals(entry.key())) {
                return "「" + region.name() + "」图鉴里已经有 " + entry.name() + " 了";
            }
        }
        entries.add(entry);
        AtlasRegion updated = region.withBestiary(entries);
        AtlasContent.get().replaceRegion(updated);
        AtlasContent.get().saveAuthored();
        if (AtlasScreen.lastSelectedRegion() != null
                && AtlasScreen.lastSelectedRegion().id().equals(region.id())) {
            AtlasScreen.setLastSelectedRegion(updated);
        }
        return "已加入「" + region.name() + "」图鉴：" + entry.name()
                + (entry.title().isEmpty() ? "" : "（" + entry.title() + "）")
                + (entry.drops().isEmpty() ? "" : " · 掉落 " + entry.drops().size() + " 项");
    }
}
