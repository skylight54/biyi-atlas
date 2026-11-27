package com.biyi.atlas.net;

import com.biyi.atlas.client.AtlasServerView;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：**该玩家可见的区域 id 集合**（A6 第②步）。
 *
 * <p>收到后客户端把它当**权威**：不在集合里的区域，其内容（道路/路标/地点/地图图）一律不显示。
 * 这样"未解锁看不到"不再由客户端自己算，改内存也改不出服务端没发的数据。</p>
 */
public class RegionVisibilityMessage {

    private final List<String> visible;

    public RegionVisibilityMessage(List<String> visible) {
        this.visible = visible;
    }

    public static void encode(RegionVisibilityMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.visible.size());
        for (String id : message.visible) {
            buffer.writeUtf(id);
        }
    }

    public static RegionVisibilityMessage decode(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<String> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(buffer.readUtf());
        }
        return new RegionVisibilityMessage(ids);
    }

    public static void handle(RegionVisibilityMessage message, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            Set<String> ids = new LinkedHashSet<>(message.visible);
            AtlasServerView.apply(ids);
        });
        ctx.setPacketHandled(true);
    }
}
