package com.mangzai.curiotrinketbridge.mixin;

import com.mangzai.curiotrinketbridge.bridge.TrinketsApiAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

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
}
