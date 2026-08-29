package cc.keran.itemcd;

import cc.keran.itemcd.command.ItemCDCommand;
import cc.keran.itemcd.config.CheckGroup;
import cc.keran.itemcd.config.CheckRule;
import cc.keran.itemcd.config.ConfigManager;
import cc.keran.itemcd.config.MatchResult;
import cc.keran.itemcd.hook.CrackShotHook;
import cc.keran.itemcd.hook.PacketHook;
import cc.keran.itemcd.hook.PlaceholderHook;
import cc.keran.itemcd.listener.ItemListener;
import cc.keran.itemcd.nbt.NbtApiMatcher;
import cc.keran.itemcd.nbt.NbtMatcher;
import cc.keran.itemcd.nbt.PdcMatcher;
import cc.keran.itemcd.util.CooldownRegistry;
import cc.keran.itemcd.util.HotbarTracker;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ItemCD - 物品冷却控制插件。
 *
 * <p>按配置规则检测玩家物品，命中后对物品材质施加原版冷却。</p>
 *
 * <p>Copyright (c) Keran Technology Co., Ltd. All rights reserved.</p>
 */
public final class ItemCD extends JavaPlugin {

    public static final String PREFIX = "[ItemCD] ";

    private static ItemCD instance;

    private ConfigManager configManager;
    private NbtMatcher nbtMatcher;
    private PlaceholderHook placeholderHook;
    private HotbarTracker tracker;
    private ItemListener listener;
    private PacketHook packetHook;
    private CrackShotHook crackShotHook;
    private CooldownRegistry cooldownRegistry;

    public static ItemCD getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        printBanner();

        // ── 配置文件 ──
        saveDefaultConfig();

        // ── NBT 匹配引擎 ──
        setupNbtMatcher();

        // ── PlaceholderAPI ──
        placeholderHook = new PlaceholderHook();
        log(placeholderHook.isEnabled()
                ? "&aPlaceholderAPI &7已接入，冷却时间支持变量解析。"
                : "&7未检测到 PlaceholderAPI，冷却时间仅支持纯数字。");

        // ── 配置 ──
        configManager = new ConfigManager(this);
        configManager.load();

        // ── 追踪器与监听器 ──
        tracker = new HotbarTracker();
        cooldownRegistry = new CooldownRegistry();
        listener = new ItemListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        listener.reinitAll();

        // ── 协议层拦截（需在配置加载之后）──
        packetHook = new PacketHook(this);
        packetHook.setup();

        // ── CrackShot 官方事件拦截 ──
        crackShotHook = new CrackShotHook(this);
        crackShotHook.setup();

        // ── 命令 ──
        PluginCommand command = getCommand("itemcd");
        if (command != null) {
            ItemCDCommand executor = new ItemCDCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            log("&c无法注册命令 itemcd（plugin.yml 未声明）");
        }

