package cc.keran.itemcd.config;

/**
 * 一次匹配的完整结果：物品所属集合 + 命中的检测规则。
 */
public class MatchResult {

    private final CheckGroup group;
    private final CheckRule rule;

    public MatchResult(CheckGroup group, CheckRule rule) {
        this.group = group;
        this.rule = rule;
    }

    public CheckGroup getGroup() {
        return group;
    }

    public CheckRule getRule() {
        return rule;
    }
}
