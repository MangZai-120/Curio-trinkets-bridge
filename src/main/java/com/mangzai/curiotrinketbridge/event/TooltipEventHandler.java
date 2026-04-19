package com.mangzai.curiotrinketbridge.event;

import com.mangzai.curiotrinketbridge.bridge.TrinketDetector;
import com.mangzai.curiotrinketbridge.bridge.TrinketSlotResolver;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Set;

/**
 * 给 Trinket 物品的 tooltip 追加 Curios 槽位说明，方便玩家辨认这是经过桥接的饰品
 * 以及它能被装入哪些 Curios 槽位。
 */
public class TooltipEventHandler {

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        Item item = stack.getItem();
        if (!TrinketDetector.isTrinket(item)) return;

        Set<String> slots = TrinketSlotResolver.getValidCuriosSlots(item);
        if (slots.isEmpty()) return;

        List<Component> tip = event.getToolTip();
        // 标题：Curios 槽位
        tip.add(Component.translatable("tooltip.curio_trinkets_bridge.slots")
                .withStyle(ChatFormatting.GOLD));
        // 列出所有可装入的槽位（用 Curios 自身的本地化键，能与 Curios UI 显示一致）
        for (String slotId : slots) {
            Component slotName = Component.translatable("curios.identifier." + slotId);
            tip.add(Component.literal("  • ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(slotName.copy().withStyle(ChatFormatting.AQUA)));
        }
    }
}
