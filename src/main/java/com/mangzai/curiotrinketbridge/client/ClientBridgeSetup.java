package com.mangzai.curiotrinketbridge.client;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import com.mangzai.curiotrinketbridge.bridge.TrinketDetector;
import com.mangzai.curiotrinketbridge.client.gui.UnifiedCuriosScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.common.CuriosRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

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
            replaceScreen(CuriosRegistry.CURIO_MENU.get(), UnifiedCuriosScreen.Legacy::new);
            replaceScreen(CuriosRegistry.CURIO_MENU_NEW.get(), UnifiedCuriosScreen.Revamp::new);
            CurioTrinketBridge.LOGGER.info("[CurioTrinketBridge] 已接管 Curios 原生 UI，使用统一饰品界面");

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

    private static <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void replaceScreen(
            MenuType<? extends M> type,
            MenuScreens.ScreenConstructor<M, U> factory) {
        screenFactories().put(type, factory);
    }

    @SuppressWarnings("unchecked")
    private static Map<MenuType<?>, MenuScreens.ScreenConstructor<?, ?>> screenFactories() {
        for (Field field : MenuScreens.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !Map.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Map<?, ?> map) {
                    return (Map<MenuType<?>, MenuScreens.ScreenConstructor<?, ?>>) map;
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("无法访问 MenuScreens 注册表", e);
            }
        }
        throw new IllegalStateException("无法定位 MenuScreens 注册表");
    }
}
