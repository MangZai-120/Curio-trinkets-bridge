package com.mangzai.curiotrinketbridge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 在模组加载阶段扫描所有已加载的 mod jar 中
 * {@code data/<namespace>/trinkets/slots/<group>/<slot>.json} 形式的 Trinkets 槽位定义。
 *
 * <p>为每个发现的槽位记录 group / slot 名以及来自 JSON 的 icon、order、amount。
 * 这些数据稍后被 {@code BridgeVirtualPack} 转换为等价的 Curios 槽位定义。
 *
 * <p>扫描在 {@code AddPackFindersEvent} 触发前的同步上下文中完成，扫描结果在两端都一致。
 *
 * <p>限制：仅扫描 mod jar；存档级数据包定义的 Trinkets 槽位不会被自动桥接。
 */
public final class TrinketSlotDiscovery {

    public record DiscoveredSlot(
            String group,
            String slot,
            String trinketSlotId,   // 例如 "hand/ring"
            String curiosSlotId,    // 例如 "trinkets_ring"
            String icon,            // 可能为 null
            int order,
            int amount
    ) {}

    private static final Gson GSON = new Gson();
    private static final Map<String, DiscoveredSlot> SLOTS = new LinkedHashMap<>();
    private static final AtomicBoolean SCANNED = new AtomicBoolean(false);

    private TrinketSlotDiscovery() {}

    /** 返回所有已发现的 Trinkets 槽位（懒扫描，线程安全）。 */
    public static Map<String, DiscoveredSlot> getOrScan() {
        if (SCANNED.compareAndSet(false, true)) {
            try {
                scan();
            } catch (Throwable t) {
                CurioTrinketBridge.LOGGER.warn("[TrinketSlotDiscovery] 扫描失败: {}", t.toString());
            }
        }
        synchronized (SLOTS) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(SLOTS));
        }
    }

    private static void scan() {
        synchronized (SLOTS) {
            SLOTS.clear();
            int total = 0;
            for (IModFileInfo info : ModList.get().getModFiles()) {
                IModFile file = info.getFile();
                if (file == null) continue;
                Path data;
                try {
                    data = file.findResource("data");
                } catch (Throwable t) {
                    continue;
                }
                if (data == null || !Files.isDirectory(data)) continue;

                try (Stream<Path> namespaces = Files.list(data)) {
                    Iterator<Path> it = namespaces.iterator();
                    while (it.hasNext()) {
                        Path ns = it.next();
                        if (!Files.isDirectory(ns)) continue;
                        Path slotsDir = ns.resolve("trinkets").resolve("slots");
                        if (!Files.isDirectory(slotsDir)) continue;
                        total += processSlotsDir(slotsDir);
                    }
                } catch (Exception e) {
                    CurioTrinketBridge.LOGGER.debug("[TrinketSlotDiscovery] 扫描 {} 失败: {}", data, e.toString());
                }
            }
            CurioTrinketBridge.LOGGER.info("[TrinketSlotDiscovery] 共发现 {} 个 Trinkets 槽位 JSON，唯一桥接槽位 {} 个",
                    total, SLOTS.size());
        }
    }

    private static int processSlotsDir(Path slotsDir) {
        final int[] count = {0};
        try (Stream<Path> walk = Files.walk(slotsDir, 4)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(p -> {
                    if (registerSlotFile(slotsDir, p)) count[0]++;
                });
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("[TrinketSlotDiscovery] 遍历 {} 失败: {}", slotsDir, e.toString());
        }
        return count[0];
    }

    private static boolean registerSlotFile(Path slotsDir, Path file) {
        Path rel;
        try {
            rel = slotsDir.relativize(file);
        } catch (Exception e) {
            return false;
        }
        // 期望路径形式：<group>/<slot>.json
        if (rel.getNameCount() < 2) return false;
        String group = rel.getName(0).toString();
        String slotFile = rel.getName(1).toString();
        if (!slotFile.endsWith(".json")) return false;
        String slot = slotFile.substring(0, slotFile.length() - 5);

        String trinketSlotId = group + "/" + slot;
        String curiosSlotId = "trinkets_" + slot;

        // 同名（如 hand/ring 与 offhand/ring）只取首个，避免重复 Curios 槽位
        if (SLOTS.containsKey(curiosSlotId)) {
            // 已存在则只补充 SlotMapper 用的 trinketSlotId 多对一映射，由调用方 (SlotMapper) 处理
            return false;
        }

        String icon = null;
        int order = 100;
        int amount = 1;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json != null) {
                if (json.has("icon")) icon = json.get("icon").getAsString();
                if (json.has("order")) order = json.get("order").getAsInt();
                if (json.has("amount")) amount = json.get("amount").getAsInt();
            }
        } catch (Exception ignore) {}

        SLOTS.put(curiosSlotId, new DiscoveredSlot(group, slot, trinketSlotId, curiosSlotId, icon, order, amount));
        return true;
    }
}
