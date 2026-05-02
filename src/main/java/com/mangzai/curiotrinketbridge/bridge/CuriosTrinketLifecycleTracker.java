package com.mangzai.curiotrinketbridge.bridge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控玩家 Curios 中的 Trinket 装备变化，并同步给外部 power 兼容 hook。
 *
 * <p>Curios 自身会处理 ICurioItem 生命周期；这里补的是“外部模组通过 Trinkets 装备变化
 * 自己加 power”的路径，例如 SSC 的 accessory_power。只在服务端执行。
 */
public final class CuriosTrinketLifecycleTracker {

    private static final Map<UUID, Map<String, ItemStack>> LAST_EQUIPPED = new ConcurrentHashMap<>();

    private CuriosTrinketLifecycleTracker() {}

    public static void tick(ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        Map<String, ItemStack> current = scan(player);
        Map<String, ItemStack> previous = LAST_EQUIPPED.getOrDefault(player.getUUID(), Map.of());

        for (Map.Entry<String, ItemStack> oldEntry : previous.entrySet()) {
            ItemStack now = current.get(oldEntry.getKey());
            if (now == null || !ItemStack.matches(now, oldEntry.getValue())) {
                AccessoryPowerCompat.onUnequip(player, oldEntry.getValue());
            }
        }

        for (Map.Entry<String, ItemStack> newEntry : current.entrySet()) {
            ItemStack old = previous.get(newEntry.getKey());
            if (old == null || !ItemStack.matches(old, newEntry.getValue())) {
                AccessoryPowerCompat.onEquip(player, newEntry.getValue());
            }
        }

        LAST_EQUIPPED.put(player.getUUID(), current);
    }

    public static void clear(ServerPlayer player) {
        if (player != null) LAST_EQUIPPED.remove(player.getUUID());
    }

    private static Map<String, ItemStack> scan(ServerPlayer player) {
        Map<String, ItemStack> current = new LinkedHashMap<>();
        ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
        if (handler == null) return current;

        for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
            String slotId = entry.getKey();
            IDynamicStackHandler stacks = entry.getValue().getStacks();
            for (int i = 0; i < stacks.getSlots(); i++) {
                ItemStack stack = stacks.getStackInSlot(i);
                if (stack.isEmpty() || !TrinketDetector.isTrinket(stack.getItem())) continue;
                current.put(slotId + "/" + i, stack.copy());
            }
        }
        return current;
    }
}