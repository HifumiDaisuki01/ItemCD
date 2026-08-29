package cc.keran.itemcd.util;

import cc.keran.itemcd.config.Trigger;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按「触发类型」独立计时的冷却注册表。
 *
 * <h3>为什么需要它</h3>
 * <p>原版 {@code Player#setCooldown} 只能按材质记录<b>一个</b>冷却值，
 * 且旧的实现里任何触发一旦命中就直接跳过（表现为「切换物品栏并切回
 * 不会重新计算冷却」）。本注册表为每个 (玩家, 材质, 触发类型) 分别记录
 * 到期时间，从而做到：</p>
 * <ul>
 *   <li><b>跨触发独立</b>：切回武器触发 {@code switch} 时，即使 {@code left}
 *       冷却还在进行，{@code switch} 的冷却也会重新计算（满足
 *       「切换物品栏并切回应重新计算」）；</li>
 *   <li><b>同触发防续期</b>：同一个触发类型在未过期时不重复延长，
 *       避免按住左键狂点把冷却无限续期。</li>
 * </ul>
 *
 * <p>原版冷却遮罩仍会同步：取所有触发中剩余<b>最大</b>的值写入
 * {@code setCooldown}，且只在更大时才写入，避免缩短既有冷却。</p>
 */
public class CooldownRegistry {

    /** 50ms / tick */
    private static final long MS_PER_TICK = 50L;

    /**
     * 玩家UUID -> 材质 -> 触发类型 -> 到期时间戳(ms)。
     * 使用并发容器，兼容协议层（可能位于非主线程）的读取。
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Material, EnumMap<Trigger, Long>>> store =
            new ConcurrentHashMap<>();

    /**
     * 施加一次冷却。
     *
     * <ul>
     *   <li><b>切换类触发</b>（switch / swap / inventory）：
     *       每次发生都<b>重置</b>该触发的冷却——例如「切走再切回」会重新计时，
     *       即玩家期望的「切换物品栏并切回应重新计算冷却」；</li>
     *   <li><b>使用类触发</b>（left / right / attack / consume）：
     *       同一触发未过期时不重复延长，防止连点把冷却无限续期。</li>
     * </ul>
     *
     * @return true = 冷却被施加/更新；false = 未生效（使用类触发仍在冷却，不延长）
     */
    public boolean apply(Player player, Material material, Trigger trigger, int ticks) {
        if (player == null || material == null || trigger == null || ticks <= 0) return false;

        long now = System.currentTimeMillis();
        long expire = now + ticks * MS_PER_TICK;
        UUID id = player.getUniqueId();

        ConcurrentHashMap<Material, EnumMap<Trigger, Long>> byMaterial =
                store.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        EnumMap<Trigger, Long> byTrigger =
                byMaterial.computeIfAbsent(material, k -> new EnumMap<>(Trigger.class));

        synchronized (byTrigger) {
            if (trigger.isResetOnTrigger()) {
                // 切换类：无条件重置（切走再切回 = 重新计时）
                byTrigger.put(trigger, expire);
            } else {
                // 使用类：同触发未过期则不延长
                Long existing = byTrigger.get(trigger);
                if (existing != null && existing > now) {
                    return false;
                }
                byTrigger.put(trigger, expire);
            }
        }

        // 同步原版冷却遮罩：写入所有触发中的最大剩余
        syncVanillaCooldown(player, material);
        return true;
    }

    /** 是否有任何触发类型处于冷却中 */
    public boolean isCooling(Player player, Material material) {
        return remaining(player, material) > 0;
    }

    /** 所有触发类型中的最大剩余 tick；无冷却返回 0。顺带清理已过期的条目。 */
    public int remaining(Player player, Material material) {
        if (player == null || material == null) return 0;
        UUID id = player.getUniqueId();

        Map<Material, EnumMap<Trigger, Long>> byMaterial = store.get(id);
        if (byMaterial == null) return 0;

        EnumMap<Trigger, Long> byTrigger = byMaterial.get(material);
        if (byTrigger == null) return 0;

        long now = System.currentTimeMillis();
        int max = 0;
        boolean empty;
        synchronized (byTrigger) {
            for (Iterator<Map.Entry<Trigger, Long>> it = byTrigger.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Trigger, Long> entry = it.next();
                long left = entry.getValue() - now;
                if (left <= 0) {
                    it.remove();
                    continue;
                }
                int ticks = (int) ((left + MS_PER_TICK - 1) / MS_PER_TICK);
                if (ticks > max) max = ticks;
            }
            empty = byTrigger.isEmpty();
        }

        if (empty) {
            byMaterial.remove(material);
            if (byMaterial.isEmpty()) store.remove(id);
        }
        return max;
    }

    /** 玩家离线 / 重载时清理 */
    public void clear(UUID playerId) {
        store.remove(playerId);
    }

    public void clearAll() {
        store.clear();
    }

    /** 当前记录条数（诊断用） */
    public int size() {
        int n = 0;
        for (Map<Material, EnumMap<Trigger, Long>> m : store.values()) {
            n += m.size();
        }
        return n;
    }

    /**
     * 把最大剩余写入原版冷却（客户端显示灰色遮罩）。
     * 仅在比当前原版冷却更长时写入，避免缩短既有冷却。
     */
    private void syncVanillaCooldown(Player player, Material material) {
        int remain = remaining(player, material);
        if (remain <= 0) return;
        if (player.getCooldown(material) < remain) {
            player.setCooldown(material, remain);
        }
    }
}
