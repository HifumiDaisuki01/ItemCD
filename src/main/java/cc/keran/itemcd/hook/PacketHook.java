package cc.keran.itemcd.hook;

import cc.keran.itemcd.ItemCD;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议层（数据包）拦截 —— 解决 CrackShot / CrackShotPlus 拦不住开火的根本方案。
 *
 * <h3>为什么需要这一层</h3>
 * <p>Bukkit 事件层的拦截对 CrackShot 系列完全无效，原因有两条：</p>
 * <ol>
 *   <li>CrackShot 不检查 {@code event.isCancelled()}，所以 {@code setCancelled(true)} 拦不住它；</li>
 *   <li>CrackShot 通过 {@code player.getInventory().getItemInMainHand()} 识别武器，
 *       而不是 {@code event.getItem()}，所以改写事件携带的物品字段同样无效。</li>
 * </ol>
 * <p>与其在事件层跟它兜圈子，不如<b>回到更上游</b>：客户端的左右键操作在服务端会先变成
 * {@code PacketPlayInUseItem} / {@code PacketPlayInBlockDig} 等数据包，
 * 服务端解析后才派发出 {@code PlayerInteractEvent}。
 * 本类在这些包被解析<b>之前</b>就把它丢弃，于是 CrackShot 根本收不到任何事件，
 * 无论它用什么方式读取物品都无从下手。</p>
 *
 * <h3>软依赖隔离</h3>
 * <p>本类<b>刻意不引用任何 ProtocolLib 类型</b>（字段、方法签名、内部类一律不含）。
 * 全部 ProtocolLib 相关代码隔离在 {@link ProtocolLibBridge} 中，
 * 只有确认插件存在后才会被加载。否则即便服务器没装 ProtocolLib，
 * 类加载阶段也会因为解析 {@code PacketAdapter} 而抛出 NoClassDefFoundError。</p>
 */
public class PacketHook {

    /**
     * 桥接接口：隔离 ProtocolLib 类型。
     *
     * <p>接口本身不含任何 ProtocolLib 引用，因此 {@link PacketHook} 持有它
     * 不会触发 ProtocolLib 的类加载。</p>
     */
    public interface Bridge {
        void unregister();
    }

    /** 可用包类型名（纯字符串，避免引用 PacketType） */
    private static final List<String> AVAILABLE_TYPES = Collections.unmodifiableList(
            Arrays.asList("USE_ITEM", "BLOCK_PLACE", "BLOCK_DIG", "USE_ENTITY",
                    "ARM_ANIMATION", "ENTITY_ACTION", "HELD_ITEM_SLOT"));

    /** 提示节流：同一玩家的冷却提示最小间隔（毫秒），避免自动武器每包刷提示 */
    private static final long FEEDBACK_COOLDOWN_MS = 200L;

    private final ItemCD plugin;

    private Bridge bridge = null;
    private Set<String> typeNames = new LinkedHashSet<>();
    private boolean airOnly = false;

    private final Map<UUID, Long> lastFeedback = new ConcurrentHashMap<>();

    public PacketHook(ItemCD plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return bridge != null;
    }

    // ══════════════ 装配 ══════════════

