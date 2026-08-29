package cc.keran.itemcd.listener;

import cc.keran.itemcd.ItemCD;
import cc.keran.itemcd.config.CheckGroup;
import cc.keran.itemcd.config.MatchResult;
import cc.keran.itemcd.config.Trigger;
import cc.keran.itemcd.util.HotbarTracker;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品冷却事件监听。
 *
 * <p>覆盖七类触发时机：主副手切换、快捷栏切换、背包与快捷栏互换、
 * 左键、右键、造成伤害的攻击、消耗物品。</p>
 */
public class ItemListener implements Listener {

    /**
     * {@link PlayerInteractEvent} 的 {@code protected ItemStack item} 字段。
     *
     * <p>用途：部分武器插件不检查事件的 cancelled 状态，仅靠 {@code setCancelled(true)}
     * 拦不住它们。若这些插件通过 {@code event.getItem()} 识别武器，把该字段替换为空气
     * 即可让武器在其视角中「消失」。</p>
     *
     * <p><b>已知对 CrackShot / CrackShotPlus 无效</b>（v1.4.0 实测）：
     * 它们通过 {@code player.getInventory().getItemInMainHand()} 读取武器，
     * 绕过了事件携带的物品。因此该机制仅作为对<b>其他</b>插件的兼容手段保留，
     * 拦截 CrackShot 请依赖 {@link cc.keran.itemcd.hook.PacketHook} 的协议层方案。</p>
     */
    private static Field interactItemField = resolveInteractItemField();

    private static Field resolveInteractItemField() {
        try {
            Field field = PlayerInteractEvent.class.getDeclaredField("item");
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private final ItemCD plugin;

    /** 待执行快捷栏扫描的玩家（去重，避免拖放等高频事件重复调度） */
    private final Set<UUID> pendingScan = ConcurrentHashMap.newKeySet();

    /** 待执行主副手切换扫描的玩家 */
    private final Set<UUID> pendingSwap = ConcurrentHashMap.newKeySet();

    public ItemListener(ItemCD plugin) {
        this.plugin = plugin;
    }

    // ══════════════ 触发一：快捷栏切换 ══════════════
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        int newSlot = event.getNewSlot();
        int prevSlot = event.getPreviousSlot();

        ItemStack newItem = player.getInventory().getItem(newSlot);

        // 切换前所在槽位的物品所属集合（用于跨集合豁免）
        String fromGroup = plugin.getTracker().getGroup(player, prevSlot);

        MatchResult result = plugin.findMatch(player, newItem);
        plugin.getTracker().refresh(player, newSlot, newItem,
                result == null ? null : result.getGroup().getId());

        apply(player, newItem, result, fromGroup, Trigger.SWITCH,
                "switch(" + prevSlot + "->" + newSlot + ")");
    }

    // ══════════════ 左键 / 右键（点一下即检测）══════════════
    //
    //  注意：此处【不能】加 ignoreCancelled = true。
    //  CrackShot / CrackShotPlus 等武器插件在开火后会主动 setCancelled(true)
    //  以阻止原版交互行为；一旦忽略已取消的事件，检测就会被整体跳过，
    //  表现为「左键必须打到怪才触发」。
    //  因此这里监听全部交互事件，无论最终是否被其他插件取消。
    //
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        Trigger trigger;
        switch (event.getAction()) {
            case LEFT_CLICK_AIR:
            case LEFT_CLICK_BLOCK:
                trigger = Trigger.LEFT;
                break;
            case RIGHT_CLICK_AIR:
            case RIGHT_CLICK_BLOCK:
                trigger = Trigger.RIGHT;
                break;
            default:
                return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        Player player = event.getPlayer();
        MatchResult result = plugin.findMatch(player, item);
        apply(player, item, result, null, trigger, event.getAction().name());
    }

    // ══════════════ 右键实体 ══════════════
    //  同样不忽略已取消事件（武器插件常会取消交互事件）
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        ItemStack item = getHeldItem(player, event.getHand());
        if (item == null) return;

        MatchResult result = plugin.findMatch(player, item);
        apply(player, item, result, null, Trigger.RIGHT, "right-click-entity");
    }

