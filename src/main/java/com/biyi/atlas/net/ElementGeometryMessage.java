package com.biyi.atlas.net;

import com.biyi.atlas.client.AtlasServerContent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：**已按玩家过滤的道路/地点**（A6 第②步收尾）。
 *
 * <p>{@code reset=true} 的第一批会让客户端先清掉上一轮服务端元素再写入。
 * 道路可带 {@code serverMask}（逐段可见性掩码），客户端据此只画授权段。</p>
 */
public class ElementGeometryMessage {

    private final boolean reset;
    private final String json;

    public ElementGeometryMessage(boolean reset, String json) {
        this.reset = reset;
        this.json = json;
    }

    public static void encode(ElementGeometryMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.reset);
        buffer.writeUtf(message.json, 262144);
    }

    public static ElementGeometryMessage decode(FriendlyByteBuf buffer) {
        return new ElementGeometryMessage(buffer.readBoolean(), buffer.readUtf(262144));
    }

    public static void handle(ElementGeometryMessage message, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> AtlasServerContent.applyElements(message.json, message.reset));
        ctx.setPacketHandled(true);
    }
}
