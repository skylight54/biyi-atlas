package com.biyi.atlas.registry;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.item.AtlasItem;
import com.biyi.atlas.item.MapWandItem;
import com.biyi.atlas.item.BestiaryWandItem;
import com.biyi.atlas.item.RegionLicenseItem;
import com.biyi.atlas.item.WaypointWandItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BiyiAtlas.MODID);

    /** 玩家用的羊皮纸地图。 */
    public static final RegistryObject<Item> ATLAS = ITEMS.register("atlas",
            () -> new AtlasItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    /** 地图作者的编辑工具（地图魔杖）。 */
    public static final RegistryObject<Item> MAP_WAND = ITEMS.register("map_wand",
            () -> new MapWandItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    /** 区域许可证：右键解锁对应章节区域。 */
    public static final RegistryObject<Item> REGION_LICENSE = ITEMS.register("region_license",
            () -> new RegionLicenseItem(new Item.Properties().stacksTo(16).rarity(Rarity.RARE)));

    /** 路标魔杖：右键 NPC/实体把它标到地图上（传送点/任务点/剧情点…）。 */
    public static final RegistryObject<Item> WAYPOINT_WAND = ITEMS.register("waypoint_wand",
            () -> new WaypointWandItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    /** 区域怪物图鉴魔杖：右键 NPC 把它加入所在区域的图鉴。 */
    public static final RegistryObject<Item> BESTIARY_WAND = ITEMS.register("bestiary_wand",
            () -> new BestiaryWandItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    private ModItems() {
    }

    public static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(MAP_WAND);
            event.accept(REGION_LICENSE);
            event.accept(WAYPOINT_WAND);

        }
    }
}
