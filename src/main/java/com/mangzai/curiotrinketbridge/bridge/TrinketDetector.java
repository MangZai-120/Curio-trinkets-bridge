package com.mangzai.curiotrinketbridge.bridge;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
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
    private static boolean checked = false;
    private static boolean available = false;

    private TrinketDetector() {}

    /**
     * 检查 Trinkets API 是否已加载（通过 Sinytra Connector）
     */
    public static boolean isTrinketsLoaded() {
        if (!checked) {
            try {
                trinketInterface = Class.forName("dev.emi.trinkets.api.Trinket");
                trinketItemClass = Class.forName("dev.emi.trinkets.api.TrinketItem");
                available = true;
                CurioTrinketBridge.LOGGER.debug("Trinkets API 类已成功加载");
            } catch (ClassNotFoundException e) {
                available = false;
                CurioTrinketBridge.LOGGER.debug("Trinkets API 类未找到: {}", e.getMessage());
            }
            checked = true;
        }
        return available;
    }

    /**
     * 检查物品是否为 Trinket（实现 Trinket 接口或继承 TrinketItem）
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
     * 获取 TrinketItem 基类的 Class 对象
     */
    public static Class<?> getTrinketItemClass() {
        isTrinketsLoaded();
        return trinketItemClass;
    }

    /**
     * 通过反射安全地调用 Trinket 上的方法
     * @param trinket Trinket 实例
     * @param methodName 方法名
     * @param paramTypes 参数类型
     * @param args 参数值
     * @return 方法返回值，如果调用失败返回 null
     */
    public static Object invokeTrinketMethod(Object trinket, String methodName,
                                              Class<?>[] paramTypes, Object... args) {
        try {
            Method method = trinket.getClass().getMethod(methodName, paramTypes);
            return method.invoke(trinket, args);
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("调用 Trinket 方法 {} 失败: {}", methodName, e.getMessage());
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
            if (isTrinket(item)) {
                try {
                    TrinketCurioAdapter adapter = new TrinketCurioAdapter(item);
                    CuriosApi.registerCurio(item, adapter);
                    registeredTrinkets.add(item);
                } catch (Exception e) {
                    CurioTrinketBridge.LOGGER.warn("注册 Trinket 物品 {} 到 Curios 失败: {}",
                            BuiltInRegistries.ITEM.getKey(item), e.getMessage());
                }
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
