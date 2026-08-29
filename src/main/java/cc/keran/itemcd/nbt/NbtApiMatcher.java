package cc.keran.itemcd.nbt;

import de.tr7zw.changeme.nbtapi.NBTCompound;
import de.tr7zw.changeme.nbtapi.NBTItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * 基于 NBT-API 的 NBT 匹配引擎（功能完整，支持任意 NBT 路径与多层嵌套）。
 *
 * <p>软依赖：服务端需安装 NBTAPI 插件。</p>
 *
 * <p>重要：{@link NBTItem} 的根即物品 NBT 的 {@code tag} 层，
 * 因此配置中可直接书写 {@code PublicBukkitValues / display / CustomModelData} 等顶层 key。</p>
 */
public class NbtApiMatcher implements NbtMatcher {

    @Override
    public String getName() {
        return "NBT-API";
    }

    @Override
    public boolean supportsArbitraryPaths() {
        return true;
    }

    @Override
    public boolean matches(ItemStack item, Map<String, Object> expected) {
        if (expected == null || expected.isEmpty()) return true;
        if (item == null || item.getType() == Material.AIR) return false;

        NBTItem nbtItem = new NBTItem(item);
        return matchCompound(nbtItem, expected);
    }

    /** 导出物品完整 NBT，便于对照配置书写检测结构 */
    @Override
    public String dumpNbt(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        try {
            return new NBTItem(item).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 递归匹配：期望结构必须是实际结构的子集 */
    private boolean matchCompound(NBTCompound actual, Map<String, Object> expected) {
        if (actual == null) return false;

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // 先确认 key 存在（getCompound 对不存在的 key 会隐式创建，必须先判存在）
            if (!actual.getKeys().contains(key)) return false;

            if (value instanceof Map) {
                // 嵌套层
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) value;
                if (child.isEmpty()) continue;
                NBTCompound sub = actual.getCompound(key);
                if (sub == null) return false;
                if (!matchCompound(sub, child)) return false;
            } else {
                // 叶子：值比较
                if (!valueMatches(actual, key, value)) return false;
            }
        }
        return true;
    }

    private boolean valueMatches(NBTCompound compound, String key, Object expected) {
        // 通配：key 存在即可（key 存在性已在调用前确认）
        if (NbtValueUtil.isWildcard(expected)) return true;

        Object actualValue = readValue(compound, key, expected);
        if (actualValue == null) return false;
        return NbtValueUtil.valueEquals(actualValue, expected);
    }

    /** 按期望类型优先读取，失败则依次兜底 */
    private Object readValue(NBTCompound c, String key, Object expected) {
        if (expected instanceof Boolean) {
            Boolean b = c.getBoolean(key);
            if (b != null) return b;
        } else if (expected instanceof Integer || expected instanceof Long || expected instanceof Short) {
            Integer i = c.getInteger(key);
            if (i != null) return i;
        } else if (expected instanceof Double || expected instanceof Float) {
            Double d = c.getDouble(key);
            if (d != null) return d;
        }

        String s = c.getString(key);
        if (s != null) return s;

        Integer i2 = c.getInteger(key);
        if (i2 != null) return i2;

        Double d2 = c.getDouble(key);
        if (d2 != null) return d2;

        Byte b2 = c.getByte(key);
        return b2;
    }
}
