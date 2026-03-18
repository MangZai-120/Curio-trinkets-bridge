package com.mangzai.curiotrinketbridge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 禁用 Trinkets API 的核心组件访问。
 *
 * 当桥接模组加载时，TrinketsApi.getTrinketComponent() 始终返回 Optional.empty()，
 * 使得 Trinkets 系统认为实体没有任何 Trinket 组件，从而：
 * - 不会创建 / 管理 Trinket 库存
 * - 不会执行 Trinket 的 tick 处理
 * - 不会在 UI 中显示 Trinket 槽位
 * - 第三方模组调用此 API 时也不会得到 Trinket 数据
 *
 * 所有饰品管理改由 Curios 系统接管。
 *
 * 使用 @Pseudo 注解，Trinkets 未安装时自动跳过。
 */
@Pseudo
@Mixin(targets = "dev.emi.trinkets.api.TrinketsApi", remap = false)
public abstract class TrinketsApiMixin {

    /**
     * 拦截 getTrinketComponent，返回空 Optional 使整个 Trinkets 系统失效
     */
    @Inject(method = "getTrinketComponent", at = @At("HEAD"), cancellable = true)
    private static void disableTrinketComponent(CallbackInfoReturnable<Optional<?>> cir) {
        cir.setReturnValue(Optional.empty());
    }
}
