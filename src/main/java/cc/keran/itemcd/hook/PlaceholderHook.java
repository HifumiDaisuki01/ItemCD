package cc.keran.itemcd.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * PlaceholderAPI 软依赖钩子。
 *
 * <p>未安装 PlaceholderAPI 时，{@link PlaceholderAPI} 类不会被加载
 * （仅在本类启用时才会触及），因此不会产生 NoClassDefFoundError。</p>
 */
public class PlaceholderHook {

    private final boolean enabled;

    public PlaceholderHook() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        this.enabled = plugin != null && plugin.isEnabled();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 解析文本中的占位符。
     *
     * @return 解析后的文本；未启用/无占位符/异常时原样返回
     */
    public String apply(Player player, String text) {
        if (!enabled || player == null || text == null) return text;
        if (text.indexOf('%') < 0) return text;
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable ignored) {
            return text;
        }
    }
}