    /**
     * 装配协议层拦截。
     *
     * @return true = 已启用；false = 未启用（缺 ProtocolLib 或配置关闭），将降级到事件层
     */
    public boolean setup() {
        disable();

        Plugin plib = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        if (plib == null) {
            plugin.log("&e未检测到 ProtocolLib &7- 协议层拦截不可用，仅使用事件层拦截。");
            plugin.log("&7提示: 要让 CrackShot / CrackShotPlus 的枪械真正无法开火，需安装 ProtocolLib。");
            return false;
        }
        if (!plib.isEnabled()) {
            plugin.log("&eProtocolLib 存在但未启用 - 协议层拦截不可用，仅使用事件层拦截。");
            return false;
        }

        if (!plugin.getConfigManager().isPacketBlockEnabled()) {
            plugin.log("&7检测到 ProtocolLib，但 &bpacket-block.enabled&7=false，协议层拦截未启用。");
            return false;
        }

        this.airOnly = "air-only".equalsIgnoreCase(plugin.getConfigManager().getBlockInteractionMode());

        // 解析包类型名
        Set<String> resolved = new LinkedHashSet<>();
        for (String name : plugin.getConfigManager().getPacketBlockTypes()) {
            String key = name.trim().toUpperCase();
            if (!AVAILABLE_TYPES.contains(key)) {
                plugin.log("&cpacket-block.types 中存在未知包类型: " + name + "（已忽略）");
                plugin.log("&7可用类型: " + String.join(", ", AVAILABLE_TYPES));
                continue;
            }
            resolved.add(key);
        }

        if (resolved.isEmpty()) {
            plugin.log("&epacket-block.types 为空，协议层拦截未启用。");
            return false;
        }
        this.typeNames = resolved;

        try {
            // 直到这一步才会加载 ProtocolLib 的类
            bridge = new ProtocolLibBridge(this, plugin, resolved);
        } catch (Throwable t) {
            plugin.log("&c协议层拦截注册失败，已降级为事件层拦截: " + t.getMessage());
            bridge = null;
            return false;
        }

        List<String> names = new ArrayList<>(resolved);
        Collections.sort(names);

        plugin.log("&aProtocolLib &7已接入 - 协议层拦截: &b" + String.join(", ", names));
        plugin.log("&7这是唯一能真正阻止 CrackShot / CrackShotPlus 开火的机制（包在派发成事件前即被丢弃）。");
        if (airOnly) {
            plugin.log("&7block-interaction-mode=air-only: 放置方块与挖方块的包将被放行。");
        }
        return true;
    }

    /** 注销监听器（重载 / 卸载时调用） */
    public void disable() {
        if (bridge != null) {
            try {
                bridge.unregister();
            } catch (Throwable ignored) {
                // ProtocolLib 可能已先卸载，忽略
            }
            bridge = null;
        }
        lastFeedback.clear();
    }

    // ══════════════ 拦截判定（由桥接层回调）══════════════

    /**
     * 判定是否应丢弃该包。
     *
     * <p>本方法只使用 Bukkit API，由 {@link ProtocolLibBridge} 在同步线程中回调。</p>
     *
     * @param player         操作者
     * @param packetTypeName 包类型名，如 {@code USE_ITEM}
     * @param targetingAir   该操作是否针对空气（左键包由包内坐标判定；其余恒为 true）
     * @return true = 丢弃该包，不派发事件
     */
    boolean shouldCancel(Player player, String packetTypeName, boolean targetingAir) {
        if (player == null || !player.isOnline()) return false;
        if (player.hasPermission("itemcd.bypass")) return false;

        // 防御：非主线程一律放行，交给事件层兜底，绝不冒险调用 Bukkit API
        if (!Bukkit.isPrimaryThread()) return false;

        // air-only 模式：放行对方块的操作（放置方块 / 挖方块）
        if (airOnly) {
            if ("BLOCK_PLACE".equals(packetTypeName)) return false;
            if ("BLOCK_DIG".equals(packetTypeName) && !targetingAir) return false;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return false;

        Material material = item.getType();
        if (!plugin.getCooldownRegistry().isCooling(player, material)) return false;

        int remain = plugin.getCooldownRegistry().remaining(player, material);
        plugin.debug("PKT-BLOCK " + packetTypeName + " : " + material
                + " 处于冷却，剩余 " + remain + "t（包已丢弃，不派发事件）");

        // 提示节流：自动武器点击频率极高
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastFeedback.get(id);
        if (last == null || now - last > FEEDBACK_COOLDOWN_MS) {
            lastFeedback.put(id, now);
            plugin.sendCooldownFeedback(player, item);
        }
        return true;
    }

    /** air-only 模式是否生效（供桥接层判断是否解析坐标） */
    boolean isAirOnly() {
        return airOnly;
    }

    // ══════════════ 诊断 ══════════════

    /** 当前启用的包类型名 */
    public List<String> getActiveTypeNames() {
        List<String> names = new ArrayList<>(typeNames);
        Collections.sort(names);
        return names;
    }

    /** 全部可用包类型名（配置提示用） */
    public static List<String> getAvailableTypeNames() {
        return AVAILABLE_TYPES;
    }
}
