package com.mangzai.curiotrinketbridge.bridge;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.*;

/**
 * 根据 Trinkets 物品标签解析物品应装备到哪个 Curios 槽位。
 *
 * <p>Trinkets 使用 item tag（如 trinkets:hand/ring）来决定物品可装入的槽位。
 * 此工具类读取这些标签并映射到对应的 Curios 槽位标识符。
 *
 * <p>映射来源于 {@link SlotMapper}（数据包驱动），当数据包重载时自动刷新。
 */
public final class TrinketSlotResolver {

    // 缓存：Item → 允许的 Curios 槽位集合
    private static final Map<Item, Set<String>> CACHE = new IdentityHashMap<>();

    // 动态生成的 tag → curios slot 映射列表
    private static volatile List<TagMapping> tagMappings = null;
    // 标记是否已经通过 SlotMapper.apply() 进行过完整的初始化
    private static volatile boolean initialized = false;

    private record TagMapping(TagKey<Item> tag, String curiosSlot, String trinketSlot) {}

    private TrinketSlotResolver() {}

    /**
     * 确保 tagMappings 已初始化。
     * 在 SlotMapper 数据包加载前（如 FMLCommonSetupEvent 期间），使用默认映射进行初始化。
     */
    private static void ensureInitialized() {
        if (tagMappings == null) {
            synchronized (TrinketSlotResolver.class) {
                if (tagMappings == null) {
                    rebuildTagMappings();
                }
            }
        }
    }

    /**
     * 根据当前 SlotMapper 中的映射重建 tag 映射列表
     */
    private static void rebuildTagMappings() {
        List<TagMapping> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : SlotMapper.INSTANCE.getAllMappings().entrySet()) {
            String trinketSlot = entry.getKey();   // 如 "hand/ring"
            String curiosSlot = entry.getValue();  // 如 "ring"
            @SuppressWarnings("deprecation")
            TagKey<Item> tag = TagKey.create(Registries.ITEM,
                    new ResourceLocation("trinkets", trinketSlot));
            list.add(new TagMapping(tag, curiosSlot, trinketSlot));
        }
        tagMappings = List.copyOf(list);
        CurioTrinketBridge.LOGGER.debug("[TrinketSlotResolver] 已重建 {} 条标签映射", list.size());
    }

    /**
     * 获取物品允许装备的 Curios 槽位集合（基于其 Trinkets 物品标签）
     *
     * @param item 要查询的物品
     * @return 允许装备的 Curios 槽位标识符集合；无标签匹配时返回 {"charm"}
     */
    public static Set<String> getValidCuriosSlots(Item item) {
        ensureInitialized();
        return CACHE.computeIfAbsent(item, i -> {
            Set<String> slots = new LinkedHashSet<>();
            try {
                for (TagMapping mapping : tagMappings) {
                    if (i.builtInRegistryHolder().is(mapping.tag)) {
                        slots.add(mapping.curiosSlot);
                        CurioTrinketBridge.LOGGER.debug("物品 {} 标签匹配: {} → curios:{}",
                                i, mapping.trinketSlot, mapping.curiosSlot);
                    }
                }
            } catch (Exception e) {
                CurioTrinketBridge.LOGGER.debug("检查物品 {} 的 Trinkets 标签失败: {}", i, e.getMessage());
            }

            if (slots.isEmpty()) {
                // 无标签匹配，放入通用 charm 槽位
                slots.add("charm");
                CurioTrinketBridge.LOGGER.debug("物品 {} 无 Trinkets 标签匹配，默认使用 charm 槽位", i);
            }
            return Collections.unmodifiableSet(slots);
        });
    }

    /**
     * 检查物品是否可以装备到指定的 Curios 槽位
     */
    public static boolean canEquipInSlot(Item item, String curiosSlotId) {
        return getValidCuriosSlots(item).contains(curiosSlotId);
    }

    /**
     * 清除缓存并重建 tag 映射（在数据包重载时由 {@link SlotMapper} 调用）
     */
    public static void clearCache() {
        CACHE.clear();
        rebuildTagMappings();
        initialized = true;
    }
}
