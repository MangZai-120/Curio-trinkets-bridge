package com.mangzai.curiotrinketbridge.mixin;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import com.mangzai.curiotrinketbridge.bridge.FakeTrinketInventory;
import com.mangzai.curiotrinketbridge.bridge.TrinketDetector;
import com.mangzai.curiotrinketbridge.bridge.TrinketsApiAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Curio Trinkets Bridge 的所有 Mixin 集合。
 *
 * <p>设计：所有 Trinkets 槽位（含玩家自定义）都被映射到 Curios。
 * Trinkets 自身的 API / 库存 tick / UI 全部禁用，避免出现两套饰品系统并存。
 * 自定义 trinkets 槽位通过 BridgeVirtualPack 在 Curios 中创建同名槽位（{@code trinkets_<slot>}）
 * 并透传 trinkets JSON 中的 icon。
 *
 * <ul>
 *   <li>{@link TrinketsApiMixin} - 公共端：使 TrinketsApi.getTrinketComponent 始终返回空</li>
 *   <li>{@link TrinketInventoryMixin} - 公共端：禁用 TrinketInventory 的 tick / update</li>
 *   <li>{@link TrinketScreenManagerMixin} - 客户端：禁用 Trinkets 的 UI 渲染与点击</li>
 *   <li>{@link SurvivalTrinketSlotMixin} - 客户端：让残留的 SurvivalTrinketSlot 完全失效</li>
 * </ul>
 */
public final class TrinketsBridgeMixins {

    private TrinketsBridgeMixins() {}

    /**
     * 禁用 Trinkets API 的核心组件访问。
     * 使 Trinkets 系统认为实体没有 Trinket 组件，所有饰品管理改由 Curios 接管。
     */
    @Pseudo
    @Mixin(targets = "dev.emi.trinkets.api.TrinketsApi", remap = false)
    public static abstract class TrinketsApiMixin {

        @Inject(method = "getTrinketComponent", at = @At("HEAD"), cancellable = true)
        private static void disableTrinketComponent(CallbackInfoReturnable<Optional<?>> cir) {
            // 桥接器内部（如 TrinketsItemMigrator）需要直接访问 TrinketComponent 时，
            // 通过 TrinketsApiAccess.ALLOW_COMPONENT_ACCESS 临时放行。
            if (Boolean.TRUE.equals(TrinketsApiAccess.ALLOW_COMPONENT_ACCESS.get())) return;
            cir.setReturnValue(Optional.empty());
        }
    }

    /**
     * 禁用 TrinketInventory 的 tick 处理，防止 Trinkets 端对已装备物品执行自身逻辑。
     * 所有 tick 由 Curios 通过 TrinketCurioAdapter.curioTick() 接管。
     */
    @Pseudo
    @Mixin(targets = "dev.emi.trinkets.api.TrinketInventory", remap = false)
    public static abstract class TrinketInventoryMixin {

        @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
        private void cancelTick(CallbackInfo ci) {
            ci.cancel();
        }

