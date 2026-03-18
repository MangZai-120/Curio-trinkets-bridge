package com.mangzai.curiotrinketbridge;

import com.mangzai.curiotrinketbridge.bridge.SlotMapper;
import com.mangzai.curiotrinketbridge.bridge.TrinketDetector;
import com.mangzai.curiotrinketbridge.event.BridgeEventHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        MinecraftForge.EVENT_BUS.register(BridgeEventHandler.class);
        // 注册数据包重载监听器，使槽位映射可通过 /reload 热更新
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListener);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
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
