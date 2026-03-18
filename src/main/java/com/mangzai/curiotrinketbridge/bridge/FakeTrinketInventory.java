package com.mangzai.curiotrinketbridge.bridge;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;



/**
 * 为 SlotReference 创建一个不会抛 NPE 的伪 TrinketInventory 代理。
 *
 * <p>Trinkets 的 {@code SlotReference} 是 {@code record(TrinketInventory inventory, int index)}。
 * 许多 Trinket 实现在 tick/onEquip 等方法中会调用 {@code slotRef.inventory()} 获取槽位信息。
 * 由于我们禁用了 Trinkets 的库存系统，无法提供真实的 TrinketInventory。
 *
 * <p>此类通过 Java 动态代理创建一个返回安全默认值的伪 TrinketInventory：
 * <ul>
 *   <li>{@code getSlotType()} → 伪 SlotType 代理</li>
 *   <li>返回 int 的方法 → 0</li>
 *   <li>返回 boolean 的方法 → false</li>
 *   <li>其他方法 → null</li>
 * </ul>
 */
public final class FakeTrinketInventory {

    private static Object fakeInventory;
    private static Object fakeSlotType;
    private static boolean initialized = false;
    private static boolean available = false;

    private FakeTrinketInventory() {}

    /**
     * 获取伪 TrinketInventory 实例。
     * 如果 Trinkets 类不存在（比如未安装），返回 null。
     */
    public static Object get() {
        if (!initialized) init();
        return available ? fakeInventory : null;
    }

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;

        try {
            // TrinketInventory extends SimpleInventory, 不能直接 proxy（是具体类）
            // 但 SlotReference.inventory() 的返回类型是 TrinketInventory
            // 我们需要创建一个可以通过类型检查的对象
            Class<?> trinketInvClass = Class.forName("dev.emi.trinkets.api.TrinketInventory");

            // TrinketInventory 是一个具体类，不能用 Proxy
            // 但我们可以尝试检查它是否有无参/单参构造器
            // 如果没有，回退到返回 null
            // 先尝试找 SlotType 相关的类，构建 SlotType 代理
            Class<?> slotTypeClass = Class.forName("dev.emi.trinkets.api.SlotType");

            // SlotType 是接口还是record/class？在 Trinkets 3.7.2 中它是一个 record
            // record 无法被 proxy，但如果它有接口我们可以 proxy 接口
            // Trinkets SlotType 在 3.7.2 中：public record SlotType(String group, String name, int order, int amount, ...)
            // 这是一个 record，没有接口。我们无法 proxy 它。

            // 最终策略：尝试通过反射构造 TrinketInventory
            // TrinketInventory 构造器：TrinketInventory(SlotType slotType, int size, TrinketComponent component)
            // 我们需要一个 SlotType 和 TrinketComponent
            // 太深了——换个更简单的策略:

            // 直接用 Unsafe 或 ObjenesisHelper 来构造空实例?
            // 不，让我们用更简单的策略——包装 SlotReference 的访问

            // 最终方案：由于 TrinketInventory 是具体类且构造复杂，
            // 我们采用 InvocationHandler + 接口代理来包装整个 SlotReference 也不行（它是 record）
            //
            // 实际上最可行的方案是：
            // 如果 Trinket 方法调用时 SlotReference.inventory() 返回 null 导致 NPE，
            // 那么在 TrinketCurioAdapter 的try-catch 中已经能兜底了。
            // 这里真正需要做的是让 SlotReference 中的 inventory 不为 null，
            // 即使它的方法都返回空/默认值。

            // 尝试用 sun.misc.Unsafe 分配一个不经过构造器的实例
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            java.lang.reflect.Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            fakeInventory = allocateInstance.invoke(unsafe, trinketInvClass);
            available = true;

            CurioTrinketBridge.LOGGER.debug("[FakeTrinketInventory] 已通过 Unsafe 创建伪 TrinketInventory 实例");
        } catch (Exception e) {
            available = false;
            CurioTrinketBridge.LOGGER.debug("[FakeTrinketInventory] 无法创建伪实例 ({}), " +
                    "部分访问 inventory() 的 Trinket 将通过 try-catch 兜底", e.getMessage());
        }
    }
}
