# Realm Control

[English](#english) | [简体中文](#简体中文)

## English

### Overview

A server-focused world management toolkit for controlling future world generation, structure placement, beacon-based area behavior, chunk loading, hostile spawning around beacons, and teleport authorization.

The project is designed around in-game administration. Where a feature changes shared gameplay data or server rules, the server remains authoritative; client-only presentation features stay local to the client. Configuration screens use KineticCore's UI and configuration infrastructure.

### Key Features

- Block-generation banning, unification, fixed replacement and weighted random replacement.
- Natural structure enable/disable rules, weights, spacing, separation, salt and placement parameters.
- Beacon-powered chunk loading with quotas, offline handling, range visualization and hostile-spawn protection.
- Configurable `/tp` and `/teleport` authorization with count-based and time-based grants.
- Server-authoritative configuration through the KineticCore configuration center.
- Optional Jade integration for contextual information.

### Dependencies

| Type | Dependency |
|---|---|
| Required | Forge 47.4.0+ |
| Required | KineticCore 26.9.8+ |
| Optional | Jade 11+ |

### Access and Configuration

- Open the KineticCore configuration center with its configured F6 entry and select **Realm Control**.
- Server-owned settings are saved by the server and synchronized where the feature requires client awareness.
- Client-only presentation settings remain local.
- Individual feature areas document their own data/configuration paths below.
- Search, list selection, item/entity inspection, tooltips and return/navigation controls reuse KineticCore UI components where available.

## Detailed Feature Reference

### World Block Rules

#### Overview

**World Block Rules** is the world-generation block management module for this project. It can ban, unify, replace, or randomly replace blocks while new chunks are being generated.

The standard edition targets future world generation only and does not scan already-generated chunks.

#### Key Features

- Ban selected naturally generated blocks.
- Unify multiple source blocks into a target block.
- Works with ordinary blocks as well as ores and modded blocks.
- Shared BlockState property preservation where possible.
- Per-rule fixed replacement chance.
- Weighted random replacement with multiple targets.
- Separate overall trigger chance for weighted rules.
- Visual source/target block selection and category filtering.
- Server-authoritative configuration persistence.
- Runtime caches for high-frequency world-generation rewrites.

#### Configuration

```text
config/kineticcore/worldblock.json
```

#### Scope

By default, World Block Rules affects newly generated world content only. The fixed-merge and weighted-replacement editors also provide an optional **Loaded Chunks** mode for one-time conversion of existing chunks. This mode is latched at game startup: changing the switch in the editor only changes the next-launch state and requires a full game restart. When the mode is disabled at startup, its chunk events, tick handler, processing queue, and chunk-state tracking are not registered, keeping its runtime overhead effectively equivalent to a build without the feature.

When this switch is disabled, no loaded-chunk block scan or rewrite queue runs. When enabled, currently loaded existing chunks and existing chunks loaded later are processed gradually, at most one chunk per server tick. Processing cost depends on the number of loaded old chunks and world height, so large pregenerated worlds should be converted in batches.

### Feature Reference
#### Config Details
| Item | Description |
|---|---|
| **World Block Rule Editors** | Manages unification, replacement, and generation bans for naturally generated blocks. |
| **Open Ore Merge Editor** | Edit ore unification and replacement rules using the dedicated item selector. |
| **Open Ore Ban Editor** | Edit ore-generation bans and replacements without manually writing configuration. |
| **Weighted Random Block Replacement** | Configure multiple weighted replacement targets for one source block. A weighted roll only happens when that source block is actually replaced. |

#### GUI and Editors
| Item | Description |
|---|---|
| **search** | Search blocks / #tag / @mod |
| **search** | Search items (@mod #tag) |
| **Done** | After selecting all source blocks to replace, click here and then choose the single final target block. |
| **New Rule** | Choose one source block to replace, then choose one or more random target blocks. |
| **Done** | Select at least one target before finishing. Every new target starts with weight 100. |
| **rule** | Left-click to edit this rule  Right-click to delete this rule |
| **Weight:** | Weight range: 1–1000000. Probability is calculated as current weight divided by the total weight of all selected targets. |
| **source** | Left-click to use this block as the source for random replacement. |
| **selected** | Current weight: %s Actual probability: %s%% |
| **remove** | Right-click to remove this block from the random targets. |
| **add** | Left-click to add this random target with default weight 100. |

#### Editable Options
- Target: %s Available sources: %s Filter: %s
- Ban This Rule
- Unban This Rule
- Select source blocks that will be replaced Selected: %s Available: %s
- Selected %s source blocks. Now choose the final replacement target Available: %s
- Left-click an existing rule to edit it, right-click to delete it, or create a new weighted rule.
- Choose one source block that will be randomly replaced.
- Choose random replacement targets Selected: %s Left-click select/edit, right-click remove

#### Data Paths
Primary configuration/data paths:

- `config/kineticcore/worldblock.json`

### Structure Generation

#### Overview

**Structure Generation** is the natural structure-generation manager for this project. It reads the live server structure registry and provides in-game editing for structure enablement, weight, frequency, and placement parameters.

The module only affects natural structure generation in future chunks and never removes structures that have already generated.

#### Structure Features
- Server registry snapshot of structures and StructureSets.
- Per-structure natural-generation disabling.
- Per-entry structure weight editing.
- StructureSet frequency and salt editing.
- Random-spread spacing, separation, and spread-type controls.
- Concentric-ring distance, spread, and count controls.
- Safer limited editing for custom placement implementations.
- Restore-to-original values captured from the live server registry.
- Clear handling of shared StructureSet parameters.

#### Configuration

```text
config/kineticcore/worldgen.toml
```

The `worldgen` configuration contains the structure-generation settings used by this module.

#### Commands

```text
/kt world structure
/kt world list-structures
/kt world help
```

### Feature Reference
#### Config Details
| Item | Description |
|---|---|
| **Structure Generation Rules** | Manage natural structure generation parameters here. Adjust per-structure disabling, weight, generation density, and placement values using the live server structure registry. |
| **Open Structure Generation Rule Editor** | Edit natural structure generation parameters. Adjust structure weight, generation density, and placement values, or restore the startup defaults read from the server registry. |

#### GUI and Editors
| Item | Description |
|---|---|
| **Disable Structure** | Disabling gives this structure an effective natural generation chance of 0. In a shared StructureSet only this candidate is removed; if the whole set is disabled, its frequency is set to 0. |
| **Spread Algorithm** | Random-spread algorithm. Linear uses linear distribution; Triangular uses triangular distribution. |
| **Restore Default** | Restores the exact original values captured from the server structure registry at startup. Shared StructureSet placement values are restored for sibling structures too. |

#### Commands
| Item | Description |
|---|---|
| **world** | Structure Generation Management command help |
| **list structures** | List all structure IDs |
| **structure** | Query structures at current location |

#### Config Defaults
| Key | Default |
|---|---|
| `structure_control.enable` | `true` |

#### Data Paths
Primary configuration/data paths:

- `config/kineticcore/worldgen.toml`

### Beacon Areas

#### Overview

**Beacon Areas** extends vanilla beacons into configurable chunk-loading and protected-area controllers.

#### Key Features

- Beacon-powered chunk loading.
- Loading radius based on beacon level.
- Global loaded-chunk quota.
- Optional per-player quotas.
- Offline timeout and automatic deactivation rules.
- Hostile natural-spawn prevention inside beacon areas.
- Client status/quota synchronization.
- Beacon range visualization and enhanced tooltips.
- Optional Jade integration.
- Beacon level-change event support for scripting integrations.

#### Configuration

```text
config/kineticcore/beacon.toml
```

Server-side beacon rules are persisted by the server through the KineticCore configuration system.

### Feature Reference
#### Config Details
| Item | Description |
|---|---|
| **Beacon Enhancements** | Server-authoritative beacon behavior. Both singleplayer and multiplayer read and save through the active server; editing requires permission level 2. |
| **Beacon Chunk Loading** | Controls whether beacons may keep surrounding chunks active and sets the radius for each beacon level. |
| **Beacon Chunk Loading** | Allow active beacons to force-load chunks around themselves. |
| **Beacon Level Radii (Chunks)** | Four entries correspond to beacon levels 1-4. Radius 0 loads 1x1 chunks, 1 loads 3x3, and values are clamped from -1 to 16. |
| **spawn** | Controls global entity spawn interception inside protected beacon areas. |
| **Enable Beacon Spawn Prevention** | Prevent configured entity spawn types inside active beacon protection ranges. |
| **Spawn Whitelist** | Entities in this list may spawn inside protected beacon areas. |
| **Spawn Blacklist** | Entities in this list are always blocked inside protected beacon areas. |
| **Advanced Spawn Rules** | One entry per line: entity ID; interception codes, for example minecraft:zombie;AE. |
| **limits** | Limits the total number of unique chunks force-loaded globally and, optionally, per player. |
| **Global Chunk Load Limit** | Maximum unique chunks force-loaded by all beacons across all dimensions. |
| **Enable Per-Player Limit** | Also enforce a separate force-loaded chunk quota for each beacon owner. |
| **Per-Player Limit** | Maximum unique chunks assigned to one player; values above the global limit are reduced when saved. |
| **offline** | Selects what beacon functions stop after their owner has remained offline for the configured duration. |
| **Offline Timeout (Mins)** | Minutes after logout before offline actions apply. 0 is immediate and -1 disables the timeout. |
| **Deactivate Completely on Timeout** | Deactivate the beacon light and effects completely after its owner times out. |
| **Disable Chunk Load on Timeout** | Stop this owner's beacon chunk loading after the timeout. |
| **Disable Spawn Prevent on Timeout** | Stop this owner's beacon spawn prevention after the timeout. |

#### GUI and Editors
| Item | Description |
|---|---|
| **chunk load** | When enabled, this beacon will keep chunks within its radius loaded. |
| **spawn prevent** | When enabled, hostile mobs will be prevented from spawning within the beacon's radius. |
| **cl rad** | Enter chunk load radius.<br>Enter -1 to use current max limit(%s).<br>(Empty means -1) |
| **sp rad** | Enter spawn prevention radius.<br>Enter -1 to use current max limit(%s).<br>(Empty means -1) |

#### Editable Options
- All Entities
- Hostile Only
- Passive Only
- Global Rules

#### Config Defaults
| Key | Default |
|---|---|
| `beacon.enableChunkLoading` | `true` |
| `beacon.limits.globalLimit` | `500` |
| `beacon.limits.perPlayerEnable` | `false` |
| `beacon.limits.perPlayerLimit` | `100` |
| `beacon.offline.deactivate` | `true` |
| `beacon.offline.disableChunkLoad` | `true` |
| `beacon.offline.disableSpawnPrevent` | `true` |
| `beacon.offline.timeout` | `4320` |
| `beacon.spawnPrevention.enable` | `true` |

#### Data Paths
Primary configuration/data paths:

- `config/kineticcore/beacon.toml`

### Teleport Authorization

#### Overview

**Teleport Authorization** is the teleport authorization and `/tp` control module for this project. It can turn the vanilla `/tp` and `/teleport` commands into a configurable player authorization system with count-based or time-based teleport access.

#### Key Features

- Optional control of vanilla `/tp` and `/teleport`.
- `FREE` mode for unrestricted teleporting.
- `AUTHORIZED` mode requiring teleport credits or timed authorization.
- Authorized normal players may teleport themselves, not arbitrary entities.
- Count-based teleport grants.
- Timed unlimited-teleport grants.
- Self-status and administrator status queries.
- Administrator authorization clearing.
- Configurable level-2 administrator bypass; permission levels 3/4 remain privileged.
- Custom denial message with `&` formatting codes and `{player}` placeholder.
- Persistent player authorization data.
- Shared authorization logic for Explorer's Compass and Nature's Compass teleports.

#### Commands

```text
/kt tpd
/kt tpd help
/kt tpd check <player>
/kt tpd allow count <player> <num>
/kt tpd allow time <player> <sec>
/kt tpd clear <player>
```

#### Configuration

```text
config/kineticcore/teleport.toml
```

### Feature Reference
#### Config Details
| Item | Description |
|---|---|
| **Admin Bypass** | On: permission level 2+ bypasses authorization limits. Off: level 2 also needs authorization, while levels 3/4 always bypass. |
| **Deny Teleport Message** | Custom message shown when teleport authorization is missing.<br>Supports & color codes and the {player} placeholder; leave blank to use the translated default message. |
| **Teleport Auth Mode** | FREE: everyone may teleport freely. AUTHORIZED: normal players and non-bypassed level-2 admins need use-based or timed authorization. |
| **Enable TP Command Override** | Allows the mod to modify vanilla /tp and /teleport command logic. |
| **Teleport Authorization Settings** | Controls authorization for vanilla /tp and /teleport, including the master switch, admin bypass, auth mode, and denial message. Teleport uses/time are still managed with /kt tpd. |

#### Commands
| Item | Description |
|---|---|
| **tpd** | Teleport auth system help |

#### Config Defaults
| Key | Default |
|---|---|
| `teleport.adminBypass` | `true` |
| `teleport.denyCustomMessage` | `""` |
| `teleport.enableModify` | `true` |
| `teleport.mode` | `"AUTHORIZED"` |

#### Data Paths
Primary configuration/data paths:

- `config/kineticcore/teleport.toml`

## 简体中文

### 模组定位

面向服务器与整合包的世界管理工具，集中控制未来区块中的方块生成、结构生成、信标区域行为与区块加载，以及玩家传送授权。

本项目以游戏内管理为核心。涉及共享玩法数据、世界规则或服务器规则的功能由服务端权威处理；仅影响显示的客户端功能保持本地生效。配置界面统一使用 KineticCore 提供的 GUI 与配置基础设施。

### 主要功能

- 支持自然生成方块禁用、统一、固定概率替换与多目标权重随机替换。
- 支持结构启停、结构权重、间距、分离距离、Salt 与多类 Placement 参数编辑。
- 支持信标驱动的区块加载、全局/玩家配额、离线处理、范围显示与敌对生物生成保护。
- 支持对 `/tp` 与 `/teleport` 实施次数授权、限时授权与管理员旁路。
- 配置由服务端权威保存，并通过 KineticCore 配置中心进行可视化管理。
- 可选支持 Jade 信息显示。

### 依赖

| 类型 | 依赖 |
|---|---|
| 必需 | Forge 47.4.0+ |
| 必需 | KineticCore 26.9.8+ |
| 可选 | Jade 11+ |

### 打开方式与配置

- 使用 KineticCore 配置中心对应的 F6 入口，选择 **Realm Control**。
- 服务端规则由服务端保存，并在需要时同步给客户端。
- 纯显示类客户端设置只在本地生效。
- 各功能自己的配置/数据路径在下方详细功能说明中列出。
- 搜索、列表选择、物品/实体信息读取、悬浮提示、返回与导航等操作尽可能复用 KineticCore GUI 组件。

## 完整功能参考

### 世界方块规则

#### 模组定位

**World Block Rules** 是 本项目中的世界自然生成方块管理模块。它用于在新区块生成过程中对目标方块执行生成封禁、统一替换和加权随机替换，适合处理重复矿石、重复装饰方块、整合包矿物统一以及世界生成规则整理。

本模块只处理之后新生成的世界内容，不会扫描并修改已经生成完成的旧区块。

#### 主要功能

- **自然生成方块封禁**：指定方块在新区块自然生成时直接被替换为空气。
- **普通方块统一**：把多个来源方块统一替换成一个目标方块。
- **不限矿石类型**：规则目标可以是普通方块、矿石、装饰方块以及模组方块，不局限于 Ore。
- **方块状态继承**：替换时会尽可能复制来源与目标共有的 BlockState 属性。
- **固定替换概率**：普通替换规则可以配置 0-100% 的触发概率。
- **加权随机替换**：一个来源方块可以配置多个目标方块，并为每个目标设置独立权重。
- **随机替换总概率**：除了目标之间的权重，还可以单独设置整条随机替换规则实际触发的概率。
- **同类过滤**：编辑合并规则时可以根据标签/类别缩小可选方块范围。
- **可视化规则编辑**：使用真实方块图标选择来源与目标，不需要直接手写配置。
- **服务端权威保存**：世界生成规则由服务器保存并同步，联机编辑需要管理权限。
- **高频路径缓存**：运行时会预先构建常用方块与 BlockState 替换缓存，减少世界生成阶段重复解析配置。

#### 配置文件

```text
config/kineticcore/worldblock.json
```

主要数据包括：

- `bannedOreGenerations`：自然生成封禁规则。
- `oreMergedItems`：固定方块统一 / 替换规则。
- `weightedBlockReplacements`：加权随机替换目标。
- `blockReplacementChances`：普通替换概率。
- `weightedBlockReplacementChances`：随机替换总概率。

#### 常用入口

通过 `F6` 进入 **World Block Rules** 页面，可打开：

- 矿物 / 方块生成封禁编辑器。
- 矿物 / 方块合并编辑器。
- 加权随机方块替换编辑器。

`/kt reload` 可以重新读取规则并同步服务器当前配置。

#### 适用范围

World Block Rules 默认主要影响 **新区块自然生成过程**。固定替换与加权替换编辑器现在也提供“已加载区块”开关，用于对旧区块进行一次性转换：

- 关闭该开关时不会执行旧区块方块扫描或重写队列，只有极轻量的区块状态记录。
- 开启后，当前已加载旧区块和之后加载的旧区块会逐步处理，每个服务器 Tick 最多处理 1 个区块。
- 大量预生成区块或高世界高度会增加转换期间的 CPU 开销，建议分批加载旧区域。

- 不会修改玩家背包里的同名物品。
- 不会修改玩家手动放置的方块。
- 不会自动重写已经生成的旧区块。

如果需要对已有地图或已加载区块执行一次性重写，请使用 **existing-chunk rewrite tooling** 版本。

### 完整功能参考

#### 配置项详细说明

| 项目 | 说明 |
|---|---|
| **世界方块规则编辑器** | 管理自然生成方块的统一、替换与生成封禁。 |
| **打开矿物合并编辑器** | 编辑矿物统一与替换规则，使用专用物品选择界面。 |
| **打开矿物封禁编辑器** | 编辑矿物生成封禁和替换规则，避免直接手写配置。 |
| **加权随机方块替换** | 为一个来源方块配置多个随机替换目标和权重；只有实际命中来源方块时才进行一次加权抽取。 |

#### 界面操作与编辑器说明

| 项目 | 说明 |
|---|---|
| **search** | 搜索方块 / #标签 / @模组 |
| **search** | 搜索物品 (支持@模组 #标签) |
| **完成** | 来源方块选择完成后点击这里，再选择唯一的最终目标方块。 |
| **新建规则** | 先选择一个将被替换的来源方块，然后选择一个或多个随机目标方块。 |
| **完成** | 至少选择一个目标方块后完成当前规则。每个新目标默认权重为 100。 |
| **rule** | 左键编辑此规则  右键删除此规则 |
| **权重：** | 权重范围 1–1000000。概率按“当前权重 ÷ 所有目标权重总和”自动计算。 |
| **source** | 左键选择这个方块作为随机替换的来源。 |
| **selected** | 当前权重：%s 实际概率：%s%% |
| **remove** | 右键从当前随机目标中移除。 |
| **add** | 左键加入随机目标，默认权重 100。 |

#### 可编辑字段、模式与分类索引

- 主方块：%s 可选来源：%s 过滤：%s
- 封禁此规则
- 解封此规则
- 请选择将被替换的来源方块 已选：%s 当前可选：%s
- 已选择 %s 个来源方块，现在请选择最终要替换成的目标方块 当前可选：%s
- 左键现有规则进行编辑，右键删除；点击“新建规则”添加随机替换。
- 请选择一个将被随机替换的来源方块。
- 请选择随机替换目标 已选：%s 左键选择/编辑，右键移除

#### 配置与数据路径

主要配置/数据路径：

- `config/kineticcore/worldblock.json`

### 结构生成

#### 模组定位

**Structure Generation** 是 本项目中的结构自然生成管理模块。它允许整合包作者直接读取服务器当前结构注册表，并在游戏内调整结构是否生成、生成权重、密度和放置参数。

模块只影响之后新区块的结构自然生成，不会删除已经生成完成的结构。

#### 结构自然生成编辑

- **结构列表预览**：从服务器注册表读取当前可用结构与 StructureSet 数据。
- **结构禁用**：将指定结构的自然生成机会设为 0，而不是在区块生成主线程中强行删除结构。
- **单结构生成权重**：调整同一 StructureSet 中不同候选结构的权重。
- **生成密度 `frequency`**：调整整个 StructureSet 的自然生成密度。
- **Salt**：编辑随机放置使用的 Salt。
- **随机分布参数**：支持 `spacing`、`separation` 与 Linear / Triangular 扩散算法。
- **同心环参数**：支持 `distance`、`spread` 与 `count`。
- **自定义放置保护**：遇到模组自定义 Placement 类型时，只开放安全的单结构禁用和权重配置，避免错误改写第三方结构算法。
- **原始值快照**：服务器启动时读取真实结构注册表中的原始参数，可在编辑器内恢复默认。
- **共享 StructureSet 提示**：密度、Salt 和放置参数属于 StructureSet；修改时会明确提示同组结构会一起受到影响。
- **修改项置顶**：已改结构在列表中自动置顶，方便继续检查和维护。

#### 配置文件

```text
config/kineticcore/worldgen.toml
```

文件只保留结构生成相关配置：

- 结构生成规则总开关。
- 结构禁用列表。
- 单结构权重规则。
- StructureSet 放置参数覆盖规则。

#### 常用入口

- `F6`：进入 **Structure Generation** 页面。
- 结构生成规则编辑器：编辑结构自然生成参数。
- `/kt world structure`：查询当前所在位置的结构。
- `/kt world list-structures`：列出服务器当前结构 ID，需要等级 2 管理权限。
- `/kt world help`：查看结构生成相关命令帮助。

#### 生效说明

- 结构生成规则保存后用于之后的新区块。
- 结构放置覆盖通常在重新进入存档或重启服务器后完整应用。
- 已经生成完成的结构不会被删除。

### 完整功能参考

#### 配置项详细说明

| 项目 | 说明 |
|---|---|
| **结构生成规则** | 这里统一管理结构自然生成参数。可调整结构禁用、权重、生成密度与放置参数，数据来自服务器当前结构注册表。 |
| **打开结构生成规则编辑器** | 编辑结构自然生成参数。可调整结构权重、生成密度及对应放置参数，并可恢复服务器启动时读取到的原始值。 |

#### 界面操作与编辑器说明

| 项目 | 说明 |
|---|---|
| **禁用结构** | 禁用后该结构的自然生成概率为 0。共享 StructureSet 中只移除这个候选；整组都被禁用时将整组 frequency 设为 0。 |
| **扩散算法** | 随机分布算法。Linear 为线性分布，Triangular 为三角分布。 |
| **恢复默认** | 恢复服务器启动时从真实结构注册表读取到的原始值。共享 StructureSet 的放置参数会同时恢复同组结构。 |

#### 命令功能说明

| 项目 | 说明 |
|---|---|
| **world** | 结构生成管理指令帮助 |
| **list structures** | 列出所有结构ID |
| **structure** | 查询所在位置的结构 |

#### 配置键与默认值

| 配置键 | 默认值 |
|---|---|
| `structure_control.enable` | `true` |

#### 配置与数据路径

主要配置/数据路径：

- `config/kineticcore/worldgen.toml`

### 信标区域

#### 模组定位

**Beacon Areas** 是 本项目中的信标强化模块，主要用于把原版信标扩展为可配置的区域强加载与安全区域系统。

#### 主要功能

- **信标区块强加载**：允许有效信标持续加载周围区块，让机器或区域逻辑在无人停留时继续运行。
- **按信标等级控制范围**：不同信标等级可以对应不同的强加载半径。
- **全局强加载上限**：限制全部维度中由信标产生的强加载区块总量。
- **玩家个人额度**：可启用每位玩家的独立信标强加载配额，避免单个玩家占满服务器额度。
- **离线超时处理**：玩家离线超过设定时间后，可自动停用其信标的强加载和/或防刷怪效果。
- **区域防刷怪**：可在信标有效范围内阻止敌对生物自然生成。
- **状态与额度同步**：客户端可以查看当前信标状态、额度与相关提示。
- **范围可视化**：提供信标范围渲染，方便服主和玩家确认实际覆盖区域。
- **Tooltip 扩展**：在信标相关界面显示更明确的强加载和安全区域信息。
- **Jade 兼容**：安装 Jade 后可显示信标扩展信息。
- **KubeJS 事件**：提供信标等级变化事件，便于脚本联动。

#### 配置文件

```text
config/kineticcore/beacon.toml
```

主要包括：

- 强加载总开关与等级半径。
- 全局区块额度与玩家额度。
- 玩家离线超时规则。
- 离线后是否关闭强加载。
- 离线后是否关闭防刷怪。
- 信标区域防刷怪规则。

服务端规则通过 KineticCore 的服务端配置体系保存和同步。

### 完整功能参考

#### 配置项详细说明

| 项目 | 说明 |
|---|---|
| **信标增强功能** | 服务器权威的信标全局行为。单人和多人都通过当前服务器读取和保存；需要 OP 2 级权限才能编辑。 |
| **信标强加载** | 控制信标是否可以维持周围区块运行，并设置各信标等级对应的半径。 |
| **信标强加载** | 允许已激活的信标强制加载周围区块。 |
| **信标等级对应半径 (区块)** | 四项依次对应 1-4 级信标。半径 0 为 1x1 区块、1 为 3x3；数值会限制在 -1 到 16。 |
| **spawn** | 控制信标保护范围内的全局实体生成拦截规则。 |
| **开启信标范围内防刷怪** | 在已激活信标的保护范围内拦截配置指定的实体生成方式。 |
| **生成白名单** | 列表中的生物允许在信标保护范围内生成。 |
| **生成黑名单** | 列表中的生物在信标保护范围内始终禁止生成。 |
| **高级生成规则** | 每行格式：实体 ID;拦截代码，例如 minecraft:zombie;AE。 |
| **limits** | 限制全局以及可选的单个玩家能够强加载的唯一性区块数量。 |
| **全局强加载区块上限** | 所有维度、所有信标合计可强加载的唯一性区块上限。 |
| **启用玩家独立上限** | 同时按信标所有者分别限制强加载区块额度。 |
| **玩家个人强加载上限** | 单个玩家可分配的唯一性区块上限；超过全局上限的值会在保存时下调。 |
| **offline** | 选择信标所有者离线达到指定时长后需要停止的信标功能。 |
| **离线失效时间 (分钟)** | 玩家下线后等待多少分钟执行离线操作。0 表示立即执行，-1 表示永不超时。 |
| **超时后完全失效(光柱消失)** | 所有者超时后完全停用信标光柱和效果。 |
| **超时后关闭强加载** | 所有者超时后停止其信标的区块强加载。 |
| **超时后关闭防刷怪** | 所有者超时后停止其信标的生成拦截。 |

#### 界面操作与编辑器说明

| 项目 | 说明 |
|---|---|
| **chunk load** | 开启后，该信标将保持其影响范围内的区块始终加载 |
| **spawn prevent** | 开启后，将阻止信标影响范围内的敌对生物自然生成 |
| **cl rad** | 输入强加载半径(区块)。<br>输入 -1 以使用当前等级配置上限(%s)。<br>(留空视为 -1) |
| **sp rad** | 输入防刷怪半径(区块)。<br>输入 -1 以使用当前等级配置上限(%s)。<br>(留空视为 -1) |

#### 可编辑字段、模式与分类索引

- 全部生物
- 仅敌对
- 仅被动
- 全局规则

#### 配置键与默认值

| 配置键 | 默认值 |
|---|---|
| `beacon.enableChunkLoading` | `true` |
| `beacon.limits.globalLimit` | `500` |
| `beacon.limits.perPlayerEnable` | `false` |
| `beacon.limits.perPlayerLimit` | `100` |
| `beacon.offline.deactivate` | `true` |
| `beacon.offline.disableChunkLoad` | `true` |
| `beacon.offline.disableSpawnPrevent` | `true` |
| `beacon.offline.timeout` | `4320` |
| `beacon.spawnPrevention.enable` | `true` |

#### 配置与数据路径

主要配置/数据路径：

- `config/kineticcore/beacon.toml`

### 传送授权

#### 模组定位

**Teleport Authorization** 是 本项目中的传送授权与 `/tp` 行为控制模块。它可以把原版 `/tp`、`/teleport` 从单纯的管理员指令改造成可配置的玩家传送授权系统，并提供按次数或按时间发放的传送额度。

#### 主要功能

- **接管 `/tp` 与 `/teleport`**：可独立开关本模组对原版传送指令的修改。
- **FREE 模式**：允许玩家自由使用传送功能。
- **AUTHORIZED 模式**：普通玩家必须拥有剩余次数或限时授权才能传送。
- **仅允许传送自己**：普通授权玩家不能借授权去移动其他玩家或实体。
- **次数授权**：管理员可以给玩家增加指定次数的传送额度，每次成功传送消耗一次。
- **限时授权**：管理员可以给玩家一段时间内的无限传送权限。
- **授权状态查询**：玩家可以查看自己的剩余次数和剩余时间，管理员可以查询其他玩家。
- **授权清空**：管理员可一键清除指定玩家的次数和时间授权。
- **管理员绕过**：可设置权限等级 2 是否直接绕过；权限等级 3/4 始终保持高级管理豁免。
- **自定义拒绝提示**：支持 `&` 颜色代码和 `{player}` 玩家名占位符。
- **授权数据随玩家保存**：剩余次数和到期时间绑定到玩家数据，在正常重登后继续有效。
- **罗盘传送联动**：Explorer's Compass 与 Nature's Compass 的传送行为接入同一套授权消耗逻辑。

#### 常用命令

```text
/kt tpd
/kt tpd help
/kt tpd check <player>
/kt tpd allow count <player> <num>
/kt tpd allow time <player> <sec>
/kt tpd clear <player>
```

其中授权发放、查询其他玩家和清空授权需要等级 2 管理权限。

#### 配置文件

```text
config/kineticcore/teleport.toml
```

主要配置包括：

- 是否接管 `/tp` 与 `/teleport`。
- 管理员是否绕过授权。
- `FREE` / `AUTHORIZED` 模式。
- 无授权时的自定义提示文本。

#### 罗盘兼容

当前实现包含对以下传送功能的直接兼容：

- Explorer's Compass
- Nature's Compass

通过罗盘执行传送时，同样会检查并消耗 Teleport Authorization 的传送授权。

### 完整功能参考

#### 配置项详细说明

| 项目 | 说明 |
|---|---|
| **管理员忽略限制** | 开启：权限等级 2 及以上可绕过授权限制。关闭：等级 2 也需要授权，但等级 3/4 始终豁免。 |
| **拒绝传送提示语** | 当玩家没有权限传送时显示的自定义消息。<br>支持 & 颜色代码和 {player} 玩家名占位符；留空则显示默认提示。 |
| **传送鉴权模式** | FREE：所有玩家可自由传送。AUTHORIZED：普通玩家和未豁免的等级 2 管理员需要次数或限时授权。 |
| **启用 TP 指令接管** | 是否允许本模组修改原版 /tp 和 /teleport 指令的行为逻辑。 |
| **传送权限设置** | 接管原版 /tp 与 /teleport 的授权规则，可在这里设置总开关、管理员豁免、鉴权模式和拒绝提示。授权次数/时长仍通过 /kt tpd 管理。 |

#### 命令功能说明

| 项目 | 说明 |
|---|---|
| **tpd** | 传送权限系统帮助 |

#### 配置键与默认值

| 配置键 | 默认值 |
|---|---|
| `teleport.adminBypass` | `true` |
| `teleport.denyCustomMessage` | `""` |
| `teleport.enableModify` | `true` |
| `teleport.mode` | `"AUTHORIZED"` |

#### 配置与数据路径

主要配置/数据路径：

- `config/kineticcore/teleport.toml`
