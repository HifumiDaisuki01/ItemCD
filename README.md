# ItemCD

> **Minecraft 1.16.5 物品冷却控制插件** — 按自定义规则检测玩家物品，命中后施加原版物品冷却。
>
> Copyright (c) Keran Technology Co., Ltd. All rights reserved.

ItemCD 是一个为 Minecraft 服务器设计的**物品冷却控制工具**。你可以为任意物品定义检测规则：指定材质、名称或 NBT 结构，物品一旦符合规则并在特定时机被触发（切换、点击、攻击、消耗……），就会进入可自定义时长的冷却，冷却期间无法使用。

插件对 **CrackShot / CrackShotPlus 深度兼容**——包括冷却期间禁止开火、禁止瞄准等高级能力，让这两款枪械插件可以无缝纳入你的冷却体系。

---

## 功能特性

- **三类检测规则**，可组合使用：
  1. 检测特定**原版材质**（如 `DIAMOND_SWORD`）
  2. 检测特定**物品名称**（支持模糊 / 精确匹配）
  3. 检测 **NBT 结构**（支持任意深度嵌套，可精确识别具体武器）
- **七种触发时机**，每种可单独设置冷却时长（tick 为单位，支持 PlaceholderAPI 变量）：
  - `switch` 快捷栏切换
  - `swap` 主副手切换（含 CrackShotPlus 射击模式切换）
  - `inventory` 背包 ↔ 快捷栏互换
  - `left` / `right` 左键 / 右键点击
  - `attack` 造成伤害的攻击
  - `consume` 消耗物品（吃食物 / 喝药水）
- **多个检测集合**，每个集合可包含多条检测
- **跨集合豁免**：从集合 A 的物品切换到集合 B 的物品时，可跳过检测
- **冷却语义精细**：切换类触发每次重新计算，使用类触发防连点续期
- **深度兼容 CrackShot / CrackShotPlus**：
  - 冷却期间枪械**无法开火**（含全自动连发）
  - 冷却期间可**按检测单独决定是否禁止瞄准**
- **软依赖设计**：NBTAPI / PlaceholderAPI / ProtocolLib / CrackShot 均按需接入，缺省不影响启动

---

## 快速开始

1. 将 `ItemCD-1.5.2.jar` 放入 `plugins/` 目录
2. 启动服务器，生成 `plugins/ItemCD/config.yml`
3. 编辑配置后执行 `/itemcd reload`（无需重启）

### 依赖

| 插件 | 必需? | 作用 |
|---|---|---|
| — | 仅需 Paper 1.16.5 | 核心功能 |
| NBTAPI | 可选 | NBT 检测支持任意路径与多层嵌套 |
| PlaceholderAPI | 可选 | 冷却时长支持变量，如 `%player_level%` |
| CrackShot / CrackShotPlus | 可选 | 冷却期间禁止开火、瞄准 |
| ProtocolLib | 可选 | 对其他武器插件提供协议级兜底 |

---

## 配置示例

```yaml
# 冷却中禁止使用物品
block-interaction-during-cooldown: true
block-interaction-mode: all          # all / air-only

groups:

  # 示例：CrackShot 枪械集合
  crackshot:
    ignore-from: [melee]             # 从 melee 集合切来时不检测

    checks:
      - id: sniper-rifle
        material: DIAMOND_SWORD
        display-name: "&c狙击步枪"
        fuzzy: true
        nbt:
          PublicBukkitValues:
            "crackshotplus:wn": "AWM"
        block-scope: true            # 冷却时禁止开镜瞄准
        cooldown:
          swap: 60
          switch: 40
          inventory: 60
          left: 20
          right: 20
```

冷却单位：**tick**（20 tick = 1 秒）。填 `0` 表示该触发不检测。

---

## 命令与权限

| 命令 | 说明 |
|---|---|
| `/itemcd reload` | 重载配置 |
| `/itemcd info` | 查看当前加载状态 |
| `/itemcd nbt` | 打印手持物品的完整 NBT（配置 NBT 检测时用） |
| `/itemcd probe` | 诊断手持物品的冷却链路 |

| 权限 | 默认 |
|---|---|
| `itemcd.admin` | OP |
| `itemcd.bypass` | 无（免疫所有冷却检测） |

---

## 自行编译

```bash
mvn clean package
```

产物在 `target/ItemCD-1.5.2.jar`。

> 依赖说明：CrackShot / ProtocolLib 不在公开 Maven 仓库，请手动安装到本地仓库后以 `provided` 作用域引用（详见 pom.xml 注释）。

---

## License

Copyright (c) Keran Technology Co., Ltd. All rights reserved.
