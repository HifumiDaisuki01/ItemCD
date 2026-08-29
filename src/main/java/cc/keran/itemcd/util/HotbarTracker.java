package cc.keran.itemcd.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家快捷栏快照追踪器。
 *
 * <p>记录每个快捷栏槽位上一次的物品与所属集合 id，用于：</p>
 * <ul>
 *   <li>识别「背包内物品与快捷栏物品互换」导致的槽位变化</li>
 *   <li>提供跨集合豁免判定所需的「切换前所属集合」</li>
 * </ul>
 */
public class HotbarTracker {

    public static final int SIZE = 9;

    private final Map<UUID, ItemStack[]> items = new ConcurrentHashMap<>();
    private final Map<UUID, String[]> groups = new ConcurrentHashMap<>();

    private ItemStack[] itemsOf(UUID id) {
        return items.computeIfAbsent(id, k -> new ItemStack[SIZE]);
    }

    private String[] groupsOf(UUID id) {
        return groups.computeIfAbsent(id, k -> new String[SIZE]);
    }

    private static boolean valid(int slot) {
        return slot >= 0 && slot < SIZE;
    }

    public ItemStack getItem(Player player, int slot) {
        if (player == null || !valid(slot)) return null;
        return itemsOf(player.getUniqueId())[slot];
    }

    public void setItem(Player player, int slot, ItemStack item) {
        if (player == null || !valid(slot)) return;
        itemsOf(player.getUniqueId())[slot] = (item == null) ? null : item.clone();
    }

    public String getGroup(Player player, int slot) {
        if (player == null || !valid(slot)) return null;
        return groupsOf(player.getUniqueId())[slot];
    }

    public void setGroup(Player player, int slot, String groupId) {
        if (player == null || !valid(slot)) return;
        groupsOf(player.getUniqueId())[slot] = groupId;
    }

    /** 刷新单个槽位的物品与集合归属 */
    public void refresh(Player player, int slot, ItemStack item, String groupId) {
        setItem(player, slot, item);
        setGroup(player, slot, groupId);
    }

    public void clear(UUID uuid) {
        items.remove(uuid);
        groups.remove(uuid);
    }

    /**
     * 判断两个物品是否「同一件」（忽略堆叠数量，比较材质与完整元数据/NBT）。
     */
    public static boolean sameItem(ItemStack a, ItemStack b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.isSimilar(b);
    }
}
