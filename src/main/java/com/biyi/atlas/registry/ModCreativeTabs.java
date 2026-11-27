package com.biyi.atlas.registry;

import com.biyi.atlas.BiyiAtlas;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** 模组专属创造物品栏。 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BiyiAtlas.MODID);

    public static final RegistryObject<CreativeModeTab> ATLAS_TAB = TABS.register("biyi_atlas",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.biyi_atlas"))
                    .icon(() -> new ItemStack(ModItems.ATLAS.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.ATLAS.get());
                        output.accept(ModItems.MAP_WAND.get());
                        output.accept(ModItems.WAYPOINT_WAND.get());

                        output.accept(ModItems.REGION_LICENSE.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
