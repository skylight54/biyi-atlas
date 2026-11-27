package com.biyi.atlas.map;

/** 保守的加工材料规则；天然石头/原木/陶瓦不视作建筑。 */
public final class BuildingMaterials {
    private BuildingMaterials() {}
    public static boolean matches(String id) {
        String name=id.substring(id.indexOf(':')+1);
        return name.endsWith("_planks") || name.endsWith("_stairs") || name.endsWith("_slab")
                || name.endsWith("_wall") || name.endsWith("_bricks") || name.equals("bricks")
                || name.contains("glass") || name.contains("concrete") || name.startsWith("stripped_")
                || name.startsWith("polished_") || name.startsWith("cut_") || name.contains("quartz_block")
                || name.contains("copper_block");
    }
}
