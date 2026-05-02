package com.mangzai.curiotrinketbridge;

import com.mangzai.curiotrinketbridge.bridge.SlotMapper;
import com.mangzai.curiotrinketbridge.bridge.TrinketDetector;
import com.mangzai.curiotrinketbridge.bridge.TrinketSlotResolver;
import com.mangzai.curiotrinketbridge.event.BridgeEventHandler;
import com.mangzai.curiotrinketbridge.event.TooltipEventHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Curio Trinkets Bridge 主入口
 * 自动将 Trinkets (Fabric) 饰品桥接至 Curios (Forge)
 */
@Mod(CurioTrinketBridge.MOD_ID)
public class CurioTrinketBridge {

    public static final String MOD_ID = "curio_trinkets_bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @SuppressWarnings("removal")
    public CurioTrinketBridge() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        // 注册自动槽位生成数据包提供者（必须挂在 mod 事件总线上，AddPackFindersEvent 在那里触发）
        FMLJavaModLoadingContext.get().getModEventBus().register(
                com.mangzai.curiotrinketbridge.pack.BridgePackProvider.class);
        MinecraftForge.EVENT_BUS.register(BridgeEventHandler.class);
        MinecraftForge.EVENT_BUS.register(TooltipEventHandler.class);
        // 注册数据包重载监听器，使槽位映射可通过 /reload 热更新
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListener);

        // 注册自定义 Curios 槽位验证器：
        // 根据 Trinkets 物品标签判断物品是否可以装入指定的 Curios 槽位
        registerTrinketTagPredicate();

        // 客户端：注册 FMLClientSetupEvent 用于桥接 Trinket 渲染器
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                FMLJavaModLoadingContext.get().getModEventBus().addListener(
                        com.mangzai.curiotrinketbridge.client.ClientBridgeSetup::onClientSetup));
    }

    /**
     * 注册 curio_trinkets_bridge:trinket_tag 验证器。
     * 当 Curios 系统检查物品是否可以放入某个槽位时，此验证器会检查物品的 Trinkets 标签
     * 并通过 SlotMapper 映射判断该物品是否属于目标 Curios 槽位。
     */
    private void registerTrinketTagPredicate() {
        CuriosApi.registerCurioPredicate(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "trinket_tag"),
                (slotResult) -> {
                    ItemStack stack = slotResult.stack();
                    Item item = stack.getItem();
                    if (!TrinketDetector.isTrinket(item)) return false;
                    String targetSlot = slotResult.slotContext().identifier();
                    return TrinketSlotResolver.canEquipInSlot(item, targetSlot);
                }
        );
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // 注册网络通道（用于把 SlotMapper 数据从服务端同步到客户端）
            com.mangzai.curiotrinketbridge.network.BridgeNetwork.register();

            if (TrinketDetector.isTrinketsLoaded()) {
                LOGGER.info("[CurioTrinketBridge] Trinkets API 已检测到，桥接已启用");
                TrinketDetector.scanAndRegisterTrinkets();
            } else {
                LOGGER.warn("[CurioTrinketBridge] Trinkets API 未加载，桥接已禁用。请确保已安装 Sinytra Connector 和 Trinkets 模组");
            }
        });
    }

    private void onAddReloadListener(final AddReloadListenerEvent event) {
        event.addListener(SlotMapper.INSTANCE);
    }
}
