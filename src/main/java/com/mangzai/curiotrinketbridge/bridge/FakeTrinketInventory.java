package com.mangzai.curiotrinketbridge.bridge;

import com.google.common.collect.HashMultimap;
import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FakeTrinketInventory {

    private static final ConcurrentMap<String, Object> INVENTORY_CACHE = new ConcurrentHashMap<>();
    private static final Map<Object, LinkedInventory> LINKED_INVENTORIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean initFailed = false;

    private static Object unsafe;
    private static Method allocateInstance;
    private static Class<?> slotTypeClass;
    private static Class<?> trinketInvClass;
    private static Class<?> dropRuleClass;
    private static Object dropRuleDefault;

    private FakeTrinketInventory() {}

    /** 绑定到 Curios 实际槽位的虚拟 TrinketInventory。 */
    public record LinkedInventory(IDynamicStackHandler handler, int[] indices) {
        public int size() {
            return indices.length;
        }

        public boolean isEmpty() {
            for (int i = 0; i < indices.length; i++) {
                if (!getStack(i).isEmpty()) return false;
            }
            return true;
        }

        public ItemStack getStack(int slot) {
            int curiosIndex = toCuriosIndex(slot);
            return curiosIndex < 0 ? ItemStack.EMPTY : handler.getStackInSlot(curiosIndex);
        }

        public void setStack(int slot, ItemStack stack) {
            int curiosIndex = toCuriosIndex(slot);
            if (curiosIndex >= 0) {
                handler.setStackInSlot(curiosIndex, stack == null ? ItemStack.EMPTY : stack);
            }
        }

        public ItemStack removeStack(int slot) {
            ItemStack stack = getStack(slot);
            if (stack.isEmpty()) return ItemStack.EMPTY;
            setStack(slot, ItemStack.EMPTY);
            return stack;
        }

        public ItemStack removeStack(int slot, int amount) {
            ItemStack stack = getStack(slot);
            if (stack.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            ItemStack removed = stack.split(amount);
            setStack(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            return removed;
        }

        public void clear() {
            for (int i = 0; i < indices.length; i++) {
                setStack(i, ItemStack.EMPTY);
            }
        }

        private int toCuriosIndex(int slot) {
            if (slot < 0 || slot >= indices.length) return -1;
            return indices[slot];
        }
    }

    public static Object getForSlot(String curiosSlotId) {
        String key = curiosSlotId == null ? "curio" : curiosSlotId;
        return getForTrinketSlotId("curios/" + key, 1);
    }

    public static Object get() { return getForSlot("charm"); }

    public static Object getForTrinketSlotId(String trinketSlotId) {
        return getForTrinketSlotId(trinketSlotId, 1);
    }

    public static Object getForTrinketSlotId(String trinketSlotId, int minSize) {
        if (initFailed) return null;
        String normalized = normalizeTrinketSlotId(trinketSlotId);
        int size = Math.max(1, minSize);
        String key = normalized + "#" + size;
        return INVENTORY_CACHE.computeIfAbsent(key, ignored -> buildInventory(normalized, size));
    }

    public static Object getLinkedForTrinketSlotId(String trinketSlotId, IDynamicStackHandler handler) {
        if (handler == null) return getForTrinketSlotId(trinketSlotId);
        int size = Math.max(1, handler.getSlots());
        int[] indices = new int[size];
        for (int i = 0; i < size; i++) indices[i] = i;
        return getLinkedForTrinketSlotId(trinketSlotId, handler, indices);
    }

    public static Object getLinkedForTrinketSlotId(String trinketSlotId, IDynamicStackHandler handler, int[] indices) {
        if (initFailed || handler == null) return null;
        int[] safeIndices = indices == null || indices.length == 0 ? new int[] {0} : Arrays.copyOf(indices, indices.length);
        Object inv = buildInventory(normalizeTrinketSlotId(trinketSlotId), safeIndices.length);
        if (inv != null) {
            LINKED_INVENTORIES.put(inv, new LinkedInventory(handler, safeIndices));
        }
        return inv;
    }

    public static LinkedInventory getLinked(Object inventory) {
        if (inventory == null) return null;
        return LINKED_INVENTORIES.get(inventory);
    }

    private static synchronized void ensureUnsafe() {
        if (unsafe != null || initFailed) return;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            slotTypeClass = Class.forName("dev.emi.trinkets.api.SlotType");
            trinketInvClass = Class.forName("dev.emi.trinkets.api.TrinketInventory");
            try {
                dropRuleClass = Class.forName("dev.emi.trinkets.api.TrinketEnums$DropRule");
            } catch (ClassNotFoundException e) {
                Class<?>[] inner = Class.forName("dev.emi.trinkets.api.TrinketEnums").getDeclaredClasses();
                if (inner.length > 0) dropRuleClass = inner[0];
            }
            if (dropRuleClass != null) {
                for (Object constant : dropRuleClass.getEnumConstants()) {
                    if ("DEFAULT".equals(constant.toString())) { dropRuleDefault = constant; break; }
                }
            }
        } catch (Exception e) {
            initFailed = true;
            CurioTrinketBridge.LOGGER.warn("[FakeTrinketInventory] init reflection failed: {}", e.getMessage());
        }
    }

    private static Object buildInventory(String trinketSlotId, int size) {
        ensureUnsafe();
        if (initFailed || trinketInvClass == null) return null;
        try {
            String normalized = normalizeTrinketSlotId(trinketSlotId);
            int slash = normalized.indexOf('/');
            String group = slash > 0 ? normalized.substring(0, slash) : "curios";
            String slot = slash > 0 ? normalized.substring(slash + 1) : normalized;
            int inventorySize = Math.max(1, size);

            Object slotType = allocateInstance.invoke(unsafe, slotTypeClass);
            setField(slotTypeClass, slotType, "group", group);
            setField(slotTypeClass, slotType, "name", slot);
            setFieldInt(slotTypeClass, slotType, "order", 0);
            setFieldInt(slotTypeClass, slotType, "amount", inventorySize);
            setField(slotTypeClass, slotType, "icon", new ResourceLocation("trinkets", "slot"));
            setField(slotTypeClass, slotType, "quickMovePredicates", Collections.emptySet());
            setField(slotTypeClass, slotType, "validatorPredicates", Collections.emptySet());
            setField(slotTypeClass, slotType, "tooltipPredicates", Collections.emptySet());
            if (dropRuleDefault != null) setField(slotTypeClass, slotType, "dropRule", dropRuleDefault);

            Object inv = allocateInstance.invoke(unsafe, trinketInvClass);
            setField(trinketInvClass, inv, "slotType", slotType);
            setFieldInt(trinketInvClass, inv, "baseSize", inventorySize);
            setField(trinketInvClass, inv, "modifiers", new HashMap<>());
            setField(trinketInvClass, inv, "persistentModifiers", new HashSet<>());
            setField(trinketInvClass, inv, "cachedModifiers", new HashSet<>());
            setField(trinketInvClass, inv, "modifiersByOperation", HashMultimap.create());
            setField(trinketInvClass, inv, "updateCallback", (Consumer<Object>) ignored -> {});
            try {
                Class<?> nnl = Class.forName("net.minecraft.core.NonNullList");
                Method withSize = nnl.getMethod("withSize", int.class, Object.class);
                Object emptyStacks = withSize.invoke(null, inventorySize, ItemStack.EMPTY);
                setField(trinketInvClass, inv, "stacks", emptyStacks);
            } catch (Exception ignore) {}
            return inv;
        } catch (Throwable t) {
            CurioTrinketBridge.LOGGER.warn("[FakeTrinketInventory] build inventory for slot={} failed: {}", trinketSlotId, t.toString());
            return null;
        }
    }

    private static String normalizeTrinketSlotId(String trinketSlotId) {
        if (trinketSlotId == null || trinketSlotId.isBlank()) return "curios/curio";
        String cleaned = trinketSlotId.replace('\\', '/');
        return cleaned.indexOf('/') > 0 ? cleaned : "curios/" + cleaned;
    }

    private static void setField(Class<?> cls, Object instance, String fieldName, Object value) {
        try { Field f = cls.getDeclaredField(fieldName); f.setAccessible(true); f.set(instance, value); }
        catch (NoSuchFieldException ignore) {}
        catch (Exception e) { CurioTrinketBridge.LOGGER.debug("set field {}.{} failed: {}", cls.getSimpleName(), fieldName, e.getMessage()); }
    }

    private static void setFieldInt(Class<?> cls, Object instance, String fieldName, int value) {
        try { Field f = cls.getDeclaredField(fieldName); f.setAccessible(true); f.setInt(instance, value); }
        catch (NoSuchFieldException ignore) {}
        catch (Exception e) { CurioTrinketBridge.LOGGER.debug("set int field {}.{} failed: {}", cls.getSimpleName(), fieldName, e.getMessage()); }
    }
}
