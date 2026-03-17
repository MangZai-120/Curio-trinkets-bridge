package com.mangzai.curiotrinketbridge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁用 Trinkets 的整个 UI 渲染和交互系统
 * 当桥接模组加载时，Trinkets 的库存面板、槽位绘制、点击检测全部被抑制，
 * 玩家只通过 Curios 的 UI 来管理饰品。
 *
 * 使用 @Pseudo 注解，当 Trinkets 未安装时此 Mixin 自动跳过。
 */
@Pseudo
@Mixin(targets = "dev.emi.trinkets.TrinketScreenManager", remap = false)
public abstract class TrinketScreenManagerMixin {

    /**
     * 取消 UI 初始化，阻止 Trinkets 接管库存屏幕
     */
    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private static void cancelInit(CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * 取消活跃饰品组的 UI 绘制
     */
    @Inject(method = "drawActiveGroup", at = @At("HEAD"), cancellable = true)
    private static void cancelDrawActiveGroup(CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * 取消额外饰品组面板的 UI 绘制
     */
    @Inject(method = "drawExtraGroups", at = @At("HEAD"), cancellable = true)
    private static void cancelDrawExtraGroups(CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * 阻止 Trinkets 拦截库存屏幕内的点击事件，始终返回 false
     */
    @Inject(method = "isClickInsideTrinketBounds", at = @At("HEAD"), cancellable = true)
    private static void cancelClickBounds(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    /**
     * 取消 UI 状态更新（鼠标悬停、活跃组切换等）
     */
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private static void cancelUpdate(CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * 取消 tick 更新（快速移动计时器等）
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void cancelTick(CallbackInfo ci) {
        ci.cancel();
    }
}
