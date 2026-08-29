package cc.keran.itemcd.command;

import cc.keran.itemcd.ItemCD;
import cc.keran.itemcd.config.CheckGroup;
import cc.keran.itemcd.config.CheckRule;
import cc.keran.itemcd.config.MatchResult;
import cc.keran.itemcd.config.Trigger;
import cc.keran.itemcd.hook.CrackShotHook;
import cc.keran.itemcd.hook.PacketHook;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /itemcd 管理命令：reload / info
 */
public class ItemCDCommand implements CommandExecutor, TabCompleter {

    private final ItemCD plugin;
    private final List<String> subCommands = Arrays.asList("reload", "info", "nbt", "probe");

    public ItemCDCommand(ItemCD plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("itemcd.admin")) {
            sender.sendMessage(ItemCD.color("&c你没有权限执行此命令。"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.reload();
                sender.sendMessage(ItemCD.color("&a[ItemCD] &7配置已重载 - &b"
                        + plugin.getConfigManager().getGroups().size()
                        + " &7个集合 / &b" + plugin.getConfigManager().getCheckCount() + " &7条检测。"));
                break;

            case "info":
                sendInfo(sender);
                break;

            case "nbt":
                dumpNbt(sender);
                break;

            case "probe":
                probe(sender);
                break;

            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    /** 打印手持物品的完整 NBT，便于对照书写检测的 nbt 结构 */
    private void dumpNbt(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ItemCD.color("&c该命令仅玩家可执行。"));
            return;
        }
        Player player = (Player) sender;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            sender.sendMessage(ItemCD.color("&c请先手持一个物品。"));
            return;
        }

        String nbt = plugin.dumpItemNbt(item);
        if (nbt == null) {
            sender.sendMessage(ItemCD.color("&c当前 NBT 引擎不支持导出完整 NBT。"));
            sender.sendMessage(ItemCD.color("&7请安装 &bNBTAPI &7插件后重启服务器。"));
            return;
        }

        sender.sendMessage(ItemCD.color("&8&m----------&r &b手持物品 NBT &8&m----------"));
        sender.sendMessage(ItemCD.color("&7材质: &a" + item.getType().name()));
        // NBT 原文可能含 & 字符，此处不做颜色码转换，避免破坏原始内容
        for (String line : split(nbt, 56)) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
        sender.sendMessage(ItemCD.color("&8&m-------------------------------"));
    }

