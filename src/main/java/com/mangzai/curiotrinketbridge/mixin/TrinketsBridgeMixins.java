package com.mangzai.curiotrinketbridge.mixin;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import com.mangzai.curiotrinketbridge.bridge.CuriosTrinketsMirror;
import com.mangzai.curiotrinketbridge.bridge.FakeTrinketInventory;
import com.mangzai.curiotrinketbridge.bridge.TrinketSlotDiscovery;
import com.mangzai.curiotrinketbridge.bridge.TrinketsApiAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Curio Trinkets Bridge 的所有 Mixin 集合。
 *
 * <p>设计：所有 Trinkets 槽位（含玩家自定义）都被映射到 Curios。
 * Trinkets 自身 UI 禁用，真实库存逐步迁移到 Curios；Trinkets 查询 API 保留并镜像 Curios，
 * 让依赖 Trinkets API 的其它模组仍能看到 Curios 中的 Trinket 物品。
 * 自定义 trinkets 槽位通过 BridgeVirtualPack 在 Curios 中创建同名槽位（{@code trinkets_<slot>}）
 * 并透传 trinkets JSON 中的 icon。
 *
 * <ul>
 *   <li>{@link TrinketInventoryMixin} - 公共端：禁用 TrinketInventory 的 tick / update</li>
 *   <li>{@link LivingEntityTrinketComponentMixin} - 公共端：把 Curios Trinket 镜像进 Trinkets 查询 API</li>
 *   <li>{@link TrinketScreenManagerMixin} - 客户端：禁用 Trinkets 的 UI 渲染与点击</li>
 *   <li>{@link SurvivalTrinketSlotMixin} - 客户端：让残留的 SurvivalTrinketSlot 完全失效</li>
 * </ul>
 */
public final class TrinketsBridgeMixins {

    private TrinketsBridgeMixins() {}

    /**
        * 旧方案保留：禁用 Trinkets API 的核心组件访问。
        *
        * <p>当前 mixins.json 不注册此 mixin，因为通用兼容需要其它模组仍能拿到 TrinketComponent，
        * 再由 LivingEntityTrinketComponentMixin 将 Curios 中的物品镜像给这些查询。
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

        @Unique
        private FakeTrinketInventory.LinkedInventory cti$linkedInventory() {
            return FakeTrinketInventory.getLinked(this);
        }

        @Inject(method = {"size", "getContainerSize"}, at = @At("HEAD"), cancellable = true, require = 0)
        private void cti$size(CallbackInfoReturnable<Integer> cir) {
            FakeTrinketInventory.LinkedInventory linked = cti$linkedInventory();
            if (linked != null) cir.setReturnValue(linked.size());
        }

        @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true, require = 0)
        private void cti$isEmpty(CallbackInfoReturnable<Boolean> cir) {
            FakeTrinketInventory.LinkedInventory linked = cti$linkedInventory();
            if (linked != null) cir.setReturnValue(linked.isEmpty());
        }

        @Inject(method = {"getStack", "getItem"}, at = @At("HEAD"), cancellable = true, require = 0)
        private void cti$getStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
            FakeTrinketInventory.LinkedInventory linked = cti$linkedInventory();
            if (linked != null) cir.setReturnValue(linked.getStack(slot));
        }

        @Inject(method = {"setStack", "setItem"}, at = @At("HEAD"), cancellable = true, require = 0)
        private void cti$setStack(int slot, ItemStack stack, CallbackInfo ci) {
            FakeTrinketInventory.LinkedInventory linked = cti$linkedInventory();
            if (linked != null) {
                linked.setStack(slot, stack);
                ci.cancel();
            }
        }

        @Inject(method = {
                "removeStack(I)Lnet/minecraft/world/item/ItemStack;",
                "removeItemNoUpdate(I)Lnet/minecraft/world/item/ItemStack;"
        }, at = @At("HEAD"), cancellable = true, require = 0)
        private void cti$removeStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
            FakeTrinketInventory.LinkedInventory linked = cti$linkedInventory();
            if (linked != null) cir.setReturnValue(linked.removeStack(slot));
        }

        @Inject(method = {
                "removeStack(II)Lnet/minecraft/world/item/ItemStack;",
                "removeItem(II)Lnet/minecraft/world/item/ItemStack;"
        }, at = @At("HEAD"), cancellable = true, require = 0)
        private void cti$removeStackAmount(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
            FakeTrinketInventory.LinkedInventory linked = cti$linkedInventory();
            if (linked != null) cir.setReturnValue(linked.removeStack(slot, amount));
        }

        @Inject(method = {"clear", "clearContent"}, at = @At("HEAD"), cancellable = true, require = 0)
        private void cti$clear(CallbackInfo ci) {
            FakeTrinketInventory.LinkedInventory linked = cti$linkedInventory();
            if (linked != null) {
                linked.clear();
                ci.cancel();
            }
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
     * 让 Accessories/cclayer 能用 Trinkets 原翻译显示桥接生成的自定义槽位名。
     * cclayer 会读取 curios.identifier.<slot> 作为 Accessories 槽位的备选翻译。
     */
    @Mixin(net.minecraft.client.resources.language.ClientLanguage.class)
    public static abstract class ClientLanguageMixin {

