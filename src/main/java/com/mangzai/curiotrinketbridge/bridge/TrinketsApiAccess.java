package com.mangzai.curiotrinketbridge.bridge;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Trinkets API 反射访问层（公共线程安全）。
 *
 * <p>由于 Trinkets 是通过 Sinytra Connector 加载的 Fabric mod，且本桥接模组编译期
 * 不依赖 Trinkets 类，所有访问统一通过反射进行，并按方法做缓存以避免每次查找。
 *
 * <p>提供给 mixin / 迁移器 / UI 判定等多处调用：
 * <ul>
 *   <li>{@link #slotIdOf(Object)} 把 SlotType 实例转成 "group/name"</li>
 *   <li>{@link #getComponent(LivingEntity)} 拿 TrinketComponent</li>
 *   <li>{@link #forEachEquipped(Object, BiConsumer)} 遍历组件中已装备 (slotRef, stack)</li>
 *   <li>{@link #setStackAt(Object, ItemStack)} 把 SlotReference 那一格设置为给定栈</li>
 *   <li>{@link #slotIdOfRef(Object)} 直接从 SlotReference 拿 trinketSlotId</li>
 * </ul>
 */
public final class TrinketsApiAccess {

    /**
     * 反向访问开关：TrinketsApiMixin 默认让 TrinketsApi.getTrinketComponent 返回空，
     * 但迁移器需要拿到真实的 TrinketComponent 才能读出库存。
     * 迁移调用前设 true，执行后在 finally 里设回 false；ThreadLocal 避免反剧。
     */
    public static final ThreadLocal<Boolean> ALLOW_COMPONENT_ACCESS = ThreadLocal.withInitial(() -> false);

    // SlotType 反射
    private static volatile Method slotTypeGetIdMethod;
    private static volatile boolean slotTypeChecked = false;

    // TrinketsApi.getTrinketComponent(LivingEntity) → Optional<TrinketComponent>
    private static volatile Method getTrinketComponentMethod;
    private static volatile boolean componentChecked = false;

    // TrinketComponent.forEach(BiConsumer)
    private static volatile Method componentForEachMethod;

    // SlotReference.inventory() → TrinketInventory
    private static volatile Method slotRefInventoryMethod;
    private static volatile Method slotRefIndexMethod;

    // TrinketInventory.getSlotType() → SlotType
    private static volatile Method invGetSlotTypeMethod;
    // TrinketInventory.setStack(int, ItemStack) — 继承自 net.minecraft.world.Container.setItem(int, ItemStack)
    private static volatile Method invSetItemMethod;

    private TrinketsApiAccess() {}

    /** 从 SlotType 实例获取 "group/name" 形式的 ID；失败返回 null。 */
    public static String slotIdOf(Object slotType) {
        if (slotType == null) return null;
        Method m = slotTypeGetIdMethod;
        if (m == null) {
            if (slotTypeChecked) return null;
            synchronized (TrinketsApiAccess.class) {
                if (slotTypeGetIdMethod == null && !slotTypeChecked) {
                    try {
                        slotTypeGetIdMethod = slotType.getClass().getMethod("getId");
                    } catch (Throwable t) {
                        CurioTrinketBridge.LOGGER.debug("[TrinketsApiAccess] 缓存 SlotType.getId 失败: {}", t.toString());
                    }
                    slotTypeChecked = true;
                }
            }
            m = slotTypeGetIdMethod;
            if (m == null) return null;
        }
        try {
            Object id = m.invoke(slotType);
            return id == null ? null : id.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 通过 SlotReference 直接取出 "group/name"；失败返回 null。 */
    public static String slotIdOfRef(Object slotRef) {
        if (slotRef == null) return null;
        try {
            ensureSlotRefMethods(slotRef);
            if (slotRefInventoryMethod == null) return null;
            Object inv = slotRefInventoryMethod.invoke(slotRef);
            if (inv == null) return null;
            ensureInventoryMethods(inv);
            if (invGetSlotTypeMethod == null) return null;
            Object type = invGetSlotTypeMethod.invoke(inv);
            return slotIdOf(type);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 SlotReference.index()。失败返回 -1。 */
    public static int indexOfRef(Object slotRef) {
        if (slotRef == null) return -1;
        try {
            ensureSlotRefMethods(slotRef);
            if (slotRefIndexMethod == null) return -1;
            Object v = slotRefIndexMethod.invoke(slotRef);
            return v instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 把 SlotReference 指向的库存槽位设置为给定 ItemStack。 */
    public static boolean setStackAt(Object slotRef, ItemStack stack) {
        if (slotRef == null) return false;
        try {
            ensureSlotRefMethods(slotRef);
            if (slotRefInventoryMethod == null || slotRefIndexMethod == null) return false;
            Object inv = slotRefInventoryMethod.invoke(slotRef);
            if (inv == null) return false;
            ensureInventoryMethods(inv);
            if (invSetItemMethod == null) return false;
            int idx = (Integer) slotRefIndexMethod.invoke(slotRef);
            invSetItemMethod.invoke(inv, idx, stack);
            return true;
        } catch (Throwable t) {
            CurioTrinketBridge.LOGGER.debug("[TrinketsApiAccess] setStackAt 失败: {}", t.toString());
            return false;
        }
    }

    /** 取 LivingEntity 的 TrinketComponent；不存在返回 null（封装 Optional）。 */
    public static Object getComponent(LivingEntity entity) {
        if (entity == null) return null;
        if (!componentChecked) {
            synchronized (TrinketsApiAccess.class) {
                if (!componentChecked) {
                    try {
                        Class<?> api = Class.forName("dev.emi.trinkets.api.TrinketsApi");
                        getTrinketComponentMethod = api.getMethod("getTrinketComponent", LivingEntity.class);
                    } catch (Throwable t) {
                        CurioTrinketBridge.LOGGER.debug("[TrinketsApiAccess] 缓存 TrinketsApi.getTrinketComponent 失败: {}", t.toString());
                    }
                    componentChecked = true;
                }
            }
        }
        Method m = getTrinketComponentMethod;
        if (m == null) return null;
        try {
            Object opt = m.invoke(null, entity);
            if (opt instanceof Optional<?> o) return o.orElse(null);
            return opt;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 遍历组件中所有 (SlotReference, ItemStack)。 */
    public static void forEachEquipped(Object component, BiConsumer<Object, ItemStack> consumer) {
        if (component == null || consumer == null) return;
        Method m = componentForEachMethod;
        if (m == null) {
            synchronized (TrinketsApiAccess.class) {
                if (componentForEachMethod == null) {
                    try {
                        for (Method candidate : component.getClass().getMethods()) {
                            if (candidate.getName().equals("forEach") && candidate.getParameterCount() == 1) {
                                componentForEachMethod = candidate;
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        CurioTrinketBridge.LOGGER.debug("[TrinketsApiAccess] 缓存 TrinketComponent.forEach 失败: {}", t.toString());
                    }
                }
            }
            m = componentForEachMethod;
            if (m == null) return;
        }
        try {
            // Trinkets API: void forEach(BiConsumer<SlotReference, ItemStack>)
            BiConsumer<Object, Object> raw = (ref, stack) -> {
                if (stack instanceof ItemStack is) {
                    consumer.accept(ref, is);
                }
            };
            m.invoke(component, raw);
        } catch (Throwable t) {
            CurioTrinketBridge.LOGGER.debug("[TrinketsApiAccess] forEach 调用失败: {}", t.toString());
        }
    }

    private static void ensureSlotRefMethods(Object slotRef) {
        if (slotRefInventoryMethod != null && slotRefIndexMethod != null) return;
        synchronized (TrinketsApiAccess.class) {
            if (slotRefInventoryMethod == null || slotRefIndexMethod == null) {
                try {
                    Class<?> c = slotRef.getClass();
                    // record SlotReference(TrinketInventory inventory, int index) — 访问方法即字段名
                    slotRefInventoryMethod = c.getMethod("inventory");
                    slotRefIndexMethod = c.getMethod("index");
                } catch (Throwable t) {
                    CurioTrinketBridge.LOGGER.debug("[TrinketsApiAccess] 缓存 SlotReference 访问器失败: {}", t.toString());
                }
            }
        }
    }

    private static void ensureInventoryMethods(Object inv) {
        if (invGetSlotTypeMethod != null && invSetItemMethod != null) return;
        synchronized (TrinketsApiAccess.class) {
            if (invGetSlotTypeMethod == null) {
                try {
                    invGetSlotTypeMethod = inv.getClass().getMethod("getSlotType");
                } catch (Throwable t) {
                    CurioTrinketBridge.LOGGER.debug("[TrinketsApiAccess] 缓存 TrinketInventory.getSlotType 失败: {}", t.toString());
                }
            }
            if (invSetItemMethod == null) {
                try {
                    // Container.setItem(int, ItemStack) — Mojmap
                    invSetItemMethod = inv.getClass().getMethod("setItem", int.class, ItemStack.class);
                } catch (Throwable ignored) {
                    try {
                        // 兼容 Yarn 名 setStack（理论上 Sinytra 已 remap，但兜底）
                        invSetItemMethod = inv.getClass().getMethod("setStack", int.class, ItemStack.class);
                    } catch (Throwable t) {
                        CurioTrinketBridge.LOGGER.debug("[TrinketsApiAccess] 缓存 TrinketInventory.setItem 失败: {}", t.toString());
                    }
                }
            }
        }
    }
}
