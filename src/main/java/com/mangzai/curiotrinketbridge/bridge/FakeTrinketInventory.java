package com.mangzai.curiotrinketbridge.bridge;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FakeTrinketInventory {

    private static final ConcurrentMap<String, Object> INVENTORY_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean initFailed = false;

    private static Object unsafe;
    private static Method allocateInstance;
    private static Class<?> slotTypeClass;
    private static Class<?> trinketInvClass;
    private static Class<?> dropRuleClass;
    private static Object dropRuleDefault;

    private FakeTrinketInventory() {}

    public static Object getForSlot(String curiosSlotId) {
        if (initFailed) return null;
        String key = curiosSlotId == null ? "curio" : curiosSlotId;
        return INVENTORY_CACHE.computeIfAbsent(key, FakeTrinketInventory::buildInventory);
    }

    public static Object get() { return getForSlot("charm"); }

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

    private static Object buildInventory(String curiosSlotId) {
        ensureUnsafe();
        if (initFailed || trinketInvClass == null) return null;
        try {
            Object slotType = allocateInstance.invoke(unsafe, slotTypeClass);
            setField(slotTypeClass, slotType, "group", "curios");
            setField(slotTypeClass, slotType, "name", curiosSlotId);
            setFieldInt(slotTypeClass, slotType, "order", 0);
            setFieldInt(slotTypeClass, slotType, "amount", 1);
            setField(slotTypeClass, slotType, "icon", new ResourceLocation("trinkets", "slot"));
            setField(slotTypeClass, slotType, "quickMovePredicates", Collections.emptySet());
            setField(slotTypeClass, slotType, "validatorPredicates", Collections.emptySet());
            setField(slotTypeClass, slotType, "tooltipPredicates", Collections.emptySet());
            if (dropRuleDefault != null) setField(slotTypeClass, slotType, "dropRule", dropRuleDefault);

            Object inv = allocateInstance.invoke(unsafe, trinketInvClass);
            setField(trinketInvClass, inv, "slotType", slotType);
            setFieldInt(trinketInvClass, inv, "baseSize", 1);
            try {
                Class<?> nnl = Class.forName("net.minecraft.core.NonNullList");
                Method withSize = nnl.getMethod("withSize", int.class, Object.class);
                Object emptyStacks = withSize.invoke(null, 1, net.minecraft.world.item.ItemStack.EMPTY);
                setField(trinketInvClass, inv, "stacks", emptyStacks);
            } catch (Exception ignore) {}
            return inv;
        } catch (Throwable t) {
            CurioTrinketBridge.LOGGER.warn("[FakeTrinketInventory] build inventory for slot={} failed: {}", curiosSlotId, t.toString());
            return null;
        }
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
