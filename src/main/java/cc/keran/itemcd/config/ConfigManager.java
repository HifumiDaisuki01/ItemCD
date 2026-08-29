package cc.keran.itemcd.config;

import cc.keran.itemcd.ItemCD;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置加载器：解析 groups / checks / nbt / cooldown 为运行时对象。
 */
public class ConfigManager {

    private final ItemCD plugin;
    private List<CheckGroup> groups = new ArrayList<>();
    private boolean debug;

    /** 冷却中是否拦截交互（让第三方武器真正无法使用） */
    private boolean blockInteraction = true;
    /** 冷却中交互被拦截时的提示文本；空 = 不提示 */
    private String cooldownMessage = "";
    /** 提示方式：actionbar / chat / none */
    private String cooldownMessageType = "actionbar";

    /** 拦截范围：all = 全部交互；air-only = 仅对空气的交互（保留对方块的操作） */
    private String blockInteractionMode = "all";

    /** 协议层（数据包）拦截开关；需要服务器安装 ProtocolLib */
    private boolean packetBlockEnabled = true;
    /** 协议层需要拦截的包类型名 */
    private List<String> packetBlockTypes = new ArrayList<>();

    public ConfigManager(ItemCD plugin) {
        this.plugin = plugin;
    }

    public boolean isPacketBlockEnabled() {
        return packetBlockEnabled;
    }

    public List<String> getPacketBlockTypes() {
        return packetBlockTypes;
    }

    public List<CheckGroup> getGroups() {
        return Collections.unmodifiableList(groups);
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isBlockInteraction() {
        return blockInteraction;
    }

    public String getCooldownMessage() {
        return cooldownMessage;
    }

    public String getCooldownMessageType() {
        return cooldownMessageType;
    }

    public String getBlockInteractionMode() {
        return blockInteractionMode;
    }

    public int getCheckCount() {
        int n = 0;
        for (CheckGroup g : groups) n += g.getChecks().size();
        return n;
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        debug = cfg.getBoolean("debug", false);

        blockInteraction = cfg.getBoolean("block-interaction-during-cooldown", true);
        cooldownMessage = CheckRule.colorize(cfg.getString("cooldown-message", ""));
        cooldownMessageType = cfg.getString("cooldown-message-type", "actionbar");
        blockInteractionMode = cfg.getString("block-interaction-mode", "all");

        // ── 协议层拦截 ──
        packetBlockEnabled = cfg.getBoolean("packet-block.enabled", true);
        List<String> rawTypes = cfg.getStringList("packet-block.types");
        packetBlockTypes = (rawTypes == null || rawTypes.isEmpty())
                ? defaultPacketTypes() : new ArrayList<>(rawTypes);

        List<CheckGroup> loaded = new ArrayList<>();

        ConfigurationSection root = cfg.getConfigurationSection("groups");
        if (root == null) {
            plugin.log("&e配置中未找到 groups 节点，未加载任何检测集合。");
            groups = loaded;
            return;
        }

        for (String groupId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(groupId);
            if (section == null) continue;

            List<String> ignoreFrom = section.getStringList("ignore-from");

            List<CheckRule> rules = new ArrayList<>();
            int index = 0;
            for (Map<?, ?> raw : section.getMapList("checks")) {
                index++;
                CheckRule rule = parseRule(groupId, index, raw);
                if (rule != null) rules.add(rule);
            }

            if (rules.isEmpty()) {
                plugin.log("&e集合 [" + groupId + "] 未配置任何有效检测，已跳过。");
                continue;
            }

            loaded.add(new CheckGroup(groupId, ignoreFrom, rules));
            plugin.log("&7已加载集合 &b" + groupId + " &7- &a" + rules.size()
                    + " &7条检测, 豁免来源: " + (ignoreFrom.isEmpty() ? "无" : String.join(", ", ignoreFrom)));
        }

        groups = loaded;
    }

    /** 协议层默认拦截的包类型 */
    private List<String> defaultPacketTypes() {
        List<String> defaults = new ArrayList<>();
        defaults.add("USE_ITEM");     // 右键使用物品（CrackShot 右键开火）
        defaults.add("BLOCK_PLACE");  // 右键放置（部分武器走此包）
        defaults.add("BLOCK_DIG");    // 左键（CrackShot 左键开火 / 近战）
        defaults.add("USE_ENTITY");   // 攻击或右键实体
        return defaults;
    }

    private CheckRule parseRule(String groupId, int index, Map<?, ?> raw) {
        String id = raw.get("id") == null ? (groupId + "#" + index) : String.valueOf(raw.get("id"));

        // ── 种类1：材质 ──
        Material material = null;
        Object rawMat = raw.get("material");
        if (rawMat != null) {
            String matName = String.valueOf(rawMat).trim();
            if (!matName.isEmpty()) {
                Material parsed = Material.matchMaterial(matName);
                if (parsed == null) {
                    plugin.log("&c集合 [" + groupId + "] 检测 [" + id + "] 材质无效: " + matName + "（该检测将忽略材质判定）");
                } else {
                    material = parsed;
                }
            }
        }

        // ── 种类2：名称 ──
        String displayName = null;
        Object rawName = raw.get("display-name");
        if (rawName != null) {
            String name = CheckRule.colorize(String.valueOf(rawName));
            if (!name.isEmpty()) displayName = name;
        }

        boolean fuzzy = true;
        Object rawFuzzy = raw.get("fuzzy");
        if (rawFuzzy instanceof Boolean) {
            fuzzy = (Boolean) rawFuzzy;
        }

        // ── 种类3：NBT ──
        Map<String, Object> nbt = null;
        Object rawNbt = raw.get("nbt");
        if (rawNbt instanceof Map) {
            nbt = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawNbt).entrySet()) {
                nbt.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }

        // ── 冷却 ──
        Map<Trigger, String> cooldowns = new EnumMap<>(Trigger.class);
        Object rawCd = raw.get("cooldown");
        if (rawCd instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawCd).entrySet()) {
                Trigger trigger = Trigger.fromKey(String.valueOf(entry.getKey()));
                if (trigger == null) {
                    plugin.log("&c集合 [" + groupId + "] 检测 [" + id + "] 存在未知触发类型: " + entry.getKey());
                    continue;
                }
                if (entry.getValue() != null) {
                    cooldowns.put(trigger, String.valueOf(entry.getValue()));
                }
            }
        }

        // ── 冷却时禁止瞄准（block-scope: true / false）──
        boolean blockScope = false;
        Object rawScope = raw.get("block-scope");
        if (rawScope instanceof Boolean) {
            blockScope = (Boolean) rawScope;
        } else if (rawScope != null) {
            blockScope = "true".equalsIgnoreCase(String.valueOf(rawScope).trim());
        }

        if (material == null && displayName == null && (nbt == null || nbt.isEmpty())) {
            plugin.log("&c集合 [" + groupId + "] 检测 [" + id + "] 三类检测均未设置，已忽略。");
            return null;
        }

        return new CheckRule(id, material, displayName, fuzzy, nbt, cooldowns, blockScope);
    }
}
