package com.mangzai.curiotrinketbridge.bridge;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import top.theillusivec4.curios.api.CuriosApi;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    // ===== Trinket 接口反射方法的共享缓存（所有 adapter 实例共用，避免重复反射）=====
    private static Method tickMethod;
    private static Method onEquipMethod;
    private static Method onUnequipMethod;
    private static Method canEquipMethod;
    private static Method canUnequipMethod;
    private static Method getModifiersMethod;
    private static Method getDropRuleMethod;
    private static Constructor<?> slotReferenceConstructor;
    private static Class<?> trinketRendererClass;        // dev.emi.trinkets.api.TrinketRenderer
    private static Method getRendererMethod;             // TrinketRendererRegistry.getRenderer(Item)
    private static Method rendererRenderMethod;          // TrinketRenderer.render(...)
    private static volatile boolean methodsCached = false;

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
     * 一次性缓存所有 Trinket 接口反射方法（共享给所有 adapter 实例使用）。
     * 线程安全：使用 double-checked locking。
     */
    public static void cacheTrinketMethods() {
        if (methodsCached) return;
        synchronized (TrinketDetector.class) {
            if (methodsCached) return;
            try {
                Class<?> trinketClass = getTrinketInterface();
                if (trinketClass == null) {
                    methodsCached = true; // 标记完成以避免反复尝试
                    return;
                }
                for (Method m : trinketClass.getMethods()) {
                    switch (m.getName()) {
                        case "tick" -> { if (m.getParameterCount() == 3) tickMethod = m; }
                        case "onEquip" -> { if (m.getParameterCount() == 3) onEquipMethod = m; }
                        case "onUnequip" -> { if (m.getParameterCount() == 3) onUnequipMethod = m; }
                        case "canEquip" -> { if (m.getParameterCount() == 3) canEquipMethod = m; }
                        case "canUnequip" -> { if (m.getParameterCount() == 3) canUnequipMethod = m; }
                        case "getModifiers" -> {
                            if (m.getParameterCount() == 3 || m.getParameterCount() == 4) getModifiersMethod = m;
                        }
                        case "getDropRule" -> { if (m.getParameterCount() >= 2) getDropRuleMethod = m; }
                    }
                }
                // 缓存 SlotReference 构造函数（record 单一构造器）
                try {
                    Class<?> slotRefClass = Class.forName("dev.emi.trinkets.api.SlotReference");
                    slotReferenceConstructor = slotRefClass.getDeclaredConstructors()[0];
                } catch (Exception e) {
                    CurioTrinketBridge.LOGGER.debug("缓存 SlotReference 构造器失败: {}", e.getMessage());
                }
                // 缓存 TrinketRenderer 与渲染相关反射（用于客户端渲染桥接，找不到不影响主流程）
                try {
                    trinketRendererClass = Class.forName("dev.emi.trinkets.api.TrinketRenderer");
                    Class<?> rendererRegistry = Class.forName("dev.emi.trinkets.api.TrinketRendererRegistry");
                    getRendererMethod = rendererRegistry.getMethod("getRenderer", Item.class);
                    for (Method m : trinketRendererClass.getMethods()) {
                        if (m.getName().equals("render") && m.getParameterCount() >= 12) {
                            rendererRenderMethod = m;
                            break;
                        }
                    }
                } catch (Exception e) {
                    CurioTrinketBridge.LOGGER.debug("缓存 TrinketRenderer 反射失败（渲染桥接将不可用）: {}", e.getMessage());
                }
                CurioTrinketBridge.LOGGER.debug("Trinket 反射方法已缓存");
            } catch (Exception e) {
                CurioTrinketBridge.LOGGER.warn("缓存 Trinket 反射方法失败: {}", e.getMessage());
            } finally {
                methodsCached = true;
            }
        }
    }

    public static Method getTickMethod() { cacheTrinketMethods(); return tickMethod; }
    public static Method getOnEquipMethod() { cacheTrinketMethods(); return onEquipMethod; }
    public static Method getOnUnequipMethod() { cacheTrinketMethods(); return onUnequipMethod; }
    public static Method getCanEquipMethod() { cacheTrinketMethods(); return canEquipMethod; }
    public static Method getCanUnequipMethod() { cacheTrinketMethods(); return canUnequipMethod; }
    public static Method getModifiersMethod() { cacheTrinketMethods(); return getModifiersMethod; }
    public static Method getDropRuleMethod() { cacheTrinketMethods(); return getDropRuleMethod; }
    public static Constructor<?> getSlotReferenceConstructor() { cacheTrinketMethods(); return slotReferenceConstructor; }

    /**
     * 通过反射获取物品已注册的 TrinketRenderer（如果有）。
     * 仅在客户端调用时有效；找不到 TrinketRendererRegistry 类时返回 null。
     */
    public static Object getTrinketRenderer(Item item) {
        cacheTrinketMethods();
        if (getRendererMethod == null) return null;
        try {
            Object opt = getRendererMethod.invoke(null, item);
            if (opt instanceof Optional<?> o) return o.orElse(null);
            return opt;
        } catch (Exception e) {
            return null;
        }
    }

    /** 调用已缓存的 TrinketRenderer.render() 方法（仅在客户端使用） */
    public static Method getRendererRenderMethod() { cacheTrinketMethods(); return rendererRenderMethod; }

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
        // 一次性预热反射方法缓存，避免每个 adapter 实例首次调用时延迟
        cacheTrinketMethods();

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
