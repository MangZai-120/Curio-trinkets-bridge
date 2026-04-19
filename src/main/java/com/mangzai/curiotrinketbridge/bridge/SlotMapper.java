package com.mangzai.curiotrinketbridge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;

/**
 * 数据驱动的 Trinkets 槽位到 Curios 槽位映射系统。
 *
 * <p>映射通过数据包加载，路径为：
 * <pre>data/&lt;namespace&gt;/curio_trinkets_bridge/slot_mappings/*.json</pre>
 *
 * <p>JSON 格式示例：
 * <pre>
 * {
 *   "replace": false,
 *   "mappings": {
 *     "head/hat": "head",
 *     "hand/ring": "ring",
 *     "chest/necklace": "necklace"
 *   },
 *   "group_fallback": {
 *     "head": "head",
 *     "hand": "ring"
 *   }
 * }
 * </pre>
 *
 * <p>"replace": true 会清除之前加载的所有映射。默认 false（合并）。
 *
 * <p>玩家只需创建数据包中的同路径 JSON 即可覆盖或扩展映射，无需修改模组代码。
 */
public final class SlotMapper extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();

    // 当前生效的映射（可被数据包重载覆盖）
    private final Map<String, String> slotMap = new LinkedHashMap<>();
    private final Map<String, String> groupFallback = new LinkedHashMap<>();

    // 内置默认映射（数据包未加载前使用）
    private static final Map<String, String> DEFAULT_SLOT_MAP;
    private static final Map<String, String> DEFAULT_GROUP_FALLBACK;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        // 头部
        map.put("head/hat", "head");
        map.put("head/face", "face");
        map.put("head/mask", "face");
        map.put("head/crown", "head");
        // 胸部
        map.put("chest/back", "back");
        map.put("chest/cape", "cape");
        map.put("chest/necklace", "necklace");
        map.put("chest/pendant", "necklace");
        map.put("chest/amulet", "necklace");
        // 手部
        map.put("hand/ring", "ring");
        map.put("hand/glove", "hands");
        map.put("hand/bracelet", "hands");
        // 副手
        map.put("offhand/ring", "ring");
        map.put("offhand/glove", "hands");
        map.put("offhand/shield", "hands");
        // 腿部
        map.put("legs/belt", "belt");
        map.put("legs/charm", "charm");
        // 脚部
        map.put("feet/aglet", "feet");
        map.put("feet/shoes", "feet");
        map.put("feet/boots", "feet");
        DEFAULT_SLOT_MAP = Collections.unmodifiableMap(map);

        Map<String, String> groupMap = new LinkedHashMap<>();
        groupMap.put("head", "head");
        groupMap.put("chest", "necklace");
        groupMap.put("hand", "ring");
        groupMap.put("offhand", "ring");
        groupMap.put("legs", "belt");
        groupMap.put("feet", "feet");
        DEFAULT_GROUP_FALLBACK = Collections.unmodifiableMap(groupMap);
    }

    // INSTANCE 必须在 static 块之后声明，确保 DEFAULT_SLOT_MAP 已初始化
    public static final SlotMapper INSTANCE = new SlotMapper();

    private SlotMapper() {
        super(GSON, "curio_trinkets_bridge/slot_mappings");
        // 初始化时使用默认映射
        slotMap.putAll(DEFAULT_SLOT_MAP);
        groupFallback.putAll(DEFAULT_GROUP_FALLBACK);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager manager, ProfilerFiller profiler) {
        // 重新从默认值开始
        slotMap.clear();
        groupFallback.clear();
        slotMap.putAll(DEFAULT_SLOT_MAP);
        groupFallback.putAll(DEFAULT_GROUP_FALLBACK);

        int loaded = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            ResourceLocation id = entry.getKey();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();

                // "replace": true 时清除所有当前映射（包括默认和先前数据包的）
                if (json.has("replace") && json.get("replace").getAsBoolean()) {
                    slotMap.clear();
                    groupFallback.clear();
                    CurioTrinketBridge.LOGGER.info("[SlotMapper] {} 使用 replace 模式，已清除先前映射", id);
                }

                // 加载精确映射
                if (json.has("mappings")) {
                    JsonObject mappings = json.getAsJsonObject("mappings");
                    for (Map.Entry<String, JsonElement> m : mappings.entrySet()) {
                        slotMap.put(m.getKey(), m.getValue().getAsString());
                    }
                }

                // 加载组回退映射
                if (json.has("group_fallback")) {
                    JsonObject fallbacks = json.getAsJsonObject("group_fallback");
                    for (Map.Entry<String, JsonElement> f : fallbacks.entrySet()) {
                        groupFallback.put(f.getKey(), f.getValue().getAsString());
                    }
                }

                loaded++;
            } catch (Exception e) {
                CurioTrinketBridge.LOGGER.warn("[SlotMapper] 加载映射文件 {} 失败: {}", id, e.getMessage());
            }
        }

        CurioTrinketBridge.LOGGER.info("[SlotMapper] 数据包加载完成: {} 个文件, {} 条精确映射, {} 条组回退",
                loaded, slotMap.size(), groupFallback.size());

        // 映射变更后清空物品槽位缓存
        TrinketSlotResolver.clearCache();

        // 服务端：将最新映射广播给所有已登录玩家，保证客户端 tooltip 与 canEquip 一致
        try {
            com.mangzai.curiotrinketbridge.network.BridgeNetwork.broadcast();
        } catch (Throwable t) {
            // 在客户端单机首次构造时网络通道可能尚未初始化，忽略
            CurioTrinketBridge.LOGGER.debug("[SlotMapper] 广播 SlotMapper 失败（可能尚未注册网络）: {}", t.getMessage());
        }
    }

    /**
     * 客户端：应用来自服务端的同步包。
     * 替换本地映射为服务端权威数据，并刷新解析器缓存。
     */
    public void applyFromNetwork(Map<String, String> incomingMappings, Map<String, String> incomingGroupFallback) {
        slotMap.clear();
        groupFallback.clear();
        if (incomingMappings != null) slotMap.putAll(incomingMappings);
        if (incomingGroupFallback != null) groupFallback.putAll(incomingGroupFallback);
        TrinketSlotResolver.clearCache();
    }

    /**
     * 将 Trinkets 槽位转换为 Curios 槽位标识符
     */
    public String toCuriosSlot(String trinketSlot) {
        String exact = slotMap.get(trinketSlot);
        if (exact != null) return exact;

        int slash = trinketSlot.indexOf('/');
        if (slash > 0) {
            String group = trinketSlot.substring(0, slash);
            String fallback = groupFallback.get(group);
            if (fallback != null) return fallback;
        }

        return "charm";
    }

    /**
     * 获取当前生效的所有精确映射（只读视图）
     */
    public Map<String, String> getAllMappings() {
        return Collections.unmodifiableMap(slotMap);
    }

    /**
     * 获取当前生效的所有组回退映射（只读视图）
     */
    public Map<String, String> getGroupFallback() {
        return Collections.unmodifiableMap(groupFallback);
    }
}
