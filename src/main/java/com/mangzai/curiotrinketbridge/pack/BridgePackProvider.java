package com.mangzai.curiotrinketbridge.pack;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 通过 Forge 的 {@link AddPackFindersEvent} 在数据包仓库中加入桥接生成的内置数据包。
 *
 * <p>设置 {@code required=true} 让该包默认启用且不可禁用；
 * 位置 {@link Pack.Position#BOTTOM} 表示其他数据包加载顺序在它之后，
 * 玩家可以通过自己的数据包覆盖任意自动生成的槽位 JSON。
 */
public final class BridgePackProvider {

    private BridgePackProvider() {}

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        try {
            Pack pack = Pack.readMetaAndCreate(
                    BridgeVirtualPack.PACK_ID,
                    Component.literal("Curio Trinkets Bridge"),
                    true,
                    id -> new BridgeVirtualPack(),
                    PackType.SERVER_DATA,
                    Pack.Position.BOTTOM,
                    PackSource.BUILT_IN
            );
            event.addRepositorySource(consumer -> consumer.accept(pack));
            CurioTrinketBridge.LOGGER.debug("[BridgePackProvider] 已注册自动槽位生成数据包");
        } catch (Throwable t) {
            CurioTrinketBridge.LOGGER.warn("[BridgePackProvider] 注册数据包失败: {}", t.toString());
        }
    }
}
