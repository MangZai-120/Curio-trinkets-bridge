package com.mangzai.curiotrinketbridge.bridge;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 把 Curios 中的 Trinket 物品镜像成 Trinkets API 可见的查询结果。
 *
 * <p>这个类只提供查询/视图层桥接，不直接触发 Trinket tick/onEquip/onUnequip，避免和
 * {@link TrinketCurioAdapter} 的生命周期回调重复执行。外部模组通过 Trinkets API 查询
 * 装备状态、遍历槽位或读取库存 map 时，会看到 Curios 中的 Trinket 物品。
 */
public final class CuriosTrinketsMirror {

    /** 内部迁移真实 trinkets 库存时临时关闭镜像，避免把 Curios 镜像再次当作源库存迁移。 */
    public static final ThreadLocal<Boolean> SUPPRESS_MIRROR = ThreadLocal.withInitial(() -> false);
    /** LivingEntityTrinketComponent.forEach 原始逻辑会调用 getInventory，期间需要临时关闭库存镜像避免重复。 */
    public static final ThreadLocal<Boolean> SUPPRESS_INVENTORY_MIRROR = ThreadLocal.withInitial(() -> false);

    @FunctionalInterface
    public interface MirroredStackConsumer {
        void accept(Object slotReference, ItemStack stack);
    }

    private CuriosTrinketsMirror() {}

    /** 从 LivingEntityTrinketComponent 实例反射读取 entity 字段。 */
    public static LivingEntity resolveEntity(Object component) {
        if (component == null) return null;
        try {
            Field entityField;
            try {
                entityField = component.getClass().getField("entity");
            } catch (NoSuchFieldException nsf) {
                entityField = component.getClass().getDeclaredField("entity");
                entityField.setAccessible(true);
            }
            Object entity = entityField.get(component);
            return entity instanceof LivingEntity living ? living : null;
        } catch (Throwable t) {
            CurioTrinketBridge.LOGGER.debug("[mirror] 读取 TrinketComponent.entity 失败: {}", t.toString());
            return null;
        }
    }

