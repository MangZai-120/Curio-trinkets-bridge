package com.mangzai.curiotrinketbridge.bridge;

import com.google.common.collect.Multimap;
import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 将 Trinket (Fabric) 物品适配为 Curios 的 ICurioItem
 *
 * 通过反射调用 Trinket 接口的方法，实现生命周期桥接：
 * - tick → curioTick
 * - onEquip → onEquip
 * - onUnequip → onUnequip
 * - canEquip → canEquip
 * - canUnequip → canUnequip
 * - getModifiers → getAttributeModifiers
 * - getDropRule → getDropRule
 */
public class TrinketCurioAdapter implements ICurioItem {

    private final Item trinketItem;
    private final Object trinketHandler; // 实际的 Trinket 行为处理器（可能是 item 本身或独立对象）

    // 缓存反射方法
    private Method tickMethod;
    private Method onEquipMethod;
    private Method onUnequipMethod;
    private Method canEquipMethod;
    private Method canUnequipMethod;
    private Method getModifiersMethod;
    private Method getDropRuleMethod;
    private boolean methodsCached = false;

    public TrinketCurioAdapter(Item trinketItem, Object trinketHandler) {
        this.trinketItem = trinketItem;
        this.trinketHandler = trinketHandler;
    }

    /**
     * 延迟缓存反射方法，避免初始化时出错
     */
    private void ensureMethodsCached() {
        if (methodsCached) return;
        methodsCached = true;

        try {
            Class<?> trinketClass = TrinketDetector.getTrinketInterface();
            if (trinketClass == null) return;

            // Trinket.tick(ItemStack stack, SlotReference slot, LivingEntity entity)
            // 注意: SlotReference 可能不可用，我们需要做兼容处理
            Class<?> itemStackClass = ItemStack.class;
            Class<?> livingEntityClass = LivingEntity.class;

            // 尝试获取方法（参数类型可能因 Sinytra 重映射而不同）
            for (Method m : trinketClass.getMethods()) {
                switch (m.getName()) {
                    case "tick" -> {
                        if (m.getParameterCount() == 3) tickMethod = m;
                    }
                    case "onEquip" -> {
                        if (m.getParameterCount() == 3) onEquipMethod = m;
                    }
                    case "onUnequip" -> {
                        if (m.getParameterCount() == 3) onUnequipMethod = m;
                    }
                    case "canEquip" -> {
                        if (m.getParameterCount() == 3) canEquipMethod = m;
                    }
                    case "canUnequip" -> {
                        if (m.getParameterCount() == 3) canUnequipMethod = m;
                    }
                    case "getModifiers" -> {
                        if (m.getParameterCount() == 3 || m.getParameterCount() == 4) {
                            getModifiersMethod = m;
                        }
                    }
                    case "getDropRule" -> {
                        if (m.getParameterCount() >= 2) getDropRuleMethod = m;
                    }
                }
            }
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.warn("缓存 Trinket 反射方法失败: {}", e.getMessage());
        }
    }

    /**
     * 创建一个伪 SlotReference 对象（通过反射），用于传递给 Trinket 方法。
     * SlotReference 是一个 record(TrinketInventory inventory, int index)。
     * 使用 {@link FakeTrinketInventory} 提供的伪实例代替 null，
     * 减少 Trinket 内部访问 inventory() 时的 NPE 风险。
     */
    private Object createSlotReference(SlotContext slotContext) {
        try {
            Class<?> slotRefClass = Class.forName("dev.emi.trinkets.api.SlotReference");
            Object fakeInv = FakeTrinketInventory.get(); // 可能为 null（如果 Unsafe 不可用）
            return slotRefClass.getDeclaredConstructors()[0].newInstance(fakeInv, slotContext.index());
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("创建 SlotReference 失败，将传递 null: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void curioTick(SlotContext slotContext, ItemStack stack) {
        ensureMethodsCached();
        if (tickMethod == null) return;

        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return; // 无法创建 SlotReference，跳过以避免 NPE
            tickMethod.invoke(trinketHandler, stack, slotRef, slotContext.entity());
        } catch (Exception e) {
            // Trinket 内部访问 SlotReference.inventory() 导致 NPE 等情况，仅记录一次
            CurioTrinketBridge.LOGGER.debug("Trinket tick 调用失败: {}", e.getMessage());
        }
    }

    @Override
    public void onEquip(SlotContext slotContext, ItemStack prevStack, ItemStack stack) {
        ensureMethodsCached();
        if (onEquipMethod == null) return;

        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return;
            onEquipMethod.invoke(trinketHandler, stack, slotRef, slotContext.entity());
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("Trinket onEquip 调用失败: {}", e.getMessage());
        }
    }