    /**
     * 诊断手持物品的冷却链路。
     *
     * <p>用于排查「为什么枪还在开火」这类问题：依次确认
     * 协议层是否启用 -> 物品是否命中检测 -> 冷却是否真的施加。</p>
     */
    private void probe(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ItemCD.color("&c该命令仅玩家可执行。"));
            return;
        }
        Player player = (Player) sender;
        ItemStack item = player.getInventory().getItemInMainHand();

        String bar = ChatColor.DARK_GRAY.toString() + ChatColor.STRIKETHROUGH
                + "----------------" + ChatColor.RESET + " " + ChatColor.AQUA
                + "ItemCD 诊断" + ChatColor.RESET + " " + ChatColor.DARK_GRAY
                + ChatColor.STRIKETHROUGH + "----------------";

        sender.sendMessage(bar);

        // ── 拦截链路 ──
        boolean plib = Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
        boolean cs = Bukkit.getPluginManager().getPlugin("CrackShot") != null;
        PacketHook hook = plugin.getPacketHook();
        CrackShotHook csHook = plugin.getCrackShotHook();

        sender.sendMessage(ItemCD.color("&7CrackShot: "
                + (cs ? "&a已安装" : "&c未安装 &7(CrackShot 枪械将无法被拦截)")));

        if (csHook != null && csHook.isActive()) {
            sender.sendMessage(ItemCD.color("&7CrackShot 官方拦截: &a已启用"
                    + " &8(冷却中的枪械连全自动都打不出去)"));
        } else {
            sender.sendMessage(ItemCD.color("&7CrackShot 官方拦截: &c未启用"));
            sender.sendMessage(ItemCD.color("   &8-> &e冷却中的 CrackShot 枪械无法被拦截"));
            sender.sendMessage(ItemCD.color("   &8-> &7请确认服务器已安装 CrackShot 且其正常加载"));
        }

        sender.sendMessage(ItemCD.color("&7ProtocolLib: "
                + (plib ? "&a已安装" : "&c未安装 &7(协议层不可用)")));

        if (hook != null && hook.isActive()) {
            sender.sendMessage(ItemCD.color("&7协议层拦截: &a已启用"));
            sender.sendMessage(ItemCD.color("   &8拦截包: &b"
                    + String.join("&7, &b", hook.getActiveTypeNames())));
            sender.sendMessage(ItemCD.color("   &8模式: &7"
                    + plugin.getConfigManager().getBlockInteractionMode()));
        } else {
            sender.sendMessage(ItemCD.color("&7协议层拦截: &c未启用"));
            sender.sendMessage(ItemCD.color("   &8-> &eCrackShot / CrackShotPlus 的枪械将无法被拦截"));
            sender.sendMessage(ItemCD.color("   &8-> &7请安装 ProtocolLib 并确认 packet-block.enabled=true"));
        }

        sender.sendMessage(ItemCD.color("&7事件层拦截: "
                + (plugin.getConfigManager().isBlockInteraction()
                ? "&a开启 &8(仅对原版物品有效)" : "&7关闭")));

        if (player.hasPermission("itemcd.bypass")) {
            sender.sendMessage(ItemCD.color("&c注意: 你拥有 itemcd.bypass 权限，所有冷却对你无效！"));
        }

        // ── 手持物品 ──
        sender.sendMessage("");
        if (item.getType() == Material.AIR) {
            sender.sendMessage(ItemCD.color("&c主手为空，请手持要诊断的物品。"));
            sender.sendMessage(ItemCD.color("&8&m----------------------------------------"));
            return;
        }

        String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? item.getItemMeta().getDisplayName() : "&8(无自定义名称)";

        sender.sendMessage(ItemCD.color("&7材质: &a" + item.getType().name()));
        sender.sendMessage(ItemCD.color("&7名称: &r" + name));

        MatchResult result = plugin.findMatch(player, item);
        if (result == null) {
            sender.sendMessage(ItemCD.color("&c匹配结果: 未命中任何检测"));
            sender.sendMessage(ItemCD.color("   &8-> &7该物品不会进入冷却，也不会被拦截"));
            sender.sendMessage(ItemCD.color("   &8-> &7用 &b/itemcd nbt &7查看物品实际 NBT，核对配置"));
        } else {
            CheckGroup group = result.getGroup();
            CheckRule rule = result.getRule();
            sender.sendMessage(ItemCD.color("&a匹配结果: &b" + group.getId()
                    + " &8/ &b" + rule.getId()));

            StringBuilder cd = new StringBuilder();
            for (Trigger t : Trigger.values()) {
                String raw = rule.getRawCooldown(t);
                if (raw != null && !raw.isEmpty() && !"0".equals(raw)) {
                    cd.append("&8").append(t.getKey()).append("=&a").append(raw).append("&7  ");
                }
            }
            sender.sendMessage(ItemCD.color("&7冷却设置: "
                    + (cd.length() == 0 ? "&8(全部为 0，不会检测)" : cd.toString().trim())));

            boolean cooling = player.hasCooldown(item.getType());
            sender.sendMessage(ItemCD.color("&7当前冷却: "
                    + (cooling ? "&a是 &8(剩余 &b" + player.getCooldown(item.getType()) + "t&8)"
                    : "&7否")));
            if (cooling && hook != null && hook.isActive()) {
                sender.sendMessage(ItemCD.color("   &8-> &7此时点击不应有任何反应（包已被丢弃）"));
            }
        }

        sender.sendMessage(ItemCD.color("&8&m----------------------------------------"));
    }

    private List<String> split(String text, int length) {
        List<String> lines = new ArrayList<>();
        if (text == null) return lines;
        for (int i = 0; i < text.length(); i += length) {
            lines.add(text.substring(i, Math.min(i + length, text.length())));
        }
        return lines;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ItemCD.color("&8&m----------------&r &bItemCD &8&m----------------"));
        sender.sendMessage(ItemCD.color("&7/itemcd reload  &8- &7重载配置"));
        sender.sendMessage(ItemCD.color("&7/itemcd info    &8- &7查看当前加载状态"));
        sender.sendMessage(ItemCD.color("&7/itemcd nbt     &8- &7打印手持物品的完整 NBT"));
        sender.sendMessage(ItemCD.color("&7/itemcd probe   &8- &7诊断手持物品的冷却链路"));
        sender.sendMessage(ItemCD.color("&8&m----------------------------------------"));
    }

    private void sendInfo(CommandSender sender) {
        sender.sendMessage(ItemCD.color("&8&m----------------&r &bItemCD 状态 &8&m--------------"));
        sender.sendMessage(ItemCD.color("&7版本: &a" + plugin.getDescription().getVersion()));
        sender.sendMessage(ItemCD.color("&7NBT 引擎: &a" + plugin.getNbtMatcher().getName()));
        sender.sendMessage(ItemCD.color("&7PlaceholderAPI: "
                + (plugin.getPlaceholderHook().isEnabled() ? "&a已接入" : "&c未安装")));

        PacketHook hook = plugin.getPacketHook();
        boolean plib = Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
        sender.sendMessage(ItemCD.color("&7协议层拦截: "
                + (hook != null && hook.isActive()
                ? "&a已启用 &8(" + String.join("&7,&b ", hook.getActiveTypeNames()) + "&8)"
                : (plib ? "&eProtocolLib 已装但未启用" : "&c未安装 ProtocolLib"))));

        boolean cs = Bukkit.getPluginManager().getPlugin("CrackShot") != null;
        CrackShotHook csHook = plugin.getCrackShotHook();
        sender.sendMessage(ItemCD.color("&7CrackShot 官方拦截: "
                + (csHook != null && csHook.isActive()
                ? "&a已启用 &8(冷却中枪械无法开火)"
                : (cs ? "&eCrackShot 已装但未启用" : "&c未安装 CrackShot"))));

        sender.sendMessage(ItemCD.color("&7调试模式: "
                + (plugin.getConfigManager().isDebug() ? "&a开启" : "&7关闭")));
        sender.sendMessage(ItemCD.color("&7检测集合: &a" + plugin.getConfigManager().getGroups().size()
                + " &8| &7检测规则: &a" + plugin.getConfigManager().getCheckCount()));

        for (CheckGroup group : plugin.getConfigManager().getGroups()) {
            String ignore = group.getIgnoreFrom().isEmpty()
                    ? "&8无" : "&e" + String.join("&7, &e", group.getIgnoreFrom());
            sender.sendMessage(ItemCD.color("&8 - &b" + group.getId()
                    + " &8(&7" + group.getChecks().size() + " 条&8) &7豁免来源: " + ignore));

            for (CheckRule rule : group.getChecks()) {
                StringBuilder cd = new StringBuilder();
                for (Trigger trigger : Trigger.values()) {
                    cd.append("&8").append(trigger.getKey()).append("=&7")
                            .append(rule.getRawCooldown(trigger)).append(" ");
                }
                sender.sendMessage(ItemCD.color("     &8* &7" + rule.getId()
                        + " &8" + describeRule(rule) + " &8| " + cd.toString().trim()));
            }
        }
        sender.sendMessage(ItemCD.color("&8&m----------------------------------------"));
    }

    private String describeRule(CheckRule rule) {
        StringBuilder sb = new StringBuilder();
        if (rule.getMaterial() != null) sb.append("mat=").append(rule.getMaterial().name()).append(" ");
        if (rule.getDisplayName() != null) {
            sb.append("name=").append(rule.getDisplayName())
                    .append(rule.isFuzzy() ? "(fuzzy)" : "(exact)").append(" ");
        }
        if (rule.getNbt() != null) sb.append("nbt=").append(rule.getNbt().keySet());
        return sb.toString().trim();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("itemcd.admin")) return new ArrayList<>();
        if (args.length != 1) return new ArrayList<>();

        List<String> result = new ArrayList<>();
        String input = args[0].toLowerCase();
        for (String sub : subCommands) {
            if (sub.startsWith(input)) result.add(sub);
        }
        return result;
    }
}
