package com.mangzai.curiotrinketbridge.bridge;

import com.google.common.collect.Multimap;
import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.capability.ICurioItem;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import javax.annotation.Nonnull;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将 Trinket (Fabric) 物品适配为 Curios 的 ICurioItem。
 *
 * <p>本适配器通过 {@link TrinketDetector} 提供的共享反射缓存调用 Trinket 接口方法，
 * 实现 Trinket 与 Curios 之间的生命周期桥接：
 * <ul>
 *   <li>tick → curioTick</li>
 *   <li>onEquip → onEquip</li>
 *   <li>onUnequip → onUnequip</li>
 *   <li>canEquip → canEquip</li>
 *   <li>canUnequip → canUnequip</li>
 *   <li>getModifiers → getAttributeModifiers</li>
 *   <li>getDropRule → getDropRule</li>
 * </ul>
 *
 * <p>所有反射 Method 对象都缓存在 TrinketDetector 静态字段中，所有 adapter 实例共用，
 * 避免每个 Trinket 物品创建一份独立缓存导致内存浪费。
 */
public class TrinketCurioAdapter implements ICurioItem {


    private final Item trinketItem;
    private final Object trinketHandler; // 实际的 Trinket 行为处理器（可能是 item 本身或独立对象）

    // 记录已经发出 WARN 的 (item|method) 组合，避免日志刷屏；首次失败给出完整堆栈，后续仅 debug
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public TrinketCurioAdapter(Item trinketItem, Object trinketHandler) {
        this.trinketItem = trinketItem;
        this.trinketHandler = trinketHandler;
    }

    private void logFailure(String method, Throwable t) {
        String key = trinketItem.getDescriptionId() + "|" + method;
        if (WARNED.add(key)) {
            CurioTrinketBridge.LOGGER.warn("[TrinketCurioAdapter] {} 调用 Trinket.{} 失败（首次）: {}",
                    trinketItem.getDescriptionId(), method, t.toString(), t);
        } else {
            CurioTrinketBridge.LOGGER.debug("[TrinketCurioAdapter] {} 调用 Trinket.{} 失败: {}",
                    trinketItem.getDescriptionId(), method, t.toString());
        }
    }

    /**
     * 创建一个 SlotReference 对象（通过共享构造器缓存）。
     * SlotReference 是一个 record(TrinketInventory inventory, int index)。
     * 使用 {@link FakeTrinketInventory} 提供的伪实例代替 null，
     * 减少 Trinket 内部访问 inventory() 时的 NPE 风险。
     */
    private Object createSlotReference(SlotContext slotContext) {

        Constructor<?> ctor = TrinketDetector.getSlotReferenceConstructor();
        if (ctor == null) return null;
        try {
            String trinketSlotId = TrinketSlotResolver.toTrinketSlotId(trinketItem, slotContext.identifier());
            Object fakeInv = createLinkedInventory(slotContext, trinketSlotId);
            if (fakeInv == null) {
                fakeInv = FakeTrinketInventory.getForTrinketSlotId(trinketSlotId, slotContext.index() + 1);
            }
            return ctor.newInstance(fakeInv, slotContext.index());
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("创建 SlotReference 失败: {}", e.getMessage());
            return null;
        }
    }

    private Object createLinkedInventory(SlotContext slotContext, String trinketSlotId) {
        try {
            ICuriosItemHandler curiosHandler = CuriosApi.getCuriosInventory(slotContext.entity()).orElse(null);
            if (curiosHandler == null) return null;
            ICurioStacksHandler slotHandler = curiosHandler.getCurios().get(slotContext.identifier());
            if (slotHandler == null) return null;
            IDynamicStackHandler stacks = slotHandler.getStacks();
            return FakeTrinketInventory.getLinkedForTrinketSlotId(trinketSlotId, stacks);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void curioTick(SlotContext slotContext, ItemStack stack) {
        Method m = TrinketDetector.getTickMethod();
        if (m == null) return;
        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return;
            m.invoke(trinketHandler, stack, slotRef, slotContext.entity());
        } catch (Exception e) {
            logFailure("tick", e);
        }
    }

    @Override
    public void onEquip(SlotContext slotContext, ItemStack prevStack, ItemStack stack) {
        Method m = TrinketDetector.getOnEquipMethod();
        if (m == null) return;
        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return;
            m.invoke(trinketHandler, stack, slotRef, slotContext.entity());
        } catch (Exception e) {
            logFailure("onEquip", e);
        }
    }

