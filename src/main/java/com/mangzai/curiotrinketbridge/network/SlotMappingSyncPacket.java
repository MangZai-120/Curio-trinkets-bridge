package com.mangzai.curiotrinketbridge.network;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import com.mangzai.curiotrinketbridge.bridge.SlotMapper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * SlotMapper 同步包：在玩家登录或数据包重载时由服务端发送给客户端。
 *
 * <p>客户端收到后调用 {@link SlotMapper#applyFromNetwork} 用服务端的精确映射与组回退覆盖本地表，
 * 保证两端 tooltip 与 canEquip 校验一致。
 */
public final class SlotMappingSyncPacket {

    private final Map<String, String> mappings;
    private final Map<String, String> groupFallback;

    public SlotMappingSyncPacket(Map<String, String> mappings, Map<String, String> groupFallback) {
        this.mappings = mappings;
        this.groupFallback = groupFallback;
    }

    public static void encode(SlotMappingSyncPacket pkt, FriendlyByteBuf buf) {
        writeMap(buf, pkt.mappings);
        writeMap(buf, pkt.groupFallback);
    }

    public static SlotMappingSyncPacket decode(FriendlyByteBuf buf) {
        Map<String, String> m = readMap(buf);
        Map<String, String> g = readMap(buf);
        return new SlotMappingSyncPacket(m, g);
    }

    /** 仅在客户端线程内执行实际应用。 */
    public static void handle(SlotMappingSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            // 物理服务器收到此包说明对方实现错误，忽略以防被反向同步
            if (FMLEnvironment.dist != Dist.CLIENT) return;
            try {
                SlotMapper.INSTANCE.applyFromNetwork(pkt.mappings, pkt.groupFallback);
                CurioTrinketBridge.LOGGER.debug("[SlotMappingSyncPacket] 已应用来自服务端的 {} 条精确映射 / {} 条组回退",
                        pkt.mappings.size(), pkt.groupFallback.size());
            } catch (Exception e) {
                CurioTrinketBridge.LOGGER.warn("[SlotMappingSyncPacket] 应用失败: {}", e.getMessage());
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void writeMap(FriendlyByteBuf buf, Map<String, String> map) {
        buf.writeVarInt(map.size());
        for (Map.Entry<String, String> e : map.entrySet()) {
            buf.writeUtf(e.getKey(), 256);
            buf.writeUtf(e.getValue(), 256);
        }
    }

    private static Map<String, String> readMap(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, String> map = new HashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String k = buf.readUtf(256);
            String v = buf.readUtf(256);
            map.put(k, v);
        }
        return map;
    }
}