    // ══════════════ 造成伤害的左键点击 ══════════════
    //  仅在伤害实际成立时触发（ignoreCancelled: 伤害被取消 = 未造成伤害）
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttackEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (!(damager instanceof Player)) return;

        Player player = (Player) damager;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) return;

        MatchResult result = plugin.findMatch(player, item);
        apply(player, item, result, null, Trigger.ATTACK, "attack-entity");
    }

    // ══════════════ 消耗物品（吃食物 / 喝药水）══════════════
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        Player player = event.getPlayer();
        MatchResult result = plugin.findMatch(player, item);
        apply(player, item, result, null, Trigger.CONSUME, "consume");
    }

    // ══════════════ 触发四：主副手切换（按 F）══════════════
    //
    //  注意：此处【不能】加 ignoreCancelled = true。
    //  CrackShotPlus 用 F 键切换射击模式（firemode）时会主动把本事件
    //  setCancelled(true)（反编译 CSP 1.108 的 PlayerSwapHandItems 确认：
    //  setFiremodeChanged + setCancelled）。一旦忽略已取消事件，
    //  firemode 切换就不会触发 swap 冷却检测。
    //  因此这里监听全部 F 键事件，无论是否被其他插件取消。
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();

        if (!pendingSwap.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingSwap.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            // F 键按下即检测当前主手（无论事件是否被取消）：
            //  - CSP firemode 切换：事件被取消，主手未变，直接检测当前主手
            //  - 真实主副手交换：事件未取消，主手已变为原副手物品
            applySwap(player);
        });
    }

    /** F 键按下后统一检测：对当前主手物品施加 swap 冷却 */
    private void applySwap(Player player) {
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack current = player.getInventory().getItem(slot);
        if (current == null || current.getType() == Material.AIR) return;

        MatchResult result = plugin.findMatch(player, current);
        String fromGroup = plugin.getTracker().getGroup(player, slot);
        plugin.getTracker().refresh(player, slot, current,
                result == null ? null : result.getGroup().getId());

        if (result != null) {
            apply(player, current, result, fromGroup, Trigger.SWAP, "swap-hands");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //   冷却中禁止交互
    //   原版冷却仅是视觉遮罩，CrackShot 等第三方武器不检查它，
    //   必须在此主动取消交互事件才能真正禁用冷却中的物品。
    //   使用 LOWEST 优先级，确保后续插件（含 CrackShot）收到 cancelled 事件。
    // ══════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void blockInteractWhileCooling(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        if (shouldBlockInteraction(player, item, event.getAction().name())) {
            event.setCancelled(true);
            // 关键：CrackShot 不检查 cancelled，需把事件携带的物品换成空气
            blankOutEventItem(event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void blockConsumeWhileCooling(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        if (shouldBlockInteraction(player, item, "consume-item")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void blockInteractEntityWhileCooling(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        ItemStack item = getHeldItem(player, event.getHand());
        if (item == null) return;

        if (shouldBlockInteraction(player, item, "right-click-entity")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void blockAttackWhileCooling(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (!(damager instanceof Player)) return;

        Player player = (Player) damager;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) return;

        if (shouldBlockInteraction(player, item, "left-click-entity")) {
            event.setCancelled(true);
        }
    }

    // ══════════════ 触发三：背包内物品与快捷栏物品切换 ══════════════
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!affectsHotbar(event)) return;
        scheduleScan((Player) event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!affectsHotbar(event)) return;
        scheduleScan((Player) event.getWhoClicked());
    }

    // ══════════════ 生命周期 ══════════════
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        initHotbar(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingScan.remove(id);
        pendingSwap.remove(id);
        plugin.getTracker().clear(id);
        plugin.getCooldownRegistry().clear(id);
    }

    /** 初始化玩家快捷栏快照（上线时调用，避免首次切换误判来源） */
    public void initHotbar(Player player) {
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < HotbarTracker.SIZE; slot++) {
            ItemStack item = inv.getItem(slot);
            MatchResult result = plugin.findMatch(player, item);
            plugin.getTracker().refresh(player, slot, item,
                    result == null ? null : result.getGroup().getId());
        }
    }

    /** 重载配置后重建所有在线玩家的快捷栏快照 */
    public void reinitAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            initHotbar(player);
        }
    }

    // ══════════════ 内部实现 ══════════════

    /** 点击是否可能影响快捷栏 */
    private boolean affectsHotbar(InventoryClickEvent event) {
        InventoryAction action = event.getAction();
        int playerSlot = event.getView().convertSlot(event.getRawSlot());

        // 明确会移动物品到/自快捷栏的操作
        switch (action) {
            case MOVE_TO_OTHER_INVENTORY:   // Shift + 点击
            case HOTBAR_SWAP:               // 数字键交换
            case HOTBAR_MOVE_AND_READD:     // 数字键放置
            case SWAP_WITH_CURSOR:          // 光标物品交换
            case DROP_ALL_CURSOR:
            case DROP_ONE_CURSOR:
            case DROP_ALL_SLOT:
            case DROP_ONE_SLOT:
            case PICKUP_ALL:
            case PICKUP_SOME:
            case PICKUP_HALF:
            case PICKUP_ONE:
            case PLACE_ALL:
            case PLACE_SOME:
            case PLACE_ONE:
            case CLONE_STACK:
            case COLLECT_TO_CURSOR:
                break;
            default:
                // 其余动作（NOTHING / UNKNOWN 等）仅在作用于快捷栏槽位时才需扫描
                return isHotbarSlot(playerSlot);
        }

        // 上述动作仍需确认作用在玩家背包侧（含快捷栏 0-8）
        return playerSlot >= 0;
    }

    /** 拖放是否涉及快捷栏 */
    private boolean affectsHotbar(InventoryDragEvent event) {
        for (int rawSlot : event.getRawSlots()) {
            if (isHotbarSlot(event.getView().convertSlot(rawSlot))) return true;
        }
        return false;
    }

    private boolean isHotbarSlot(int playerInventorySlot) {
        return playerInventorySlot >= 0 && playerInventorySlot < HotbarTracker.SIZE;
    }

    /** 事件结束后（下一 tick）扫描快捷栏，检测槽位物品变化 */
    private void scheduleScan(Player player) {
        if (!pendingScan.add(player.getUniqueId())) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingScan.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            scanHotbar(player);
        });
    }

    private void scanHotbar(Player player) {
        Inventory inv = player.getInventory();
        HotbarTracker tracker = plugin.getTracker();

        for (int slot = 0; slot < HotbarTracker.SIZE; slot++) {
            ItemStack current = inv.getItem(slot);
            ItemStack before = tracker.getItem(player, slot);
            String fromGroup = tracker.getGroup(player, slot);

            MatchResult result = plugin.findMatch(player, current);
            String newGroup = result == null ? null : result.getGroup().getId();

            boolean changed = !HotbarTracker.sameItem(before, current);
            tracker.refresh(player, slot, current, newGroup);

            // 仅当槽位物品实际发生变化时才视为「切换」
            if (changed && result != null) {
                apply(player, current, result, fromGroup, Trigger.INVENTORY,
                        "inventory-swap(slot " + slot + ")");
            }
        }
    }

    /**
     * 统一施加冷却。
     *
     * @param fromGroup 切换前物品所属集合 id；null 表示空气或不属任何集合
     */
    private void apply(Player player, ItemStack item, MatchResult result,
                       String fromGroup, Trigger trigger, String source) {
        if (result == null || item == null || item.getType() == Material.AIR) return;
        if (player.hasPermission("itemcd.bypass")) return;

        CheckGroup group = result.getGroup();

        // 跨集合豁免：从指定集合切过来时跳过检测（对该集合内所有检测生效）
        if (group.shouldIgnore(fromGroup)) {
            plugin.debug("EXEMPT " + source + " : " + fromGroup + " -> " + group.getId()
                    + " (" + result.getRule().getId() + ")");
            return;
        }

        int ticks = result.getRule().getCooldownTicks(player, trigger, plugin.getPlaceholderHook());

        // 冷却 <= 0：该触发不检测
        if (ticks <= 0) return;

        // 冷却按「触发类型」独立记录：
        //   - 跨触发（如从 left 冷却切换到 switch 触发）会重新计算冷却；
        //   - 同一触发类型未过期则不重复施加，避免连点把冷却无限续期。
        // 这修复了旧逻辑「任何触发都跳过已有冷却的物品」导致的
        // 「切换物品栏并切回不重新计算冷却」问题。
        boolean applied = plugin.getCooldownRegistry().apply(player, item.getType(), trigger, ticks);

        if (applied) {
            plugin.debug("COOLDOWN " + source + " : " + item.getType() + " x " + ticks + "t"
                    + " [group=" + group.getId() + ", rule=" + result.getRule().getId()
                    + ", trigger=" + trigger.getKey() + "]");
        } else {
            plugin.debug("SKIP " + source + " : " + item.getType() + " 的 "
                    + trigger.getKey() + " 冷却仍在进行，剩余 "
                    + plugin.getCooldownRegistry().remaining(player, item.getType())
                    + "t，不重复施加");
        }
    }

    /**
     * 把交互事件携带的物品替换为空气。
     *
     * <p>CrackShot / CrackShotPlus 不检查事件的 cancelled 状态，
     * 仅靠 setCancelled 拦不住开火；但它们通过 {@code event.getItem()} 识别武器。
     * 将该字段置为空气后，武器在这些插件视角中不存在，从而无法开火。</p>
     */
    private void blankOutEventItem(PlayerInteractEvent event) {
        if (interactItemField == null) return;
        try {
            interactItemField.set(event, new ItemStack(Material.AIR));
        } catch (Throwable t) {
            // 反射失败（版本变更等）则永久关闭该机制，避免反复报错
            interactItemField = null;
            plugin.log("&c无法改写交互事件的 item 字段，CrackShot 拦截将退化为仅依赖事件取消。");
        }
    }

    /**
     * 判断物品是否处于冷却中且应被拦截。
     *
     * @return true = 应取消交互事件
     */
    private boolean shouldBlockInteraction(Player player, ItemStack item, String action) {
        if (!plugin.getConfigManager().isBlockInteraction()) return false;
        if (player.hasPermission("itemcd.bypass")) return false;
        if (!plugin.getCooldownRegistry().isCooling(player, item.getType())) return false;

        // air-only 模式：保留对方块的交互（开门 / 挖方块 / 开箱子），
        // 避免手持冷却中武器时连门都开不了。实体交互不受此限制。
        if ("air-only".equalsIgnoreCase(plugin.getConfigManager().getBlockInteractionMode())
                && action.endsWith("_BLOCK")) {
            return false;
        }

        plugin.sendCooldownFeedback(player, item);
        plugin.debug("BLOCKED " + action + " : " + item.getType()
                + " 处于冷却，剩余 " + plugin.getCooldownRegistry().remaining(player, item.getType()) + "t");

        // 事件层对 CrackShot 无效（它既不检查 cancelled，也不读 event.getItem()）。
        // 真正的拦截由 PacketHook 在协议层完成，这里主要承担两件事：
        //   1) 未安装 ProtocolLib 时的降级拦截（对原版物品依然有效）
        //   2) 为 block-interaction-mode 等纯 Bukkit 场景提供拦截
        return true;
    }

    private ItemStack getHeldItem(Player player, org.bukkit.inventory.EquipmentSlot hand) {
        if (hand == null) return null;
        PlayerInventory inv = player.getInventory();
        ItemStack item = (hand == org.bukkit.inventory.EquipmentSlot.HAND)
                ? inv.getItemInMainHand() : inv.getItemInOffHand();
        return item.getType() == Material.AIR ? null : item;
    }
}