        @Inject(method = "loadFrom(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Z)Lnet/minecraft/client/resources/language/ClientLanguage;",
            at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableMap;copyOf(Ljava/util/Map;)Lcom/google/common/collect/ImmutableMap;", remap = false),
            remap = false,
                locals = org.spongepowered.asm.mixin.injection.callback.LocalCapture.CAPTURE_FAILSOFT)
        private static void cti$injectTrinketsSlotTranslations(net.minecraft.server.packs.resources.ResourceManager resourceManager,
                                                              java.util.List<String> filenames,
                                                              boolean defaultRightToLeft,
                                                              CallbackInfoReturnable<net.minecraft.client.resources.language.ClientLanguage> cir,
                                                              Map<String, String> translations) {
            if (translations == null) return;

            for (TrinketSlotDiscovery.DiscoveredSlot slot : TrinketSlotDiscovery.getOrScan().values()) {
                String translated = translations.get("trinkets.slot." + slot.group() + "." + slot.slot());
                if (translated == null) translated = translations.get("trinkets.slot." + slot.slot());
                if (translated == null) continue;
                translations.putIfAbsent("curios.identifier." + slot.curiosSlotId(), translated);
            }
        }
    }

    /**
        * 镜像 Curios 槽位到 Trinkets 查询 API。
     *
     * <p>SSC 的 LivingEntity.tick mixin 通过 TrinketsApi.getTrinketComponent(player).ifPresent(c -> c.forEach(...))
     * 检测装备变化并触发 accessory_power。当玩家把 Trinket 物品装在 Curios 槽位时，
     * 真实 trinkets 库存为空，SSC 看不到这些物品，accessory_power 不会触发。
     *
        * <p>本 mixin 覆盖 forEach / isEquipped / getInventory 三类常见查询面，兼容更多
        * “依据饰品添加 power / 渲染 / 执行动作”的 Trinkets 生态模组。
     */
    @Pseudo
    @Mixin(targets = "dev.emi.trinkets.api.LivingEntityTrinketComponent", remap = false)
    public static abstract class LivingEntityTrinketComponentMixin {

        @Shadow(remap = false) public LivingEntity entity;

        @Inject(method = "forEach", at = @At("HEAD"), require = 0)
        private void cti$suppressInventoryMirrorDuringForEach(BiConsumer consumer, CallbackInfo ci) {
            CuriosTrinketsMirror.SUPPRESS_INVENTORY_MIRROR.set(true);
        }

        @Inject(method = "forEach", at = @At("TAIL"), require = 0)
        private void cti$mirrorCuriosToTrinkets(BiConsumer consumer, CallbackInfo ci) {
            CuriosTrinketsMirror.SUPPRESS_INVENTORY_MIRROR.set(false);
            LivingEntity living = this.entity != null ? this.entity : CuriosTrinketsMirror.resolveEntity(this);
            CuriosTrinketsMirror.forEachMirrored(living, (slotRef, stack) -> {
                try {
                    //noinspection unchecked
                    consumer.accept(slotRef, stack);
                } catch (Throwable inner) {
                    CurioTrinketBridge.LOGGER.debug("[mirror] consumer.accept 失败: {}", inner.toString());
                }
            });
        }

        @Inject(method = "isEquipped", at = @At("RETURN"), cancellable = true, require = 0)
        private void cti$mirrorCuriosIsEquipped(Predicate<ItemStack> predicate, CallbackInfoReturnable<Boolean> cir) {
            if (Boolean.TRUE.equals(cir.getReturnValue())) return;
            LivingEntity living = this.entity != null ? this.entity : CuriosTrinketsMirror.resolveEntity(this);
            if (CuriosTrinketsMirror.anyMirroredMatches(living, predicate)) {
                cir.setReturnValue(true);
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        @Inject(method = "getInventory", at = @At("RETURN"), cancellable = true, require = 0)
        private void cti$mirrorCuriosInventory(CallbackInfoReturnable<Map> cir) {
            LivingEntity living = this.entity != null ? this.entity : CuriosTrinketsMirror.resolveEntity(this);
            cir.setReturnValue(CuriosTrinketsMirror.mirrorInventory(living, cir.getReturnValue()));
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

        @Inject(method = "equipItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z",
                at = @At("HEAD"), cancellable = true, require = 0)
        private static void cti$cancelEquipItemLiving(net.minecraft.world.entity.LivingEntity user,
                                                      net.minecraft.world.item.ItemStack stack,
                                                      CallbackInfoReturnable<Boolean> cir) {
            cir.setReturnValue(false);
        }

        @Inject(method = "equipItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Z",
                at = @At("HEAD"), cancellable = true, require = 0)
        private static void cti$cancelEquipItemPlayer(net.minecraft.world.entity.player.Player user,
                                                      net.minecraft.world.item.ItemStack stack,
                                                      CallbackInfoReturnable<Boolean> cir) {
            cir.setReturnValue(false);
        }
    }
}
