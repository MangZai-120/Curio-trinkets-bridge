package com.mangzai.curiotrinketbridge.bridge;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import top.theillusivec4.curios.api.CuriosApi;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过反射检测 Trinkets (Fabric) 物品
 * 在运行时检查 Trinkets API 是否可用（通过 Sinytra Connector 加载）
 */
public final class TrinketDetector {

    private static Class<?> trinketInterface;
    private static Class<?> trinketItemClass;
    private static Method getTrinketMethod;        // TrinketsApi.getTrinket(Item)
    private static Object defaultTrinketHandler;   // 未注册物品的默认空处理器
    private static boolean checked = false;
    private static boolean available = false;

    private TrinketDetector() {}

    /**
     * 检查 Trinkets API 是否已加载（通过 Sinytra Connector）
     */
    public static boolean isTrinketsLoaded() {
        if (!checked) {
            checked = true;
            try {
                trinketInterface = Class.forName("dev.emi.trinkets.api.Trinket");
                trinketItemClass = Class.forName("dev.emi.trinkets.api.TrinketItem");

                // 缓存 TrinketsApi.getTrinket 方法，用于查找注册的 Trinket 处理器
                Class<?> apiClass = Class.forName("dev.emi.trinkets.api.TrinketsApi");
                getTrinketMethod = apiClass.getMethod("getTrinket", Item.class);
                // 获取默认处理器（对未注册物品返回的空处理器），用于辨别是否有自定义处理器
                defaultTrinketHandler = getTrinketMethod.invoke(null, Items.AIR);

                available = true;
                CurioTrinketBridge.LOGGER.debug("Trinkets API 类已成功加载");
            } catch (ClassNotFoundException e) {
                available = false;
                CurioTrinketBridge.LOGGER.debug("Trinkets API 类未找到: {}", e.getMessage());
            } catch (Exception e) {
                // getTrinket 缓存失败，但 Trinkets 核心类存在
                available = trinketInterface != null;
                CurioTrinketBridge.LOGGER.debug("Trinkets API 处理器缓存失败（不影响核心功能）: {}", e.getMessage());
            }
        }
        return available;
    }

    /**
     * 检查物品是否为 Trinket（实现 Trinket 接口）
     */
    public static boolean isTrinket(Item item) {
        if (!isTrinketsLoaded()) return false;
        return trinketInterface.isInstance(item);
    }

    /**
     * 获取 Trinket 接口的 Class 对象
     */
    public static Class<?> getTrinketInterface() {
        isTrinketsLoaded();
        return trinketInterface;
    }

    /**
     * 获取物品注册的 Trinket 行为处理器。
     * <p>
     * 优先返回通过 TrinketsApi.registerTrinket(item, handler) 注册的处理器。
     * 如果物品未单独注册处理器（返回默认空处理器），则返回 null，
     * 调用方应将物品本身作为 handler（适用于 TrinketItem 子类或直接实现 Trinket 的物品）。
     *
     * @param item 要查询的物品
     * @return 注册的 Trinket 处理器，未找到自定义处理器时返回 null
     */
    public static Object getTrinketHandler(Item item) {
        if (getTrinketMethod == null) return null;
        try {
            Object handler = getTrinketMethod.invoke(null, item);
            // 与默认空处理器比较 — 如果相同说明没有自定义注册
            if (handler == defaultTrinketHandler) return null;
            return handler;
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("获取 Trinket 处理器失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 扫描所有已注册物品，将 Trinket 物品注册到 Curios 系统
     */
    public static void scanAndRegisterTrinkets() {
        if (!isTrinketsLoaded()) return;

        List<Item> registeredTrinkets = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM) {
            if (!isTrinket(item)) continue;

            try {
                // 优先使用 TrinketsApi 注册的处理器，否则用物品自身（它实现了 Trinket 接口）
                Object handler = getTrinketHandler(item);
                if (handler == null) handler = item;

                TrinketCurioAdapter adapter = new TrinketCurioAdapter(item, handler);
                CuriosApi.registerCurio(item, adapter);
                registeredTrinkets.add(item);
            } catch (Exception e) {
                CurioTrinketBridge.LOGGER.warn("注册 Trinket 物品 {} 到 Curios 失败: {}",
                        BuiltInRegistries.ITEM.getKey(item), e.getMessage());
            }
        }

        CurioTrinketBridge.LOGGER.info("[CurioTrinketBridge] 已桥接 {} 个 Trinket 物品到 Curios 系统",
                registeredTrinkets.size());

        if (CurioTrinketBridge.LOGGER.isDebugEnabled()) {
            for (Item item : registeredTrinkets) {
                CurioTrinketBridge.LOGGER.debug("  - {}", BuiltInRegistries.ITEM.getKey(item));
            }
        }
    }
}
