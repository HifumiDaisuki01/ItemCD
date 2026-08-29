package cc.keran.itemcd.nbt;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

/**
 * 原生 Bukkit PDC 匹配引擎（零依赖兜底）。
 *
 * <p>仅支持 {@code PublicBukkitValues} 顶层下的路径——这正是
 * CrackShot / CrackShotPlus / MythicMobs 等插件写入自定义数据的位置。</p>
 *
 * <p>若需匹配任意 NBT 路径或更深的多层嵌套，请安装 NBTAPI 插件，
 * 插件会自动切换到 {@link NbtApiMatcher}。</p>
 */
public class PdcMatcher implements NbtMatcher {

    @Override
    public String getName() {
        return "Bukkit-PDC (仅支持 PublicBukkitValues)";
    }

    @Override
    public boolean supportsArbitraryPaths() {
        return false;
    }

    @Override
    public boolean matches(ItemStack item, Map<String, Object> expected) {
        if (expected == null || expected.isEmpty()) return true;
        if (item == null || item.getType() == Material.AIR) return false;
        if (!item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            // 原生 PDC 只能访问 PublicBukkitValues 一层
            if (!"PublicBukkitValues".equalsIgnoreCase(entry.getKey())) return false;
            if (!(entry.getValue() instanceof Map)) return false;

            @SuppressWarnings("unchecked")
            Map<String, Object> inner = (Map<String, Object>) entry.getValue();

            for (Map.Entry<String, Object> field : inner.entrySet()) {
                NamespacedKey key = toKey(field.getKey());
                if (key == null) return false;

                Object actual = readRaw(pdc, key);
                if (actual == null) return false;
                if (!NbtValueUtil.valueEquals(actual, field.getValue())) return false;
            }
        }
        return true;
    }

    private NamespacedKey toKey(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return NamespacedKey.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 依次尝试常见类型读取；都存在性由返回值非 null 表达 */
    private Object readRaw(PersistentDataContainer pdc, NamespacedKey key) {
        try {
            String s = pdc.get(key, PersistentDataType.STRING);
            if (s != null) return s;
            Integer i = pdc.get(key, PersistentDataType.INTEGER);
            if (i != null) return i;
            Double d = pdc.get(key, PersistentDataType.DOUBLE);
            if (d != null) return d;
            Long l = pdc.get(key, PersistentDataType.LONG);
            if (l != null) return l;
            Byte b = pdc.get(key, PersistentDataType.BYTE);
            if (b != null) return b;
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
