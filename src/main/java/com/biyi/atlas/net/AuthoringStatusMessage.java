package com.biyi.atlas.net;

import com.biyi.atlas.client.AtlasAuthoring;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：**你有没有内容编辑（上传）权限**（A6 第③步）。
 *
 * <p>服务端在登录时告知。收到 {@code false} 后客户端会退出作者模式、并关掉本地内容写路径
 * （不再写 {@code biyi_atlas/authored/}），避免"玩家在本地改了半天只有自己看得见"的困惑，
 * 也让权限模型彻底由服务端决定。</p>
 */
public class AuthoringStatusMessage {

    private final boolean allowed;

    public AuthoringStatusMessage(boolean allowed) {
        this.allowed = allowed;
    }

    public static void encode(AuthoringStatusMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.allowed);
    }

    public static AuthoringStatusMessage decode(FriendlyByteBuf buffer) {
        return new AuthoringStatusMessage(buffer.readBoolean());
    }

    public static void handle(AuthoringStatusMessage message, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> AtlasAuthoring.apply(message.allowed));
        ctx.setPacketHandled(true);
    }
}
