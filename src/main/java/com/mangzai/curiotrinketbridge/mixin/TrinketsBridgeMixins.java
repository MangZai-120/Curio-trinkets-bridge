package com.mangzai.curiotrinketbridge.mixin;

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
 * <p>本类只是一个命名空间，所有 Mixin 以静态嵌套类形式存在，便于集中管理。
 * 三个 Mixin 都使用 {@code @Pseudo} 注解，Trinkets 未安装时自动跳过。
 *
 * <ul>
 *   <li>{@link TrinketsApiMixin} - 公共端：使 TrinketsApi.getTrinketComponent 始终返回空</li>
 *   <li>{@link TrinketInventoryMixin} - 公共端：禁用 TrinketInventory 的 tick / update</li>
 *   <li>{@link TrinketScreenManagerMixin} - 客户端：禁用 Trinkets 的 UI 渲染与点击</li>
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
}
