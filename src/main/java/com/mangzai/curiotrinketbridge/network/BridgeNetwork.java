package com.mangzai.curiotrinketbridge.network;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import com.mangzai.curiotrinketbridge.bridge.SlotMapper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 桥接模组的网络通道。仅用于同步 SlotMapper 数据包内容到客户端。
 *
 * <p>协议版本固定为 "1"。客户端可选（可缺少模组），服务端可选（专用服务端可能不装本模组）。
 * 这样既允许"仅服务端装"也允许"仅客户端装"，不会因为协议不匹配而拒绝连接。
 */
public final class BridgeNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(CurioTrinketBridge.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            // 双端可选：未安装本模组的端不会因协议不匹配被踢
            .clientAcceptedVersions(v -> true)
            .serverAcceptedVersions(v -> true)
            .simpleChannel();

    private BridgeNetwork() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(
                id++,
                SlotMappingSyncPacket.class,
                SlotMappingSyncPacket::encode,
                SlotMappingSyncPacket::decode,
                SlotMappingSyncPacket::handle
        );
    }

    /** 发送当前 SlotMapper 给指定玩家。 */
    public static void sendTo(ServerPlayer player) {
        SlotMappingSyncPacket pkt = new SlotMappingSyncPacket(
                SlotMapper.INSTANCE.getAllMappings(),
                SlotMapper.INSTANCE.getGroupFallback()
        );
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), pkt);
    }

    /** 广播给全部已登录玩家（用于数据包重载后）。 */
    public static void broadcast() {
        SlotMappingSyncPacket pkt = new SlotMappingSyncPacket(
                SlotMapper.INSTANCE.getAllMappings(),
                SlotMapper.INSTANCE.getGroupFallback()
        );
        CHANNEL.send(PacketDistributor.ALL.noArg(), pkt);
    }
}
