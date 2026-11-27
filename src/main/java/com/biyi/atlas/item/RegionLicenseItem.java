package com.biyi.atlas.item;

import com.biyi.atlas.server.AtlasUnlockManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 区域许可证：右键使用即解锁对应章节区域（NBT {@code region}），物品消耗掉。
 *
 * <p>作者可以用它做"通行证/地图残页"之类的道具；也可以直接用
 * {@code /biyi_atlas unlock <区域id>}，方便 CNPC 脚本或任务链调用。</p>
 */
public class RegionLicenseItem extends Item {

    public static final String TAG_REGION = "region";

    public RegionLicenseItem(Properties properties) {
        super(properties);
    }

    public static ItemStack forRegion(Item item, String regionId) {
        ItemStack stack = new ItemStack(item);
        stack.getOrCreateTag().putString(TAG_REGION, regionId);
        return stack;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        String region = stack.getOrCreateTag().getString(TAG_REGION);
        if (region == null || region.isEmpty()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("[避役图志] 这张许可证没有绑定区域")
                        .withStyle(ChatFormatting.RED), false);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            AtlasUnlockManager.grant(serverPlayer, region);
            player.displayClientMessage(Component.literal("[避役图志] 已解锁区域：" + region), false);
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        String region = stack.getOrCreateTag().getString(TAG_REGION);
        tooltip.add(Component.literal(region == null || region.isEmpty() ? "未绑定区域" : "解锁区域：" + region)
                .withStyle(ChatFormatting.GOLD));
    }
}
