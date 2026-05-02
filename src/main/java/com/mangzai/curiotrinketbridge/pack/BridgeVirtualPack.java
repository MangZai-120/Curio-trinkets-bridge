package com.mangzai.curiotrinketbridge.pack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import com.mangzai.curiotrinketbridge.bridge.TrinketSlotDiscovery;
import com.mangzai.curiotrinketbridge.bridge.SlotMapper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 内置只读虚拟数据包：根据 {@link TrinketSlotDiscovery} 发现的 Trinkets 自定义槽位
 * 在内存中生成等价的 Curios 槽位 JSON。
 *
 * <p>仅服务端数据包类型；包优先级置于 BOTTOM，玩家可通过自定义数据包覆盖。
 *
 * <p>跳过策略：若发现的 Trinkets 槽位已在 {@link SlotMapper#DEFAULT_SLOT_MAP} 中
 * 显式映射到某个现有 Curios 槽位（如 {@code hand/ring → ring}），则不再为其生成
 * {@code trinkets_<slot>}，避免出现重复槽位。
 */
public final class BridgeVirtualPack implements PackResources {

    public static final String PACK_ID = "builtin/" + CurioTrinketBridge.MOD_ID + "_generated";

    private static final Gson GSON = new Gson();
    private static final int PACK_FORMAT = 15; // 1.20.1 数据包格式

    private final Map<ResourceLocation, byte[]> dataEntries = new HashMap<>();
    private final Set<String> dataNamespaces = new HashSet<>();

    public BridgeVirtualPack() {
        generate();
    }

    private void generate() {
        Map<String, TrinketSlotDiscovery.DiscoveredSlot> discovered = TrinketSlotDiscovery.getOrScan();
        Map<String, String> defaults = SlotMapper.DEFAULT_SLOT_MAP;
        Map<String, String> nameFallback = SlotMapper.SLOT_NAME_FALLBACK;
        int generated = 0;
        java.util.LinkedHashSet<String> generatedSlotIds = new java.util.LinkedHashSet<>();
        for (TrinketSlotDiscovery.DiscoveredSlot slot : discovered.values()) {
            // 1) 已被默认映射覆盖到既有 Curios 槽位的，跳过生成
            if (defaults.containsKey(slot.trinketSlotId())) continue;
            // 2) slot 名能命中通用兜底（如 cape→back, glove→hands）也跳过，避免重复造槽
            if (nameFallback.containsKey(slot.slot())) continue;
            byte[] payload = buildCuriosSlotJson(slot);
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("curios",
                    "curios/slots/" + slot.curiosSlotId() + ".json");
            dataEntries.put(loc, payload);
            dataNamespaces.add(loc.getNamespace());
            generatedSlotIds.add(slot.curiosSlotId());
            generated++;
        }

        // 仅定义 slot 不会出现在玩家 GUI；必须再写一份 entities/player.json 把 slot 附加到玩家
        if (!generatedSlotIds.isEmpty()) {
            byte[] entityJson = buildEntitiesJson(generatedSlotIds);
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("curios", "curios/entities/player.json");
            dataEntries.put(loc, entityJson);
            dataNamespaces.add(loc.getNamespace());
        }

        if (generated > 0) {
            CurioTrinketBridge.LOGGER.info("[BridgeVirtualPack] 已生成 {} 个 Curios 自定义槽位 JSON（含 trinkets icon 透传）并附加到玩家", generated);
        }
    }

    /**
     * 构造 Curios 的 entities 文件：把所有自动生成的槽位 ID 附加到 #minecraft:player tag。
     * 格式参考 Curios 5.x：{"entities": ["#minecraft:player"], "slots": ["slot_a", "slot_b"]}
     */
    private static byte[] buildEntitiesJson(java.util.Set<String> slotIds) {
        JsonObject json = new JsonObject();
        JsonArray entities = new JsonArray();
        entities.add("#minecraft:player");
        json.add("entities", entities);
        JsonArray slots = new JsonArray();
        for (String id : slotIds) slots.add(id);
        json.add("slots", slots);
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildCuriosSlotJson(TrinketSlotDiscovery.DiscoveredSlot slot) {
        JsonObject json = new JsonObject();
        json.addProperty("size", Math.max(1, slot.amount()));
        json.addProperty("operation", "ADD");
        json.addProperty("order", slot.order());
        json.addProperty("use_native_gui", true);
        if (slot.icon() != null && !slot.icon().isEmpty()) {
            json.addProperty("icon", slot.icon());
        }
        // 通过自定义验证器复用桥接的 trinket_tag 判定，确保 GUI 拖放校验正确
        JsonArray validators = new JsonArray();
        validators.add(CurioTrinketBridge.MOD_ID + ":trinket_tag");
        json.add("validators", validators);
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        if (path.length == 1 && "pack.mcmeta".equals(path[0])) {
            return () -> new ByteArrayInputStream(buildPackMcmeta());
        }
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.SERVER_DATA) return null;
        byte[] data = dataEntries.get(location);
        if (data == null) return null;
        return () -> new ByteArrayInputStream(data);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.SERVER_DATA) return;
        for (Map.Entry<ResourceLocation, byte[]> e : dataEntries.entrySet()) {
            ResourceLocation loc = e.getKey();
            if (!loc.getNamespace().equals(namespace)) continue;
            if (!loc.getPath().startsWith(path)) continue;
            byte[] bytes = e.getValue();
            output.accept(loc, () -> new ByteArrayInputStream(bytes));
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.SERVER_DATA ? dataNamespaces : Set.of();
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        if (serializer == PackMetadataSection.TYPE) {
            PackMetadataSection meta = new PackMetadataSection(
                    Component.literal("Curio Trinkets Bridge - generated"),
                    PACK_FORMAT);
            return (T) meta;
        }
        return null;
    }

    @Override
    public String packId() {
        return PACK_ID;
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public void close() {
        dataEntries.clear();
        dataNamespaces.clear();
    }

    private static byte[] buildPackMcmeta() {
        String mcmeta = "{\"pack\":{\"pack_format\":" + PACK_FORMAT
                + ",\"description\":\"Curio Trinkets Bridge - auto generated Curios slots\"}}";
        return mcmeta.getBytes(StandardCharsets.UTF_8);
    }
}
