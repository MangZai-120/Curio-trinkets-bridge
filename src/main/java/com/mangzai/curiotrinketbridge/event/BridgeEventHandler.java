package com.mangzai.curiotrinketbridge.event;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import com.mangzai.curiotrinketbridge.bridge.TrinketDetector;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.Map;

/**
 * Forge 事件处理器
 * 1. 服务器启动后延迟扫描 Trinket 物品
 * 2. 右键点击 Trinket 物品时装备到 Curios 饰品栏（因 Trinkets 组件被禁用后原右键装备失效）
 */
public class BridgeEventHandler {

    private static volatile boolean scannedLate = false;

    /**
     * 服务器启动后再次扫描，捕获在 FMLCommonSetupEvent 之后才注册的 Trinket 物品
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (scannedLate) return;
        scannedLate = true;

        if (TrinketDetector.isTrinketsLoaded()) {
            CurioTrinketBridge.LOGGER.info("[CurioTrinketBridge] 服务器启动后进行延迟扫描...");
            TrinketDetector.scanAndRegisterTrinkets();
        }
    }

    /**
     * 拦截 Trinket 物品的右键使用，将其装备到 Curios 饰品栏。
     * 高优先级确保在 TrinketItem.use()（尝试装入已禁用的 Trinkets 库存）之前执行。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;

        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !TrinketDetector.isTrinket(stack.getItem())) return;

        // 尝试装备到第一个可用的 Curios 饰品槽位
        boolean[] equipped = {false};
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
                if (equipped[0]) return;

                String slotId = entry.getKey();
                IDynamicStackHandler stacks = entry.getValue().getStacks();

                for (int i = 0; i < stacks.getSlots(); i++) {
                    if (!stacks.getStackInSlot(i).isEmpty()) continue;

                    // 通过 ICurio 能力检查是否允许装入此槽位
                    SlotContext ctx = new SlotContext(slotId, player, i, false, true);
                    ICurio curio = stack.getCapability(CuriosCapability.ITEM).resolve().orElse(null);

                    if (curio != null && curio.canEquip(ctx)) {
                        stacks.setStackInSlot(i, stack.split(1));
                        equipped[0] = true;
                        return;
                    }
                }
            }
        });

        if (equipped[0]) {
            // 播放装备音效
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ARMOR_EQUIP_GENERIC, SoundSource.PLAYERS, 1.0f, 1.0f);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(false));
        }
    }
}
