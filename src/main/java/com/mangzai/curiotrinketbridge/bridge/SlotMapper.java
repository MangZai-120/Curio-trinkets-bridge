package com.mangzai.curiotrinketbridge.bridge;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Trinkets 槽位到 Curios 槽位的映射工具
 *
 * Trinkets 使用 "group/name" 格式（如 "head/hat", "chest/necklace"）
 * Curios 使用单一标识符（如 "head", "necklace"）
 */
public final class SlotMapper {

    // Trinkets "group/name" → Curios 标识符
    private static final Map<String, String> SLOT_MAP;

    // Trinkets group name → Curios 默认槽位（当 group/name 没有精确匹配时按 group 回退）
    private static final Map<String, String> GROUP_FALLBACK;

    static {
        Map<String, String> map = new HashMap<>();
        // 头部槽位
        map.put("head/hat", "head");
        map.put("head/face", "face");
        map.put("head/mask", "face");
        map.put("head/crown", "head");
        // 胸部槽位
        map.put("chest/back", "back");
        map.put("chest/cape", "cape");
        map.put("chest/necklace", "necklace");
        map.put("chest/pendant", "necklace");
        map.put("chest/amulet", "necklace");
        // 手部槽位
        map.put("hand/ring", "ring");
        map.put("hand/glove", "hands");
        map.put("hand/bracelet", "hands");
        // 副手槽位
        map.put("offhand/ring", "ring");
        map.put("offhand/glove", "hands");
        map.put("offhand/shield", "hands");
        // 腿部槽位
        map.put("legs/belt", "belt");
        map.put("legs/charm", "charm");
        // 脚部槽位
        map.put("feet/aglet", "feet");
        map.put("feet/shoes", "feet");
        map.put("feet/boots", "feet");
        SLOT_MAP = Collections.unmodifiableMap(map);

        // Group 级别回退映射
        Map<String, String> groupMap = new HashMap<>();
        groupMap.put("head", "head");
        groupMap.put("chest", "necklace");
        groupMap.put("hand", "ring");
        groupMap.put("offhand", "ring");
        groupMap.put("legs", "belt");
        groupMap.put("feet", "feet");
        GROUP_FALLBACK = Collections.unmodifiableMap(groupMap);
    }

    private SlotMapper() {}

    /**
     * 将 Trinkets 槽位转换为 Curios 槽位标识符
     * @param trinketSlot Trinkets 格式的槽位 "group/name"
     * @return Curios 槽位标识符，未知槽位返回 "curio"
     */
    public static String toCuriosSlot(String trinketSlot) {
        // 先精确匹配 group/name
        String exact = SLOT_MAP.get(trinketSlot);
        if (exact != null) return exact;

        // 按 group 回退
        int slash = trinketSlot.indexOf('/');
        if (slash > 0) {
            String group = trinketSlot.substring(0, slash);
            String fallback = GROUP_FALLBACK.get(group);
            if (fallback != null) return fallback;
        }

        return "charm";
    }

    /**
     * 获取所有已知的槽位映射
     */
    public static Map<String, String> getAllMappings() {
        return SLOT_MAP;
    }

    /**
     * 根据 Curios 槽位标识符反向查找可能的 Trinkets 槽位
     * @param curiosSlot Curios 槽位标识符
     * @return 对应的 Trinkets 槽位 "group/name"，未找到返回 null
     */
    public static String toTrinketsSlot(String curiosSlot) {
        for (Map.Entry<String, String> entry : SLOT_MAP.entrySet()) {
            if (entry.getValue().equals(curiosSlot)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
