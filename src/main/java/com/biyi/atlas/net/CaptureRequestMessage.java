package com.biyi.atlas.net;

import com.biyi.atlas.client.LocationCapture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：请求客户端生成一张"地点俯视图"。
 * 这样 CNPC 脚本 / 命令方块 / 控制台都能触发（客户端才拿得到渲染与文件系统）。
 */
public class CaptureRequestMessage {

    private final String name;
    private final int radius;

    public CaptureRequestMessage(String name, int radius) {
        this.name = name;
        this.radius = radius;
    }

    public static void encode(CaptureRequestMessage message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.name);
        buffer.writeVarInt(message.radius);
    }

    public static CaptureRequestMessage decode(FriendlyByteBuf buffer) {
        return new CaptureRequestMessage(buffer.readUtf(), buffer.readVarInt());
    }

    public static void handle(CaptureRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> LocationCapture.capture(message.radius, message.name)));
        context.setPacketHandled(true);
    }
}
