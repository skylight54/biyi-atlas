package com.biyi.atlas.map;

import net.minecraft.resources.ResourceLocation;

/** 地图标记点：可由作者在 markers.json 里预置，后续版本也支持玩家自行添加。 */
public record AtlasMarker(String icon, ResourceLocation dimension, double x, double z, String label) {

    public static final String DEFAULT_ICON = "pin";
}
