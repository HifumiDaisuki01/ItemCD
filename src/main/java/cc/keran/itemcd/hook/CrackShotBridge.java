package cc.keran.itemcd.hook;

import cc.keran.itemcd.ItemCD;
import com.shampaggon.crackshot.events.WeaponPrepareShootEvent;
import com.shampaggon.crackshot.events.WeaponPreShootEvent;
import com.shampaggon.crackshot.events.WeaponScopeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * CrackShot 桥接实现 —— 所有 CrackShot 代码隔离在这个类里。
 *
 * <p>{@link CrackShotHook} 不引用本类的任何 CrackShot 类型，因此本类只在
 * 确认 CrackShot 存在后才会被加载，保证没有 CrackShot 的服务器不崩溃。</p>
 *
 * <h3>为什么监听这两个事件就能拦住开火</h3>
 * <p>反编译 CrackShot 0.98.13 的 {@code CSMinion} 确认，每次射击流程为：</p>
 * <pre>
 *   WeaponPrepareShootEvent e = new WeaponPrepareShootEvent(player, weaponTitle);
 *   server.getPluginManager().callEvent(e);
 *   if (!e.isCancelled()) {
 *       plugin.fireProjectile(player, weaponTitle, leftClick);  // 真正开火
 *   }
 * </pre>
 * <p>所有开火路径（单发 / 三连发 / 全自动连发 / 投掷）都会经过
 * {@code WeaponPrepareShootEvent}，因此在此取消即可拦下全部射击。
 * {@code WeaponPreShootEvent} 作为第二道保险（发射弹丸前）。</p>
 */
final class CrackShotBridge implements CrackShotHook.Bridge {

    private final Listener listener;

    CrackShotBridge(CrackShotHook hook, ItemCD plugin) {
        listener = new Listener() {

            /** 射击流程最开始 —— 官方设计就是用于控制「谁能用哪些武器」 */
            @EventHandler(priority = EventPriority.LOWEST)
            public void onPrepareShoot(WeaponPrepareShootEvent event) {
                if (hook.shouldCancelShoot(event.getPlayer(), event.getWeaponTitle())) {
                    event.setCancelled(true);
                }
            }

            /** 发射弹丸之前 —— 第二道保险 */
            @EventHandler(priority = EventPriority.LOWEST)
            public void onPreShoot(WeaponPreShootEvent event) {
                if (hook.shouldCancelShoot(event.getPlayer(), event.getWeaponTitle())) {
                    event.setCancelled(true);
                }
            }

            /** 瞄准（开镜 / 收镜）—— 冷却中且规则开启 block-scope 时禁止开镜 */
            @EventHandler(priority = EventPriority.LOWEST)
            public void onScope(WeaponScopeEvent event) {
                if (hook.shouldCancelScope(event.getPlayer(), event.getWeaponTitle())) {
                    event.setCancelled(true);
                }
            }
        };

        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void unregister() {
        try {
            HandlerList.unregisterAll(listener);
        } catch (Throwable ignored) {
            // CrackShot 可能已先卸载
        }
    }
}
