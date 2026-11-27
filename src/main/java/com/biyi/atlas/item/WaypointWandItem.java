package com.biyi.atlas.item;

import com.biyi.atlas.client.ClientBridge;
import com.biyi.atlas.map.WaypointType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 路标魔杖（作者用）：右键 NPC/实体 → 在地图上按当前类型标一个路标；
 * 潜行右键空气切换类型；右键空气打开地图。玩家不需要它（路标由作者设置）。
 */
public class WaypointWandItem extends Item {

    public static final String TAG_TYPE = "waypoint_type";

    public WaypointWandItem(Properties properties) {
        super(properties);
    }

    public static WaypointType typeOf(ItemStack stack) {
        return WaypointType.byId(stack.getOrCreateTag().getString(TAG_TYPE));
    }

    public static void setType(ItemStack stack, WaypointType type) {
        stack.getOrCreateTag().putString(TAG_TYPE, type.name());
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                  InteractionHand hand) {
        if (player.level().isClientSide) {
            setType(stack, typeOf(stack));
            ClientBridge.placeWaypoint(target);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            if (player.isShiftKeyDown()) {
                ClientBridge.cycleWaypointType(stack);
            } else {
                ClientBridge.openAtlas();
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("类型：" + typeOf(stack).label()).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("右键 NPC 标注 · 潜行右键切换类型").withStyle(ChatFormatting.GRAY));
    }
}
