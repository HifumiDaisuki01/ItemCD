package cc.keran.itemcd.config;

import cc.keran.itemcd.hook.PlaceholderHook;
import cc.keran.itemcd.nbt.NbtMatcher;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 单条检测规则。
 *
 * <p>三类检测种类按顺序执行，未设置(为 null)的种类自动跳过；
 * 任一种类不匹配即返回 false，由调用方继续尝试下一条检测。</p>
 */
public class CheckRule {

    /** 规则 id，仅用于日志与调试 */
    private final String id;

    /** 种类1：原版材质；null = 跳过该种类 */
    private final Material material;

    /** 种类2：物品显示名（已转颜色码）；null = 跳过该种类 */
    private final String displayName;

    /** 名称匹配模式：true=模糊(包含即匹配)，false=精确(完全一致) */
    private final boolean fuzzy;

    /** 种类3：期望的 NBT 结构（可多层嵌套）；null/空 = 跳过该种类 */
    private final Map<String, Object> nbt;

    /** 各触发的冷却 tick 表达式（可含 PlaceholderAPI 变量） */
    private final Map<Trigger, String> cooldowns;

    /**
     * 冷却期间是否禁止该物品的 CrackShot 瞄准（开镜）。
     * true = 冷却时禁止瞄准；false/未设置 = 不禁止（仅禁止射击）。
     */
    private final boolean blockScope;

    public CheckRule(String id, Material material, String displayName, boolean fuzzy,
                     Map<String, Object> nbt, Map<Trigger, String> cooldowns) {
        this(id, material, displayName, fuzzy, nbt, cooldowns, false);
    }

    public CheckRule(String id, Material material, String displayName, boolean fuzzy,
                     Map<String, Object> nbt, Map<Trigger, String> cooldowns, boolean blockScope) {
        this.id = id;
        this.material = material;
        this.displayName = displayName;
        this.fuzzy = fuzzy;
        this.nbt = (nbt == null || nbt.isEmpty()) ? null : nbt;
        this.cooldowns = cooldowns == null ? new EnumMap<>(Trigger.class)
                : new EnumMap<>(cooldowns);
        this.blockScope = blockScope;
    }

    public String getId() {
        return id;
    }

    public Material getMaterial() {
        return material;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isFuzzy() {
        return fuzzy;
    }

    public Map<String, Object> getNbt() {
        return nbt == null ? null : Collections.unmodifiableMap(nbt);
    }

    /** 冷却期间是否禁止该物品的 CrackShot 瞄准（开镜） */
    public boolean isBlockScope() {
        return blockScope;
    }

    /**
     * 检测物品是否命中本规则。
     *
     * @param item    待检测物品
     * @param matcher NBT 匹配引擎
     * @return true = 命中
     */
    public boolean matches(ItemStack item, NbtMatcher matcher) {
        if (item == null || item.getType() == Material.AIR) return false;

        // ── 种类1：材质 ──
        if (material != null && item.getType() != material) return false;

        // ── 种类2：名称 ──
        if (displayName != null) {
            String actual = getDisplayName(item);
            if (actual == null) return false;
            if (fuzzy) {
                if (!actual.contains(displayName)) return false;
            } else {
                if (!actual.equals(displayName)) return false;
            }
        }

        // ── 种类3：NBT ──
        if (nbt != null && matcher != null) {
            if (!matcher.matches(item, nbt)) return false;
        }

        return true;
    }

    /** 取物品显示名；无自定义名返回 null */
    private static String getDisplayName(ItemStack item) {
        if (!item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return null;
        return meta.getDisplayName();
    }

    /**
     * 取指定触发的冷却 tick。
     *
     * @return tick 数；<=0 表示该触发不检测
     */
    public int getCooldownTicks(Player player, Trigger trigger, PlaceholderHook hook) {
        String raw = cooldowns.get(trigger);
        if (raw == null) return 0;
        raw = raw.trim();
        if (raw.isEmpty()) return 0;

        // 解析 PlaceholderAPI 变量
        if (hook != null && hook.isEnabled()) {
            raw = hook.apply(player, raw).trim();
        }
        if (raw.isEmpty()) return 0;

        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            // 支持小数写法，向下取整
        }
        try {
            return (int) Math.floor(Double.parseDouble(raw));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 取配置中的原始冷却表达式（未解析 PAPI），用于 /itemcd info 展示 */
    public String getRawCooldown(Trigger trigger) {
        String raw = cooldowns.get(trigger);
        return raw == null ? "0" : raw;
    }

    /** 配置中的 & 颜色码转换为 § */
    public static String colorize(String input) {
        return input == null ? null : ChatColor.translateAlternateColorCodes('&', input);
    }
}
