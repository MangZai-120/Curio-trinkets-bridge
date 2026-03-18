package com.mangzai.curiotrinketbridge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 禁用 TrinketInventory 的 tick 处理。
 *
 * 即使 Trinkets 组件因某种原因仍然存在（如从存档加载），
 * 此 Mixin 也会阻止 TrinketInventory 执行 tick 逻辑，
 * 防止 Trinkets 对已装备物品执行其自身的 tick / 属性修改 / 状态更新。
 *
 * 所有 tick 逻辑由 Curios 系统通过 TrinketCurioAdapter.curioTick() 接管。
 *
 * 使用 @Pseudo 注解，Trinkets 未安装时自动跳过。
 */
@Pseudo
@Mixin(targets = "dev.emi.trinkets.api.TrinketInventory", remap = false)
public abstract class TrinketInventoryMixin {

    /**
     * 取消 TrinketInventory 的 tick 方法，阻止 Trinkets 端的自行处理
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cancelTick(CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * 取消 update 方法（如果存在），阻止库存状态同步
     */
    @Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 0)
    private void cancelUpdate(CallbackInfo ci) {
        ci.cancel();
    }
}