    @Override
    public void onUnequip(SlotContext slotContext, ItemStack newStack, ItemStack stack) {
        Method m = TrinketDetector.getOnUnequipMethod();
        if (m == null) return;
        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return;
            m.invoke(trinketHandler, stack, slotRef, slotContext.entity());
        } catch (Exception e) {
            logFailure("onUnequip", e);
        }
    }

    @Override
    public boolean canEquip(SlotContext slotContext, ItemStack stack) {
        // 首先通过标签映射检查此物品是否适合当前 Curios 槽位
        if (!TrinketSlotResolver.canEquipInSlot(trinketItem, slotContext.identifier())) {
            return false;
        }

        Method m = TrinketDetector.getCanEquipMethod();
        if (m == null) return true;

        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return true; // 无法构造 SlotReference，已通过标签校验
            Object result = m.invoke(trinketHandler, stack, slotRef, slotContext.entity());
            return !(result instanceof Boolean b) || b;
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public boolean canUnequip(SlotContext slotContext, ItemStack stack) {
        Method m = TrinketDetector.getCanUnequipMethod();
        if (m == null) return true;
        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return true;
            Object result = m.invoke(trinketHandler, stack, slotRef, slotContext.entity());
            return !(result instanceof Boolean b) || b;
        } catch (Exception e) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    @Nonnull
    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(SlotContext slotContext,
                                                                         UUID uuid,
                                                                         ItemStack stack) {
        Method m = TrinketDetector.getModifiersMethod();
        if (m == null) {
            return ICurioItem.super.getAttributeModifiers(slotContext, uuid, stack);
        }
        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return ICurioItem.super.getAttributeModifiers(slotContext, uuid, stack);
            Object result;
            if (m.getParameterCount() == 4) {
                result = m.invoke(trinketHandler, stack, slotRef, slotContext.entity(), uuid);
            } else {
                result = m.invoke(trinketHandler, stack, slotRef, uuid);
            }
            if (result instanceof Multimap<?, ?>) {
                return (Multimap<Attribute, AttributeModifier>) result;
            }
        } catch (Exception e) {
            logFailure("getModifiers", e);
        }
        return ICurioItem.super.getAttributeModifiers(slotContext, uuid, stack);
    }

    @Nonnull
    @Override
    public ICurio.DropRule getDropRule(SlotContext slotContext, net.minecraft.world.damagesource.DamageSource source,
                                       int lootingLevel, boolean recentlyHit, ItemStack stack) {
        Method m = TrinketDetector.getDropRuleMethod();
        if (m == null) return ICurio.DropRule.DEFAULT;
        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return ICurio.DropRule.DEFAULT;
            Object result = m.invoke(trinketHandler, ICurio.DropRule.DEFAULT, stack, slotRef, slotContext.entity());
            if (result != null) {
                return switch (result.toString()) {
                    case "KEEP" -> ICurio.DropRule.ALWAYS_KEEP;
                    case "DROP" -> ICurio.DropRule.ALWAYS_DROP;
                    case "DESTROY" -> ICurio.DropRule.DESTROY;
                    default -> ICurio.DropRule.DEFAULT;
                };
            }
        } catch (Exception e) {
            logFailure("getDropRule", e);
        }
        return ICurio.DropRule.DEFAULT;
    }
}
