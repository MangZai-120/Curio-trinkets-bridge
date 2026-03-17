package com.mangzai.curiotrinketbridge.event;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import com.mangzai.curiotrinketbridge.bridge.TrinketDetector;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge 事件处理器
 * 在服务器启动后进行延迟扫描，确保所有模组物品已完成注册
 */
public class BridgeEventHandler {

    private static boolean scannedLate = false;

    /**
     * 服务器启动后再次扫描，捕获在 FMLCommonSetupEvent 之后才注册的 Trinket 物品
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (scannedLate) return;
        scannedLate = true;

        if (TrinketDetector.isTrinketsLoaded()) {
            CurioTrinketBridge.LOGGER.info("[CurioTrinketBridge] 服务器启动后进行延迟扫描...");
            TrinketDetector.scanAndRegisterTrinkets();
        }
    }
}
