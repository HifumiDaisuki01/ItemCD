package cc.keran.itemcd.nbt;

/**
 * NBT 叶子节点值比较工具。
 *
 * <p>不同来源的类型可能不一致（配置里是 Integer，NBT 里是 String / Byte / Double），
 * 因此采用「同类型直比 + 跨类型字符串兜底」的宽松比较。</p>
 */
public final class NbtValueUtil {

    /** 通配值：只要该 key 存在即视为匹配 */
    public static final String WILDCARD = "*";

    private NbtValueUtil() {
    }

    /**
     * @param actual   物品 NBT 中的实际值
     * @param expected 配置中的期望值
     * @return true = 值匹配
     */
    public static boolean valueEquals(Object actual, Object expected) {
        if (expected == null) return actual == null;
        if (actual == null) return false;

        String expStr = String.valueOf(expected);

        // 通配：key 已存在即匹配
        if (WILDCARD.equals(expStr)) return true;

        // 布尔：NBT 中常以 byte 0/1 存储
        if (expected instanceof Boolean) {
            boolean exp = (Boolean) expected;
            if (actual instanceof Boolean) return ((Boolean) actual) == exp;
            if (actual instanceof Number) {
                return ((Number) actual).byteValue() == (exp ? (byte) 1 : (byte) 0);
            }
            return String.valueOf(actual).equalsIgnoreCase(expStr);
        }

        // 数值：直接按 double 比较
        if (actual instanceof Number && expected instanceof Number) {
            return ((Number) actual).doubleValue() == ((Number) expected).doubleValue();
        }

        // 字符串直比
        String actStr = String.valueOf(actual);
        if (actStr.equals(expStr)) return true;

        // 跨类型数值兜底（NBT 存 "40"，配置写 40）
        try {
            return Double.parseDouble(actStr) == Double.parseDouble(expStr);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static boolean isWildcard(Object expected) {
        return expected != null && WILDCARD.equals(String.valueOf(expected));
    }
}
