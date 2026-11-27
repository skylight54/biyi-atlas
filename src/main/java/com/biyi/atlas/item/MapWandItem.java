package com.biyi.atlas.item;

import com.biyi.atlas.client.ClientBridge;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 地图魔杖：地图作者的编辑工具（仅创造/管理员可用）。
 * 右键打开"作者模式"地图，可以在里面放地点、传送点、道路。
 */
public class MapWandItem extends Item {

    public MapWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            ClientBridge.openAuthorAtlas();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
