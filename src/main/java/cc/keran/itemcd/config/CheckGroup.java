package cc.keran.itemcd.config;

import cc.keran.itemcd.nbt.NbtMatcher;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检测集合。一个集合包含多条检测规则，
 * 并可声明「从哪些集合的物品切换到本集合物品时跳过检测」。
 */
public class CheckGroup {

    private final String id;
    private final Set<String> ignoreFrom;
    private final List<CheckRule> checks;

    public CheckGroup(String id, Collection<String> ignoreFrom, List<CheckRule> checks) {
        this.id = id;
        // 统一小写存储，比较时忽略大小写
        Set<String> tmp = new LinkedHashSet<>();
        if (ignoreFrom != null) {
            for (String s : ignoreFrom) {
                if (s != null && !s.trim().isEmpty()) {
                    tmp.add(s.trim().toLowerCase());
                }
            }
        }
        this.ignoreFrom = tmp;
        this.checks = checks == null ? new ArrayList<>() : new ArrayList<>(checks);
    }

    public String getId() {
        return id;
    }

    public List<CheckRule> getChecks() {
        return Collections.unmodifiableList(checks);
    }

    public Set<String> getIgnoreFrom() {
        return Collections.unmodifiableSet(ignoreFrom);
    }

    /**
     * 是否应当跳过检测。
     *
     * @param fromGroupId 切换前物品所属集合 id；可为 null（空气或不属任何集合）
     * @return true = 跳过
     */
    public boolean shouldIgnore(String fromGroupId) {
        if (fromGroupId == null) return false;
        return ignoreFrom.contains(fromGroupId.toLowerCase());
    }

    /**
     * 在本集合中查找第一个命中的检测规则。
     *
     * @return 命中的规则；无命中返回 null
     */
    public CheckRule findMatch(ItemStack item, NbtMatcher matcher) {
        if (item == null) return null;
        for (CheckRule rule : checks) {
            if (rule.matches(item, matcher)) return rule;
        }
        return null;
    }
}
