package cc.keran.itemcd.hook;

import cc.keran.itemcd.ItemCD;
import cc.keran.itemcd.config.MatchResult;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * CrackShot 官方事件拦截钩子 —— 阻止 CrackShot / CrackShotPlus 开火的最终方案。
 *
 * <h3>为什么事件层与协议层都拦不住 CrackShot（v1.4.0 反编译结论）</h3>
 * <ul>
 *   <li>CrackShot 不检查 {@code PlayerInteractEvent.isCancelled()}，
 *       所以 {@code setCancelled(true)} 无效；</li>
 *   <li>CrackShot 通过 {@code player.getItemInHand()} 直接读背包（已反编译确认：
 *       {@code CSDirector.OnPlayerInteract} 中调用
 *       {@code Player.getItemInHand()}），既不读 {@code event.getItem()}，
 *       所以改写事件物品字段同样无效；</li>
 *   <li>全自动武器（Fully_Automatic 模块）首次点击后由内部定时器持续开火，
 *       协议层丢包只能拦下第一次点击，后续连发照常打出。</li>
 * </ul>
 *
 * <h3>官方事件是正解</h3>
 * <p>反编译 {@code CSMinion} 确认：CrackShot 每次开火前都会派发
 * {@code com.shampaggon.crackshot.events.WeaponPrepareShootEvent}，
 * 并检查其 {@code isCancelled()} 后才调用 {@code fireProjectile}。
 * 字节码：{@code callEvent -> isCancelled -> ifne return -> fireProjectile}。
 * 因此监听该事件并取消，即可拦截<b>全部</b>射击路径，包括全自动连发。</p>
 *
 * <h3>软依赖隔离</h3>
 * <p>本类不引用任何 CrackShot 类型；全部相关代码隔离在 {@link CrackShotBridge}。
 * 没有 CrackShot 的服务器不会触发 NoClassDefFoundError。</p>
 */
public class CrackShotHook {

    /** 桥接接口：隔离 CrackShot 类型 */
    public interface Bridge {
        void unregister();
    }

    private final ItemCD plugin;
    private Bridge bridge = null;
    /** 额外注册的监听器（如 CSP 二次瞄准），重载/卸载时需要一并注销 */
    private final java.util.List<Listener> extraListeners = new java.util.ArrayList<>();

    public CrackShotHook(ItemCD plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return bridge != null;
    }

    /**
     * 装配 CrackShot 官方事件拦截。
     *
     * @return true = 已启用；false = 未启用（缺 CrackShot 或配置关闭）
     */
    public boolean setup() {
        disable();

        Plugin cs = Bukkit.getPluginManager().getPlugin("CrackShot");
        if (cs == null) {
            plugin.log("&e未检测到 CrackShot &7- 官方事件拦截不可用（CrackShot 枪械将无法被拦截）。");
            return false;
        }
        if (!cs.isEnabled()) {
            plugin.log("&eCrackShot 存在但未启用 - 官方事件拦截不可用。");
            return false;
        }

        try {
            // 直到这一步才会加载 CrackShot 的类
            bridge = new CrackShotBridge(this, plugin);
        } catch (Throwable t) {
            plugin.log("&cCrackShot 事件拦截注册失败: " + t.getMessage());
            bridge = null;
            return false;
        }

        // 附加：CrackShotPlus 二次瞄准（反射动态注册，无 CSP 依赖）
        Listener cspListener = CrackShotPlusBridge.tryRegister(plugin, this);
        if (cspListener != null) {
            extraListeners.add(cspListener);
        }

        plugin.log("&aCrackShot &7官方事件拦截已启用 - 冷却中的枪械（含全自动连发）无法开火，也无法瞄准。");
        return true;
    }

    /** 注销（重载 / 卸载时调用） */
    public void disable() {
        if (bridge != null) {
            try {
                bridge.unregister();
            } catch (Throwable ignored) {
                // CrackShot 可能已先卸载
            }
            bridge = null;
        }
        for (Listener extra : extraListeners) {
            try {
                HandlerList.unregisterAll(extra);
            } catch (Throwable ignored) {
                // 插件可能已先卸载
            }
        }
        extraListeners.clear();
    }

    /**
     * 判定是否应取消本次射击（由桥接层回调，纯 Bukkit API）。
     *
     * <p>射击拦截只要求「冷却中」：无论该规则是否设置 block-scope，
     * 冷却中的枪械一律不允许开火。</p>
     *
     * @param player      射击者
     * @param weaponTitle CrackShot 武器名（来自事件，仅用于日志）
     * @return true = 取消射击
     */
    boolean shouldCancelShoot(Player player, String weaponTitle) {
        if (!isCooling(player)) return false;

        ItemStack item = player.getInventory().getItemInMainHand();
        Material material = item.getType();
        int remain = plugin.getCooldownRegistry().remaining(player, material);
        plugin.debug("CS-BLOCK " + weaponTitle + " : " + material
                + " 冷却中，剩余 " + remain + "t，已取消射击");

        plugin.sendCooldownFeedback(player, item);
        return true;
    }

    /**
     * 判定是否应取消本次瞄准（开镜 / 收镜，由桥接层回调）。
     *
     * <p>瞄准拦截要求<b>冷却中 且 命中的规则设置了 {@code block-scope: true}</b>。
     * 未设置或为 false 时，冷却中仍允许开镜（仅禁止射击）。</p>
     *
     * @param player      操作者
     * @param weaponTitle CrackShot 武器名（来自事件，仅用于日志）
     * @return true = 取消瞄准
     */
    boolean shouldCancelScope(Player player, String weaponTitle) {
        if (player == null || !player.isOnline()) return false;
        if (player.hasPermission("itemcd.bypass")) return false;
        if (!plugin.getConfigManager().isBlockInteraction()) return false;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return false;

        Material material = item.getType();
        if (!plugin.getCooldownRegistry().isCooling(player, material)) return false;

        // 命中规则必须开启 block-scope 才禁止瞄准
        MatchResult result = plugin.findMatch(player, item);
        if (result == null || !result.getRule().isBlockScope()) return false;

        int remain = plugin.getCooldownRegistry().remaining(player, material);
        plugin.debug("CS-BLOCK-SCOPE " + weaponTitle + " : " + material
                + " 冷却中(剩余 " + remain + "t)，规则 "
                + result.getGroup().getId() + "/" + result.getRule().getId()
                + " 启用了 block-scope，已取消瞄准");

        plugin.sendCooldownFeedback(player, item);
        return true;
    }

    /** 通用前置检查：在线、非 bypass、开启拦截、主手有物品且处于冷却 */
    private boolean isCooling(Player player) {
        if (player == null || !player.isOnline()) return false;
        if (player.hasPermission("itemcd.bypass")) return false;
        if (!plugin.getConfigManager().isBlockInteraction()) return false;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return false;

        return plugin.getCooldownRegistry().isCooling(player, item.getType());
    }
}
