package com.mangzai.curiotrinketbridge.bridge;

import com.google.common.collect.Multimap;
import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import javax.annotation.Nonnull;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 灏?Trinket (Fabric) 鐗╁搧閫傞厤涓?Curios 鐨?ICurioItem銆?
 *
 * <p>鏈€傞厤鍣ㄩ€氳繃 {@link TrinketDetector} 鎻愪緵鐨勫叡浜弽灏勭紦瀛樿皟鐢?Trinket 鎺ュ彛鏂规硶锛?
 * 瀹炵幇 Trinket 涓?Curios 涔嬮棿鐨勭敓鍛藉懆鏈熸ˉ鎺ワ細
 * <ul>
 *   <li>tick 鈫?curioTick</li>
 *   <li>onEquip 鈫?onEquip</li>
 *   <li>onUnequip 鈫?onUnequip</li>
 *   <li>canEquip 鈫?canEquip</li>
 *   <li>canUnequip 鈫?canUnequip</li>
 *   <li>getModifiers 鈫?getAttributeModifiers</li>
 *   <li>getDropRule 鈫?getDropRule</li>
 * </ul>
 *
 * <p>鎵€鏈夊弽灏?Method 瀵硅薄閮界紦瀛樺湪 TrinketDetector 闈欐€佸瓧娈典腑锛屾墍鏈?adapter 瀹炰緥鍏辩敤锛?
 * 閬垮厤姣忎釜 Trinket 鐗╁搧鍒涘缓涓€浠界嫭绔嬬紦瀛樺鑷村唴瀛樻氮璐广€?
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
     * 鍒涘缓涓€涓?SlotReference 瀵硅薄锛堥€氳繃鍏变韩鏋勯€犲櫒缂撳瓨锛夈€?
     * SlotReference 鏄竴涓?record(TrinketInventory inventory, int index)銆?
     * 浣跨敤 {@link FakeTrinketInventory} 鎻愪緵鐨勪吉瀹炰緥浠ｆ浛 null锛?
     * 鍑忓皯 Trinket 鍐呴儴璁块棶 inventory() 鏃剁殑 NPE 椋庨櫓銆?
     */
    private static Object createSlotReference(SlotContext slotContext) {
        Constructor<?> ctor = TrinketDetector.getSlotReferenceConstructor();
        if (ctor == null) return null;
        try {
            // 关键修复：按 Curios 槽位 ID 提供带 SlotType 的伪库存
            // 避免 Trinket 实现调用 ref.inventory().getSlotType().getGroup()/getName() 时 NPE
            Object fakeInv = FakeTrinketInventory.getForSlot(slotContext.identifier());
            return ctor.newInstance(fakeInv, slotContext.index());
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("创建 SlotReference 失败: {}", e.getMessage());
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
        // 棣栧厛閫氳繃鏍囩鏄犲皠妫€鏌ユ鐗╁搧鏄惁閫傚悎褰撳墠 Curios 妲戒綅
        if (!TrinketSlotResolver.canEquipInSlot(trinketItem, slotContext.identifier())) {
            return false;
        }

        Method m = TrinketDetector.getCanEquipMethod();
        if (m == null) return true;

        try {
            Object slotRef = createSlotReference(slotContext);
            if (slotRef == null) return true; // 鏃犳硶鏋勯€?SlotReference锛屽凡閫氳繃鏍囩鏍￠獙
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