        log("&a已启用 &7- 共 &b" + configManager.getGroups().size()
                + " &7个检测集合 / &b" + configManager.getCheckCount() + " &7条检测规则。");
    }

    @Override
    public void onDisable() {
        if (crackShotHook != null) {
            crackShotHook.disable();
            crackShotHook = null;
        }
        if (packetHook != null) {
            packetHook.disable();
            packetHook = null;
        }
        if (cooldownRegistry != null) {
            cooldownRegistry.clearAll();
        }
        if (tracker != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                tracker.clear(player.getUniqueId());
            }
        }
        log("&7已卸载。Copyright (c) Keran Technology Co., Ltd.");
        instance = null;
    }

    // ══════════════ 对外接口 ══════════════

    /**
     * 在所有集合中查找第一个命中的检测。
     *
     * @return 匹配结果（含所属集合）；无命中返回 null
     */
    public MatchResult findMatch(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        for (CheckGroup group : configManager.getGroups()) {
            CheckRule rule = group.findMatch(item, nbtMatcher);
            if (rule != null) return new MatchResult(group, rule);
        }
        return null;
    }

    /** 导出物品完整 NBT（需 NBT-API 引擎）；当前引擎不支持时返回 null */
    public String dumpItemNbt(ItemStack item) {
        return nbtMatcher == null ? null : nbtMatcher.dumpNbt(item);
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public NbtMatcher getNbtMatcher() {
        return nbtMatcher;
    }

    public PlaceholderHook getPlaceholderHook() {
        return placeholderHook;
    }

    public HotbarTracker getTracker() {
        return tracker;
    }

    public ItemListener getListener() {
        return listener;
    }

    public PacketHook getPacketHook() {
        return packetHook;
    }

    public CrackShotHook getCrackShotHook() {
        return crackShotHook;
    }

    public CooldownRegistry getCooldownRegistry() {
        return cooldownRegistry;
    }

    /** 重载配置并重建在线玩家快照 */
    public void reload() {
        configManager.load();
        listener.reinitAll();
        if (packetHook != null) {
            packetHook.setup();
        }
        if (crackShotHook != null) {
            crackShotHook.setup();
        }
    }

    /** 冷却中交互被拦截时给玩家的提示。
     *
     * <p>事件层（ItemListener）、协议层（PacketHook）与
     * CrackShot 官方事件（CrackShotHook）共用同一套提示逻辑。</p>
     */
    public void sendCooldownFeedback(Player player, ItemStack item) {
        String message = configManager.getCooldownMessage();
        if (message == null || message.isEmpty()) return;

        String type = configManager.getCooldownMessageType();
        if (type == null || type.isEmpty() || "none".equalsIgnoreCase(type)) return;

        int remain = cooldownRegistry == null
                ? player.getCooldown(item.getType())
                : cooldownRegistry.remaining(player, item.getType());
        String text = message
                .replace("{ticks}", String.valueOf(remain))
                .replace("{seconds}", String.format("%.1f", remain / 20.0));

        if ("chat".equalsIgnoreCase(type)) {
            player.sendMessage(text);
        } else {
            // 默认 actionbar：不刷屏，自动覆盖
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText(text));
        }
    }

    // ══════════════ 日志 ══════════════

    public void log(String message) {
        Bukkit.getConsoleSender().sendMessage(PREFIX + color(message));
    }

    public void debug(String message) {
        if (configManager != null && configManager.isDebug()) {
            Bukkit.getConsoleSender().sendMessage(PREFIX + "&8[DEBUG] &7" + message);
        }
    }

    public static String color(String input) {
        return input == null ? "" : ChatColor.translateAlternateColorCodes('&', input);
    }

    // ══════════════ 内部 ══════════════

    private void setupNbtMatcher() {
        if (Bukkit.getPluginManager().getPlugin("NBTAPI") != null) {
            try {
                nbtMatcher = new NbtApiMatcher();
                log("&aNBTAPI &7已接入 - NBT 引擎: &b" + nbtMatcher.getName()
                        + " &7(支持任意 NBT 路径与多层嵌套)");
                return;
            } catch (Throwable t) {
                log("&cNBTAPI 插件存在但 API 加载失败，回退原生实现: " + t.getMessage());
            }
        }
        nbtMatcher = new PdcMatcher();
        log("&e未检测到 NBTAPI &7- NBT 引擎: &b" + nbtMatcher.getName());
        log("&7提示: 安装 NBTAPI 插件后可检测任意 NBT 路径与更深的多层嵌套。");
    }

    /** 版权横幅（纯 ASCII，避免 Windows 控制台 GBK 编码乱码） */
    private void printBanner() {
        String version = getDescription().getVersion();
        String[] lines = {
                "+-------------------------------------------------------+",
                "|                                                       |",
                "|   Item CD  v" + version + "                                        |",
                "|   Item Cooldown Control System                        |",
                "|                                                       |",
                "|   Copyright (c) Keran Technology Co., Ltd.            |",
                "|   All rights reserved.                                |",
                "|                                                       |",
                "+-------------------------------------------------------+"
        };
        for (String line : lines) {
            Bukkit.getConsoleSender().sendMessage(ChatColor.AQUA + line);
        }
    }
}
