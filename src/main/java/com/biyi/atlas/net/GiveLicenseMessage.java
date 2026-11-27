package com.biyi.atlas.net;

import com.biyi.atlas.item.RegionLicenseItem;
import com.biyi.atlas.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端 → 服务端：作者在界面上点"生成许可证"，由服务端发放绑定好区域的许可证物品。 */
public class GiveLicenseMessage {

    private final String regionId;

    public GiveLicenseMessage(String regionId) {
        this.regionId = regionId;
    }

    public static void encode(GiveLicenseMessage message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.regionId == null ? "" : message.regionId);
    }

    public static GiveLicenseMessage decode(FriendlyByteBuf buffer) {
        return new GiveLicenseMessage(buffer.readUtf());
    }

    public static void handle(GiveLicenseMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!player.hasPermissions(2) && !player.isCreative()) {
                player.displayClientMessage(Component.literal("[避役图志] 只有管理员/创造模式能生成许可证"), false);
                return;
            }
            if (message.regionId == null || message.regionId.isEmpty()) {
                return;
            }
            ItemStack stack = RegionLicenseItem.forRegion(ModItems.REGION_LICENSE.get(), message.regionId);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            player.displayClientMessage(Component.literal("[避役图志] 已生成许可证：" + message.regionId), false);
        });
        context.setPacketHandled(true);
    }
}
