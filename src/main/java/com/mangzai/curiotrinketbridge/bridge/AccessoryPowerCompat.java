package com.mangzai.curiotrinketbridge.bridge;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 兼容“根据 Trinkets 装备变化给玩家添加 power”的外部模组。
 *
 * <p>这类模组不一定把逻辑写在 Trinket.onEquip/onUnequip 里，可能像 SSC 一样通过
 * LivingEntity.tick + TrinketsApi 查询自行维护装备变化。桥接到 Curios 后，这里用反射调用
 * 已知的外部 power hook，避免只对某一个编译期模组硬依赖。
 */
public final class AccessoryPowerCompat {

    private static final String SSC_TRINKET_UTILS = "net.onixary.shapeShifterCurseFabric.util.TrinketUtils";
    private static volatile boolean sscChecked = false;
    private static volatile Method sscOnEquip;
    private static volatile Method sscOnUnequip;

    private AccessoryPowerCompat() {}

    public static void onEquip(ServerPlayer player, ItemStack stack) {
        invokeSsc("ApplyAccessoryPowerOnEquip", player, stack, true);
    }

    public static void onUnequip(ServerPlayer player, ItemStack stack) {
        invokeSsc("ApplyAccessoryPowerOnUnEquip", player, stack, false);
    }

    private static void invokeSsc(String methodName, ServerPlayer player, ItemStack stack, boolean equip) {
        if (player == null || stack == null || stack.isEmpty()) return;
        ensureSscHooks();
        Method method = equip ? sscOnEquip : sscOnUnequip;
        if (method == null) return;
        try {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            Object idArg = coerceIdentifier(method.getParameterTypes()[1], itemId);
            if (idArg == null) return;
            method.invoke(null, player, idArg);
        } catch (Throwable t) {
            CurioTrinketBridge.LOGGER.debug("[AccessoryPowerCompat] 调用 SSC {} 失败: {}", methodName, t.toString());
        }
    }

    private static void ensureSscHooks() {
        if (sscChecked) return;
        synchronized (AccessoryPowerCompat.class) {
            if (sscChecked) return;
            try {
                Class<?> utilClass = Class.forName(SSC_TRINKET_UTILS);
                sscOnEquip = findStaticHook(utilClass, "ApplyAccessoryPowerOnEquip");
                sscOnUnequip = findStaticHook(utilClass, "ApplyAccessoryPowerOnUnEquip");
                if (sscOnEquip != null || sscOnUnequip != null) {
                    CurioTrinketBridge.LOGGER.info("[AccessoryPowerCompat] 已启用 SSC 饰品 power 兼容 hook");
                }
            } catch (ClassNotFoundException ignored) {
                // 未安装 SSC 时静默跳过。
            } catch (Throwable t) {
                CurioTrinketBridge.LOGGER.debug("[AccessoryPowerCompat] 初始化 SSC hook 失败: {}", t.toString());
            } finally {
                sscChecked = true;
            }
        }
    }

    private static Method findStaticHook(Class<?> owner, String name) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 2) continue;
            if (!Modifier.isStatic(method.getModifiers())) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private static Object coerceIdentifier(Class<?> expectedType, ResourceLocation id) {
        if (expectedType.isInstance(id)) return id;
        try {
            Constructor<?> ctor = expectedType.getConstructor(String.class, String.class);
            return ctor.newInstance(id.getNamespace(), id.getPath());
        } catch (Throwable ignored) {}
        try {
            Method of = expectedType.getMethod("of", String.class, String.class);
            if (Modifier.isStatic(of.getModifiers())) return of.invoke(null, id.getNamespace(), id.getPath());
        } catch (Throwable ignored) {}
        try {
            Constructor<?> ctor = expectedType.getConstructor(String.class);
            return ctor.newInstance(id.toString());
        } catch (Throwable ignored) {}
        return null;
    }
}