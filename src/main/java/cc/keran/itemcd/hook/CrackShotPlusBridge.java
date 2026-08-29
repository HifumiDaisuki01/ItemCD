package cc.keran.itemcd.hook;

import cc.keran.itemcd.ItemCD;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;

/**
 * CrackShotPlus 二次瞄准（SecondScope）拦截。
 *
 * <p>CSP 的瞄准除 CrackShot 的 {@code WeaponScopeEvent} 外，
 * 还提供独立的二次瞄准事件 {@code WeaponSecondScopeEvent}。
 * 本类通过<b>反射动态注册</b>该事件，无需编译期依赖 CSP jar，
 * 并自动探测多个可能的包名；找不到时静默跳过（主瞄准仍由
 * {@code WeaponScopeEvent} 拦截）。</p>
 *
 * <p>类本身不含任何 CSP 类型引用，即使服务器没有 CSP 也不会触发类加载错误。</p>
 */
public final class CrackShotPlusBridge {

    /** CSP 各版本中 WeaponSecondScopeEvent 的可能包名 */
    private static final String[] CANDIDATE_CLASSES = {
            "me.DeeCaaD.CrackShotPlus.Events.WeaponSecondScopeEvent",
            "me.DeeCaaD.CrackShotPlusV2.Events.WeaponSecondScopeEvent",
            "me.DeeCaaD.CrackShotPlusV2.Events.WeaponSecondScopeEvent"
    };

    private CrackShotPlusBridge() {
    }

    /**
     * 尝试注册二次瞄准拦截。
     *
     * @return 注册成功后返回可注销的监听器持有者；失败返回 null
     */
    public static Listener tryRegister(ItemCD plugin, CrackShotHook hook) {
        for (String className : CANDIDATE_CLASSES) {
            try {
                Class<?> raw = Class.forName(className);
                if (!Event.class.isAssignableFrom(raw)) continue;

                @SuppressWarnings("unchecked")
                Class<? extends Event> eventClass = (Class<? extends Event>) raw;

                final Method getPlayer = raw.getMethod("getPlayer");
                final Method getWeaponTitle = safeGetMethod(raw, "getWeaponTitle");
                final Method setCancelled = raw.getMethod("setCancelled", boolean.class);

                Listener holder = new Listener() {
                };
                PluginManager pm = plugin.getServer().getPluginManager();
                pm.registerEvent(eventClass, holder, EventPriority.LOWEST, (listener, event) -> {
                    try {
                        Player player = (Player) getPlayer.invoke(event);
                        String title = getWeaponTitle == null ? null : (String) getWeaponTitle.invoke(event);
                        if (hook.shouldCancelScope(player, title)) {
                            setCancelled.invoke(event, true);
                        }
                    } catch (Throwable t) {
                        plugin.debug("CSP 二次瞄准事件处理异常: " + t.getMessage());
                    }
                }, plugin);

                plugin.log("&aCrackShotPlus &7二次瞄准拦截已启用 (via " + className + ")");
                return holder;
            } catch (ClassNotFoundException ignored) {
                // 尝试下一个候选包名
            } catch (Throwable t) {
                plugin.debug("CSP 注册失败 " + className + ": " + t.getMessage());
            }
        }
        plugin.log("&e未检测到 CrackShotPlus 二次瞄准事件 - 仅拦截主瞄准（WeaponScopeEvent）。");
        return null;
    }

    private static Method safeGetMethod(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }
}
