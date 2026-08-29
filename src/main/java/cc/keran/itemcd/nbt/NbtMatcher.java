package cc.keran.itemcd.nbt;

import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * NBT 匹配引擎。
 *
 * <p>期望结构支持多层嵌套。匹配语义为「期望结构是物品 NBT 的子集」：
 * 期望中的每一层 key 都必须存在，且叶子节点的值必须相等。</p>
 */
public interface NbtMatcher {

    /**
     * @param item     待检测物品
     * @param expected 期望的 NBT 结构（多层 Map；叶子为值）
     * @return true = 物品 NBT 包含期望结构
     */
    boolean matches(ItemStack item, Map<String, Object> expected);

    /** 引擎名称，用于启动日志 */
    String getName();

    /**
     * 导出物品的完整 NBT 结构（用于调试配置）。
     *
     * @return NBT 字符串表示；不支持时返回 null
     */
    default String dumpNbt(ItemStack item) {
        return null;
    }

    /** 是否支持任意 NBT 路径（false 表示仅支持 PublicBukkitValues） */
    boolean supportsArbitraryPaths();
}
