package com.mangzai.curiotrinketbridge.bridge;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trinkets → Curios 物品自动迁移器。
 *
 * <p>每 20 tick（≈1 秒）由服务端 tick 事件调用一次，扫描每个玩家的 trinkets 库存，
 * 把"原生映射槽位"（{@link SlotMapper#shouldHandleAsNative}）中的物品自动转移到 Curios：
 * <ol>
 *   <li>找到该 trinket 物品在 Curios 中的合法槽位（{@link TrinketSlotResolver#getValidCuriosSlots}）</li>
 *   <li>遍历这些 Curios 槽，找空位放入</li>
 *   <li>失败 → 投入玩家主背包</li>
 *   <li>仍失败（背包满）→ 在玩家位置丢出</li>
 * </ol>
 *
 * <p>纯自定义槽位的物品保持在 trinkets 端不动（设计上由 trinkets 自行 UI 接管）。
 *
 * <p>性能：仅遍历组件 + 反射调用，且按 20 tick 频率，对 TPS 影响可忽略。
 * 多人环境安全：仅服务端调用、不做客户端假设。
 */
public final class TrinketsItemMigrator {

    private TrinketsItemMigrator() {}

    /** 一次迁移单元：保留对源 SlotReference 的引用以便最终清空。 */
    private record PendingMigration(Object slotRef, ItemStack stack, String trinketSlotId) {}

    /**
     * 清理 Curios 已存在同款时真实 trinkets 库存里的副本。
     *
     * <p>这是防复制兜底：即使某个原生 TrinketItem.equipItem 路径绕过了右键事件，下一 tick
     * 也会把 trinkets 真库存中的重复栈移除，保证最终只保留 Curios 作为唯一装备源。
     */
    public static void purgeDuplicates(ServerPlayer player) {
        if (player == null) return;
        Object component = TrinketsApiAccess.getComponent(player);
        if (component == null) return;

        List<PendingMigration> pending = new ArrayList<>();
        CuriosTrinketsMirror.SUPPRESS_MIRROR.set(true);
        try {
            TrinketsApiAccess.forEachEquipped(component, (slotRef, stack) -> {
                if (stack.isEmpty()) return;
                String slotId = TrinketsApiAccess.slotIdOfRef(slotRef);
                if (slotId == null) return;
                pending.add(new PendingMigration(slotRef, stack.copy(), slotId));
            });
        } finally {
            CuriosTrinketsMirror.SUPPRESS_MIRROR.set(false);
        }

        int removed = 0;
        for (PendingMigration pm : pending) {
            if (!hasEquivalentInCurios(player, pm.stack)) continue;
            TrinketsApiAccess.setStackAt(pm.slotRef, ItemStack.EMPTY);
            removed++;
        }
        if (removed > 0) {
            CurioTrinketBridge.LOGGER.debug("[Migrator] {}: 已清理 {} 个 trinkets 重复副本",
                    player.getGameProfile().getName(), removed);
        }
    }

    /**
     * 对单个玩家执行一次迁移扫描。
     */
    public static void migrate(ServerPlayer player) {
        if (player == null) return;
        // 直接调用 TrinketsApi.getTrinketComponent（TrinketsApiMixin 已从 mixins.json 移除，不再需要绕过）
        Object component = TrinketsApiAccess.getComponent(player);
        if (component == null) {
            CurioTrinketBridge.LOGGER.debug("[Migrator] {}: 未拿到 TrinketComponent，跳过",
                    player.getGameProfile().getName());
            return;
        }

        // 第一遍：只收集真实 trinkets 库存中非空物品。
        // 扫描期间临时关闭 Curios 镜像，避免把已经在 Curios 的物品误判为“待迁移源”。
        List<PendingMigration> pending = new ArrayList<>();
        CuriosTrinketsMirror.SUPPRESS_MIRROR.set(true);
        try {
            TrinketsApiAccess.forEachEquipped(component, (slotRef, stack) -> {
                if (stack.isEmpty()) return;
                String slotId = TrinketsApiAccess.slotIdOfRef(slotRef);
                if (slotId == null) return;
                pending.add(new PendingMigration(slotRef, stack.copy(), slotId));
            });
        } finally {
            CuriosTrinketsMirror.SUPPRESS_MIRROR.set(false);
        }

        if (pending.isEmpty()) return;
        CurioTrinketBridge.LOGGER.debug("[Migrator] {}: 待迁移 {} 件",
                player.getGameProfile().getName(), pending.size());

        boolean anyMoved = false;
        for (PendingMigration pm : pending) {
            ItemStack moving = pm.stack;
            if (moving.isEmpty()) continue;

            // 1) 尝试 Curios 合法槽（含 ICurio.canEquip 校验，会触发 trinket 自身条件判定如形态限制）
            ItemStack remaining = tryEquipToCurios(player, moving);
            // 2) 主背包
            if (!remaining.isEmpty() && player.getInventory().add(remaining)) {
                remaining = ItemStack.EMPTY;
            }
            // 3) 仍未放下：drop 到地上
            if (!remaining.isEmpty()) {
                player.drop(remaining, false);
            }

            // 清空 trinkets 端原槽位
            TrinketsApiAccess.setStackAt(pm.slotRef, ItemStack.EMPTY);
            anyMoved = true;
            CurioTrinketBridge.LOGGER.debug("[Migrator] {}: 已从 trinkets {} 迁移 {}",
                    player.getGameProfile().getName(), pm.trinketSlotId, pm.stack);
        }

        if (anyMoved) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ARMOR_EQUIP_GENERIC, SoundSource.PLAYERS, 0.6f, 1.2f);
        }
    }

    /**
     * 尝试把整个 stack 装入 Curios 中合法的空槽。
     * 返回剩余未装入部分（可能是 EMPTY 或部分残余）。
     * 每个候选槽位会调用 ICurio.canEquip(SlotContext) 检查物品自身条件（如形态限制）。
     */
    private static ItemStack tryEquipToCurios(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        Set<String> validSlots = TrinketSlotResolver.getValidCuriosSlots(stack.getItem());
        if (validSlots.isEmpty()) return stack;

        ItemStack[] holder = { stack.copy() };
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
                if (holder[0].isEmpty()) return;
                String slotId = entry.getKey();
                if (!validSlots.contains(slotId)) continue;
                IDynamicStackHandler stacks = entry.getValue().getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    if (holder[0].isEmpty()) return;
                    if (!stacks.getStackInSlot(i).isEmpty()) continue;
                    // 在装入前用 Curios canEquip 校验物品是否能装到该槽位的该 index
                    if (!canEquipTo(player, holder[0], slotId, i)) continue;
                    int max = Math.min(holder[0].getCount(), holder[0].getMaxStackSize());
                    ItemStack put = holder[0].split(max);
                    stacks.setStackInSlot(i, put);
                }
            }
        });
        return holder[0];
    }

    private static boolean hasEquivalentInCurios(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return false;
        final boolean[] found = {false};
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
                if (found[0]) return;
                IDynamicStackHandler stacks = entry.getValue().getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack equipped = stacks.getStackInSlot(i);
                    if (!equipped.isEmpty() && ItemStack.isSameItemSameTags(equipped, stack)) {
                        found[0] = true;
                        return;
                    }
                }
            }
        });
        return found[0];
    }

    /**
     * 检查物品是否能装入指定的 Curios 槽位。
     * 两道门：
     * 1) {@link TrinketSlotResolver#canEquipInSlot}——标签匹配（slot/group 合法性）
     * 2) 反射调用 trinket 物品自身的 canEquip——触发形态等定制条件。
     * 反射不可用时仅依赖标签校验，避免误拒。
     */
    public static boolean canEquipTo(ServerPlayer player, ItemStack stack, String slotId, int index) {
        // 1) 标签校验
        if (!TrinketSlotResolver.canEquipInSlot(stack.getItem(), slotId)) return false;
        // 2) 反射调 trinket.canEquip
        try {
            Method m = TrinketDetector.getCanEquipMethod();
            if (m == null) return true;
            Object handler = TrinketDetector.getTrinketHandler(stack.getItem());
            if (handler == null) handler = stack.getItem();
            Constructor<?> ctor = TrinketDetector.getSlotReferenceConstructor();
            if (ctor == null) return true;
            String trinketSlotId = TrinketSlotResolver.toTrinketSlotId(stack.getItem(), slotId);
            Object inv = FakeTrinketInventory.getForTrinketSlotId(trinketSlotId, index + 1);
            if (inv == null) return true;
            Object slotRef = ctor.newInstance(inv, index);
            Object result = m.invoke(handler, stack, slotRef, player);
            return !(result instanceof Boolean b) || b;
        } catch (Throwable t) {
            CurioTrinketBridge.LOGGER.debug("[Migrator] canEquip 反射异常: {}", t.toString());
            return true; // 兑底放行，避免误拒
        }
    }
}
