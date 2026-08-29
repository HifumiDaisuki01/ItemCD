package cc.keran.itemcd.config;

/**
 * 冷却触发时机。
 * 每种触发可在配置中单独设置冷却 tick。
 */
public enum Trigger {

    /** 快捷栏切换物品（滚轮 / 数字键）—— 切换类：每次触发都重新计算冷却 */
    SWITCH("switch", true),

    /** 背包内物品与快捷栏物品互换（点击 / 拖放 / Shift+点击 / 数字键交换）—— 切换类 */
    INVENTORY("inventory", true),

    /** 主副手切换（默认按 F）—— 切换类 */
    SWAP("swap", true),

    /** 左键：点一下即检测，不论是否命中、是否被其他插件取消 —— 使用类 */
    LEFT("left", false),

    /** 右键：点一下即检测，不论是否触发效果、是否被其他插件取消 —— 使用类 */
    RIGHT("right", false),

    /** 左键且实际造成伤害（命中实体）—— 使用类 */
    ATTACK("attack", false),

    /** 消耗物品：吃食物、喝药水等 —— 使用类 */
    CONSUME("consume", false);

    private final String key;

    /**
     * 切换类触发：每次发生都会重置该触发的冷却（如「切走再切回」重新计时）。
     * 使用类触发：同一触发在未过期时不重复延长，防止连点把冷却无限续期。
     */
    private final boolean resetOnTrigger;

    Trigger(String key) {
        this(key, false);
    }

    Trigger(String key, boolean resetOnTrigger) {
        this.key = key;
        this.resetOnTrigger = resetOnTrigger;
    }

    public String getKey() {
        return key;
    }

    /** 是否为切换类触发（每次触发都重置冷却） */
    public boolean isResetOnTrigger() {
        return resetOnTrigger;
    }

    public static Trigger fromKey(String key) {
        if (key == null) return null;
        for (Trigger t : values()) {
            if (t.key.equalsIgnoreCase(key)) return t;
        }
        return null;
    }
}