    @Override
    public void onUnequip(SlotContext slotContext, ItemStack newStack, ItemStack stack) {
        ensureMethodsCached();
        if (onUnequipMethod == null) return;

        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return;
            onUnequipMethod.invoke(trinketHandler, stack, slotRef, slotContext.entity());
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("Trinket onUnequip 调用失败: {}", e.getMessage());
        }
    }

    @Override
    public boolean canEquip(SlotContext slotContext, ItemStack stack) {
        // 首先通过标签映射检查此物品是否适合当前 Curios 槽位
        if (!TrinketSlotResolver.canEquipInSlot(trinketItem, slotContext.identifier())) {
            return false;
        }

        // 然后委托给 Trinket 自身的 canEquip 逻辑
        ensureMethodsCached();
        if (canEquipMethod == null) return true;

        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return true; // 无法构造 SlotReference，允许装备（已通过标签校验）
            Object result = canEquipMethod.invoke(trinketHandler, stack, slotRef, slotContext.entity());
            return result instanceof Boolean b ? b : true;
        } catch (Exception e) {
            return true; // 反射失败时允许装备（已通过标签校验）
        }
    }

    @Override
    public boolean canUnequip(SlotContext slotContext, ItemStack stack) {
        ensureMethodsCached();
        if (canUnequipMethod == null) return true;

        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return true;
            Object result = canUnequipMethod.invoke(trinketHandler, stack, slotRef, slotContext.entity());
            return result instanceof Boolean b ? b : true;
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
        ensureMethodsCached();
        if (getModifiersMethod == null) {
            return ICurioItem.super.getAttributeModifiers(slotContext, uuid, stack);
        }

        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return ICurioItem.super.getAttributeModifiers(slotContext, uuid, stack);
            Object result;
            if (getModifiersMethod.getParameterCount() == 4) {
                result = getModifiersMethod.invoke(trinketHandler, stack, slotRef, slotContext.entity(), uuid);
            } else {
                result = getModifiersMethod.invoke(trinketHandler, stack, slotRef, uuid);
            }
            if (result instanceof Multimap<?, ?>) {
                return (Multimap<Attribute, AttributeModifier>) result;
            }
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("Trinket getModifiers 调用失败: {}", e.getMessage());
        }

        return ICurioItem.super.getAttributeModifiers(slotContext, uuid, stack);
    }

    @Nonnull
    @Override
    public ICurio.DropRule getDropRule(SlotContext slotContext, net.minecraft.world.damagesource.DamageSource source,
                                       int lootingLevel, boolean recentlyHit, ItemStack stack) {
        ensureMethodsCached();
        if (getDropRuleMethod == null) return ICurio.DropRule.DEFAULT;

        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return ICurio.DropRule.DEFAULT;
            Object result = getDropRuleMethod.invoke(trinketHandler, ICurio.DropRule.DEFAULT,
                    stack, slotRef, slotContext.entity());
            if (result != null) {
                String name = result.toString();
                return switch (name) {
                    case "KEEP" -> ICurio.DropRule.ALWAYS_KEEP;
                    case "DROP" -> ICurio.DropRule.ALWAYS_DROP;
                    case "DESTROY" -> ICurio.DropRule.DESTROY;
                    default -> ICurio.DropRule.DEFAULT;
                };
            }
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("Trinket getDropRule 调用失败: {}", e.getMessage());
        }
        return ICurio.DropRule.DEFAULT;
    }
}