        @Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 0)
        private void cancelUpdate(CallbackInfo ci) {
            ci.cancel();
        }
    }

    /**
     * 禁用 Trinkets 的整个 UI 渲染和交互系统。
     * 玩家仅通过 Curios UI 管理饰品。
     */
    @Pseudo
    @Mixin(targets = "dev.emi.trinkets.TrinketScreenManager", remap = false)
    public static abstract class TrinketScreenManagerMixin {

        @Inject(method = "init", at = @At("HEAD"), cancellable = true)
        private static void cancelInit(CallbackInfo ci) {
            ci.cancel();
        }

        @Inject(method = "drawActiveGroup", at = @At("HEAD"), cancellable = true)
        private static void cancelDrawActiveGroup(CallbackInfo ci) {
            ci.cancel();
        }

        @Inject(method = "drawExtraGroups", at = @At("HEAD"), cancellable = true)
        private static void cancelDrawExtraGroups(CallbackInfo ci) {
            ci.cancel();
        }

        @Inject(method = "isClickInsideTrinketBounds", at = @At("HEAD"), cancellable = true)
        private static void cancelClickBounds(CallbackInfoReturnable<Boolean> cir) {
            cir.setReturnValue(false);
        }

        @Inject(method = "update", at = @At("HEAD"), cancellable = true)
        private static void cancelUpdate(CallbackInfo ci) {
            ci.cancel();
        }

        @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
        private static void cancelTick(CallbackInfo ci) {
            ci.cancel();
        }
    }

    /**
     * 即便 trinkets 仍把 SurvivalTrinketSlot 注入到 PlayerScreenHandler，
     * 强制让所有此类槽位返回 false，彻底不渲染、不响应 hover/click，
     * 也不允许 quickMove 等路径塞物品进 trinkets 端。
     * 同时兼容 yarn (isEnabled / canInsert) 与 mojmap (isActive / mayPlace)。
     */
    @Pseudo
    @Mixin(targets = "dev.emi.trinkets.SurvivalTrinketSlot", remap = false)
    public static abstract class SurvivalTrinketSlotMixin {

        @Inject(method = "isEnabled", at = @At("HEAD"), cancellable = true, require = 0)
        private void disableSlotYarn(CallbackInfoReturnable<Boolean> cir) {
            cir.setReturnValue(false);
        }

        @Inject(method = "isActive", at = @At("HEAD"), cancellable = true, require = 0)
        private void disableSlotMoj(CallbackInfoReturnable<Boolean> cir) {
            cir.setReturnValue(false);
        }

        @Inject(method = "canInsert", at = @At("HEAD"), cancellable = true, require = 0)
        private void blockInsertYarn(net.minecraft.world.item.ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
            cir.setReturnValue(false);
        }

        @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true, require = 0)
        private void blockInsertMoj(net.minecraft.world.item.ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 跳过 trinkets 自身槽位在 vanilla AbstractContainerScreen.renderSlot 中的渲染。
     * 因为 vanilla 的 renderSlot 不检查 isEnabled / isActive，仅靠 SurvivalTrinketSlotMixin 无法让圆形槽位消失。
     */
    @Mixin(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class)
    public static abstract class AbstractContainerScreenSlotMixin {

        @Inject(method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;)V",
                at = @At("HEAD"), cancellable = true, remap = false)
        private void cti$cancelTrinketSlotRender(net.minecraft.client.gui.GuiGraphics gfx,
                                                 net.minecraft.world.inventory.Slot slot,
                                                 CallbackInfo ci) {
            if (slot != null && slot.getClass().getName().startsWith("dev.emi.trinkets.")) {
                ci.cancel();
            }
        }
    }

    /**
     * 镜像 Curios 槽位到 Trinkets 的 forEach 迭代。
     *
     * <p>SSC 的 LivingEntity.tick mixin 通过 TrinketsApi.getTrinketComponent(player).ifPresent(c -> c.forEach(...))
     * 检测装备变化并触发 accessory_power。当玩家把 Trinket 物品装在 Curios 槽位时，
     * 真实 trinkets 库存为空，SSC 看不到这些物品，accessory_power 不会触发。
     *
     * <p>本 mixin 在 forEach 末尾追加遍历 Curios 中的 Trinket 物品，
     * 用 FakeTrinketInventory（slotType.group="curios", name=slotId）构造 SlotReference
     * 并喂给 consumer。SSC 的差异检测以 group/name/index 为 key，因此 Curios 镜像与
     * trinkets 原生槽位的 key 不冲突，不会重复触发。
     */
    @Pseudo
    @Mixin(targets = "dev.emi.trinkets.api.LivingEntityTrinketComponent", remap = false)
    public static abstract class LivingEntityTrinketComponentMixin {

        @Inject(method = "forEach", at = @At("TAIL"), require = 0)
        private void cti$mirrorCuriosToTrinkets(BiConsumer consumer, CallbackInfo ci) {
            try {
                // 反射读取 entity 字段（LivingEntityTrinketComponent.entity 为 public）
                Field entityField;
                try {
                    entityField = this.getClass().getField("entity");
                } catch (NoSuchFieldException nsf) {
                    entityField = this.getClass().getDeclaredField("entity");
                    entityField.setAccessible(true);
                }
                Object entityObj = entityField.get(this);
                if (!(entityObj instanceof net.minecraft.world.entity.LivingEntity living)) return;

                Constructor<?> slotRefCtor = TrinketDetector.getSlotReferenceConstructor();
                if (slotRefCtor == null) return;

                net.minecraftforge.common.util.LazyOptional<top.theillusivec4.curios.api.type.capability.ICuriosItemHandler> opt =
                        CuriosApi.getCuriosInventory(living);
                top.theillusivec4.curios.api.type.capability.ICuriosItemHandler invHandler = opt.orElse(null);
                if (invHandler == null) return;

                Map<String, ICurioStacksHandler> curios = invHandler.getCurios();
                for (Map.Entry<String, ICurioStacksHandler> e : curios.entrySet()) {
                    String slotId = e.getKey();
                    IDynamicStackHandler stacks = e.getValue().getStacks();
                    int size = stacks.getSlots();
                    for (int i = 0; i < size; i++) {
                        net.minecraft.world.item.ItemStack stack = stacks.getStackInSlot(i);
                        if (stack.isEmpty()) continue;
                        if (!TrinketDetector.isTrinket(stack.getItem())) continue;

                        Object fakeInv = FakeTrinketInventory.getForSlot(slotId);
                        if (fakeInv == null) continue;

                        try {
                            Object slotRef = slotRefCtor.newInstance(fakeInv, i);
                            //noinspection unchecked
                            consumer.accept(slotRef, stack);
                        } catch (Throwable inner) {
                            CurioTrinketBridge.LOGGER.debug("[mirror] consumer.accept 失败 slot={} index={}: {}",
                                    slotId, i, inner.toString());
                        }
                    }
                }
            } catch (Throwable t) {
                CurioTrinketBridge.LOGGER.debug("[mirror] forEach 注入异常：{}", t.toString());
            }
        }
    }

    /**
     * 取消 TrinketItem.use() 的默认装备到 trinkets 库存逻辑。
     *
     * <p>所有右键装备走 BridgeEventHandler.onRightClickItem → Curios。本 mixin 防止
     * server 走 Curios + client/服务端再走 trinkets 装入造成的物品复制（用户报告的 bug3）。
     *
     * <p>返回 PASS 而非 SUCCESS，让 vanilla / Forge 后续逻辑（如丢弃 / 投掷）正常处理空操作。
     */
    @Pseudo
    @Mixin(targets = "dev.emi.trinkets.api.TrinketItem", remap = false)
    public static abstract class TrinketItemUseMixin {

        @Inject(method = "use", at = @At("HEAD"), cancellable = true, require = 0)
        private void cti$cancelTrinketUse(net.minecraft.world.level.Level level,
                                          net.minecraft.world.entity.player.Player player,
                                          net.minecraft.world.InteractionHand hand,
                                          CallbackInfoReturnable<net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack>> cir) {
            net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
            cir.setReturnValue(net.minecraft.world.InteractionResultHolder.pass(held));
        }
    }
}