    /** 遍历 Curios 中所有 Trinket 物品，并按原 Trinkets 槽位构造 SlotReference。 */
    public static void forEachMirrored(LivingEntity living, MirroredStackConsumer consumer) {
        if (living == null || consumer == null || Boolean.TRUE.equals(SUPPRESS_MIRROR.get())) return;
        try {
            Constructor<?> slotRefCtor = TrinketDetector.getSlotReferenceConstructor();
            if (slotRefCtor == null) return;
            ICuriosItemHandler curiosHandler = CuriosApi.getCuriosInventory(living).orElse(null);
            if (curiosHandler == null) return;

            for (Map.Entry<String, ICurioStacksHandler> entry : curiosHandler.getCurios().entrySet()) {
                String curiosSlotId = entry.getKey();
                IDynamicStackHandler stacks = entry.getValue().getStacks();
                Map<String, Object> inventoryByTrinketSlot = new LinkedHashMap<>();

                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack stack = stacks.getStackInSlot(i);
                    if (stack.isEmpty() || !TrinketDetector.isTrinket(stack.getItem())) continue;

                    String trinketSlotId = TrinketSlotResolver.toTrinketSlotId(stack.getItem(), curiosSlotId);
                    Object inv = inventoryByTrinketSlot.computeIfAbsent(trinketSlotId,
                            id -> createFilteredInventory(id, curiosSlotId, stacks));
                    if (inv == null) continue;

                    try {
                        Object slotRef = slotRefCtor.newInstance(inv, i);
                        consumer.accept(slotRef, stack);
                    } catch (Throwable inner) {
                        CurioTrinketBridge.LOGGER.debug("[mirror] 构造 SlotReference 失败 slot={} index={}: {}",
                                trinketSlotId, i, inner.toString());
                    }
                }
            }
        } catch (Throwable t) {
            CurioTrinketBridge.LOGGER.debug("[mirror] 遍历 Curios 镜像失败: {}", t.toString());
        }
    }

    /** TrinketComponent.isEquipped(Predicate) 的 Curios 镜像查询。 */
    public static boolean anyMirroredMatches(LivingEntity living, Predicate<ItemStack> predicate) {
        if (living == null || predicate == null || Boolean.TRUE.equals(SUPPRESS_MIRROR.get())) return false;
        final boolean[] matched = {false};
        forEachMirrored(living, (slotRef, stack) -> {
            if (!matched[0] && predicate.test(stack)) {
                matched[0] = true;
            }
        });
        return matched[0];
    }

    /** TrinketComponent.getInventory() 的 Curios 镜像视图。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Map mirrorInventory(LivingEntity living, Map originalInventory) {
        if (living == null || Boolean.TRUE.equals(SUPPRESS_MIRROR.get())
                || Boolean.TRUE.equals(SUPPRESS_INVENTORY_MIRROR.get())) {
            return originalInventory;
        }
        try {
            Map result = copyInventoryMap(originalInventory);
            ICuriosItemHandler curiosHandler = CuriosApi.getCuriosInventory(living).orElse(null);
            if (curiosHandler == null) return result;

            for (Map.Entry<String, ICurioStacksHandler> entry : curiosHandler.getCurios().entrySet()) {
                String curiosSlotId = entry.getKey();
                IDynamicStackHandler stacks = entry.getValue().getStacks();
                Map<String, Object> inventoryByTrinketSlot = new LinkedHashMap<>();

                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack stack = stacks.getStackInSlot(i);
                    if (stack.isEmpty() || !TrinketDetector.isTrinket(stack.getItem())) continue;

                    String trinketSlotId = TrinketSlotResolver.toTrinketSlotId(stack.getItem(), curiosSlotId);
                    inventoryByTrinketSlot.computeIfAbsent(trinketSlotId,
                            id -> createFilteredInventory(id, curiosSlotId, stacks));
                }

                for (Map.Entry<String, Object> mirrored : inventoryByTrinketSlot.entrySet()) {
                    String trinketSlotId = mirrored.getKey();
                    Object inventory = mirrored.getValue();
                    if (inventory == null) continue;

                    int slash = trinketSlotId.indexOf('/');
                    String group = slash > 0 ? trinketSlotId.substring(0, slash) : "curios";
                    String slot = slash > 0 ? trinketSlotId.substring(slash + 1) : trinketSlotId;
                    Map groupMap = (Map) result.computeIfAbsent(group, k -> new LinkedHashMap<>());
                    groupMap.put(slot, inventory);
                }
            }
            return result;
        } catch (Throwable t) {
            CurioTrinketBridge.LOGGER.debug("[mirror] 构造 getInventory 镜像失败: {}", t.toString());
            return originalInventory;
        }
    }

    private static Object createFilteredInventory(String trinketSlotId, String curiosSlotId, IDynamicStackHandler stacks) {
        int size = Math.max(1, stacks.getSlots());
        int[] indices = new int[size];
        Arrays.fill(indices, -1);
        boolean any = false;
        for (int i = 0; i < stacks.getSlots(); i++) {
            ItemStack stack = stacks.getStackInSlot(i);
            if (stack.isEmpty() || !TrinketDetector.isTrinket(stack.getItem())) continue;
            String currentSlotId = TrinketSlotResolver.toTrinketSlotId(stack.getItem(), curiosSlotId);
            if (trinketSlotId.equals(currentSlotId)) {
                indices[i] = i;
                any = true;
            }
        }
        return any ? FakeTrinketInventory.getLinkedForTrinketSlotId(trinketSlotId, stacks, indices) : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map copyInventoryMap(Map originalInventory) {
        Map result = new LinkedHashMap();
        if (originalInventory == null) return result;
        for (Object rawEntry : originalInventory.entrySet()) {
            Map.Entry entry = (Map.Entry) rawEntry;
            Object value = entry.getValue();
            if (value instanceof Map inner) {
                result.put(entry.getKey(), new LinkedHashMap(inner));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }
}