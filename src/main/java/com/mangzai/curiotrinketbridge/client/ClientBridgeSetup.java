package com.mangzai.curiotrinketbridge.client;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import com.mangzai.curiotrinketbridge.bridge.TrinketDetector;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;

/**
 * 客户端启动时扫描所有 Trinket 物品，若该物品已通过
 * {@code TrinketRendererRegistry.registerRenderer(...)} 注册了渲染器，
 * 则将其桥接为 Curios 的 {@link top.theillusivec4.curios.api.client.ICurioRenderer}
 * 并注册到 {@link CuriosRendererRegistry}。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientBridgeSetup {

    private ClientBridgeSetup() {}

    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!TrinketDetector.isTrinketsLoaded()) return;

        event.enqueueWork(() -> {
            CurioTrinketBridge.LOGGER.info("[CurioTrinketBridge] UI 交由 Accessories/cclayer 原生界面处理");

            int registered = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                if (!TrinketDetector.isTrinket(item)) continue;
                Object trinketRenderer = TrinketDetector.getTrinketRenderer(item);
                if (trinketRenderer == null) continue;
                // 注册到 Curios 渲染器注册表（registry 内部使用 Supplier，闭包捕获引用）
                final Item targetItem = item;
                final Object renderer = trinketRenderer;
                CuriosRendererRegistry.register(targetItem,
                        () -> new TrinketRendererBridge(targetItem, renderer));
                registered++;
            }
            CurioTrinketBridge.LOGGER.info("[CurioTrinketBridge] 已桥接 {} 个 Trinket 渲染器到 Curios 系统", registered);
        });
    }
}
