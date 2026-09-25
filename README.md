# Realm Control

[English](#english) | [简体中文](#chinese)

<a id="english"></a>

## English

Realm Control provides visual administration for world blocks, structure and biome generation, beacon areas, and teleport authorization. Use it to shape a modpack's future terrain, configure beacon infrastructure, and give players controlled access to teleport commands.

### Installation and access

- Current build target: **Minecraft 1.20.1**, **Forge 47.4.2+**, and **KineticCore 26.9.20+**.
- Install Realm Control and KineticCore on the client and server for multiplayer use.
- Optional: **Jade 11.0.0+** for beacon information.
- Enter a world, press **F6**, and choose **Realm Control** in KineticCore. The key is configurable in Controls.
- Global server settings and world-generation editors require **permission level 2**. Individual beacon controls also enforce ownership where applicable.

### World block generation and replacement

The World Blocks module has dedicated editors for generation bans, fixed replacement, and weighted replacement.

- **Generation bans:** select exact blocks or create block-tag/mod rules to exclude matching blocks during generation.
- **Fixed replacement:** select one or more source blocks, click Done, and choose the final target block.
- **Weighted replacement:** select a source and several targets, then assign each target a positive weight. A target's share is its weight divided by the total.
- Replacement chance and target weight serve different purposes: chance decides whether replacement occurs, while weights select a target after a successful replacement roll.
- Use source/target groups and searches to manage duplicate ores or other generated blocks in a large modpack.

For example, a weighted rule can replace a source with stone and deepslate at weights 3 and 1. Among successful replacements, their shares are 75% and 25%; these shares do not replace the rule's own replacement-chance setting.

Saved block rules affect subsequent generation. Existing terrain is handled only by the separate loaded-chunk replacement feature described below.

### Optional one-time replacement in existing chunks

Fixed and weighted replacement each have their own **Loaded Chunks** switch. Both default to off.

- Enabling either switch takes effect only after a **full game/server process restart**. Saving or `/kt reload` cannot register or unregister this feature live.
- When enabled at startup, old chunks are queued when they load and processed at most **one chunk per tick**.
- Each chunk is marked after processing, separately for fixed and weighted replacement. Changing rules does not automatically rescan chunks already completed for that rule type.
- The operation uses block contents, not a record of whether a block was placed by a player. Matching blocks in existing builds can therefore also change.
- This feature does not regenerate structures or replace stored biomes in existing terrain.

Use this switch when existing terrain should be migrated, and review source rules against the world before enabling it. With both switches off at startup, the chunk-scanning handlers and processing queue are not installed.

### Structures

The structure editor reads the active server registry and shows the generation parameters available for each structure.

- Disable an individual structure or change its weight within a structure set.
- Change generation frequency and salt.
- For random-spread placement, adjust spacing, separation, and linear/triangular spread type.
- For concentric-ring placement, adjust distance, spread, and count.
- Restore startup defaults captured from the server registry.
- Use the editor's locate/teleport tools to inspect enabled structures; cross-dimension structures may require changing dimension first.

Placement settings belong to a **shared StructureSet**. Editing or restoring them can affect sibling structures in the same set. Disabling one candidate does not automatically disable its siblings.

**Save All**, then reopen the world or restart the server to apply generation changes. They affect future chunks; they do not move, remove, or regenerate structures already present. Refreshing the registry list preserves unsaved local edits.

### Biomes

The biome editor supports replacing a biome, replacing biomes matched by a tag, removing matching climate entries, and restricting a rule to one dimension or all dimensions.

- A source can be a biome ID or `#biome_tag`; a replacement uses a target biome ID.
- Rules can target a specific dimension, or `*` for all dimensions.
- Removal excludes the matching entries from biome selection rather than turning existing terrain into empty space.
- Implementation applies to **MultiNoiseBiomeSource** dimensions. It does not apply to every custom biome source or vanilla's distinct End biome source.
- If removal would eliminate every climate entry in a dimension, that result is rejected instead of leaving an empty biome source.

Save biome rules and reload the world/restart the server. New generation uses the updated selection; already stored chunk biomes remain as generated.

For manual file editing, entries use `dimension|source|target`, for example `minecraft:overworld|minecraft:desert|minecraft:plains`. The target `null` means removal; the visual editor handles this format for you.

### Beacon areas

Open an active beacon's interface to configure its chunk-loading and spawn-prevention controls, then click **Apply Settings**.

- Enable chunk loading and choose a radius within the beacon-level limit. Radius 0 covers 1×1 chunks; radius 1 covers 3×3. The local value `-1` selects the current level's maximum.
- Set a separate spawn-prevention radius and target all entities, hostile entities, or passive entities.
- Leave interception codes empty to use global rules, or supply codes for specific spawn sources.
- Global lists allow or block selected entity IDs. Within an applicable protected area/target category, the allowlist is checked before the blocklist.
- Global and optional per-owner quotas count unique force-loaded chunks across dimensions.
- Offline settings control when an absent owner's beacon loses effects, chunk loading, or spawn prevention.
- Range rendering, quota information, and optional Jade details help inspect beacon coverage.

Interception codes: **A** natural, **B** conversion, **C** command, **D** egg/bucket/dispenser, **E** spawner, **F** summoned, **G** event/reinforcement, **H** breeding. Without local codes or an entity-specific global rule, the default interception is natural spawning.

Generated defaults use level radii `[0, 1, 2, 3]`, a global limit of **500 chunks**, and an offline timeout of **4320 minutes**. Per-player quotas are initially disabled. Timeout `0` is immediate; `-1` disables the timeout. Saved interception rules update immediately; loaded beacon radius, quota, and offline state may need a beacon update or world reload.

### Teleport authorization and commands

The teleport module changes vanilla `/tp` and `/teleport`. **FREE** mode permits unrestricted player use; **AUTHORIZED** mode requires remaining uses or an unexpired timed grant. A master switch disables the modification. Authorized players subject to these limits can teleport themselves, not other entities.

Admin bypass normally exempts permission level 2+. If disabled, level 2 also needs authorization; levels 3 and 4 still bypass. A custom denial message supports `&` colors and `{player}`.

| Command | Purpose / access |
| --- | --- |
| `/kt world help` | Structure-command help |
| `/kt world structure` | Inspect structures at the player's position |
| `/kt world list-structures` | List registered structures; level 2 |
| `/kt tpd` | Check your own teleport authorization |
| `/kt tpd help` | Teleport-authorization help |
| `/kt tpd allow count <players> <amount>` | Add teleport uses; level 2 |
| `/kt tpd allow time <players> <seconds>` | Add timed authorization; level 2 |
| `/kt tpd check <player>` | Inspect another player's authorization; level 2 |
| `/kt tpd clear <players>` | Clear both uses and timed authorization; level 2 |
| `/kt reload` | Reload registered module configuration; level 2 |

For example, `/kt tpd allow count Alex 3` adds three uses, and `/kt tpd allow time Alex 300` grants five minutes. Counts are added to existing uses; time extends the current expiry if it has not expired. Timed grants use elapsed real time.

### Files and application scope

| File under `config/kineticcore/` | Contents / application |
| --- | --- |
| `worldblock.json` | Block bans, fixed/weighted targets, chances, startup-only loaded-chunk switches |
| `worldblock.old.json` | Companion backup for world-block rules |
| `worldgen.toml` | Structure and biome rules; apply on world/server start |
| `beacon.toml` | Global beacon behavior, quotas, and offline rules |
| `teleport.toml` | Teleport mode, bypass, master switch, and denial message |

World data stores beacon chunk-loading state and one-time chunk rewrite markers. Teleport grants are player data. `/kt reload` reloads registered block, worldgen, and teleport configuration; it does not re-create an already running world's generation setup or replace a full restart for loaded-chunk mode.

<a id="chinese"></a>

## 简体中文

Realm Control 提供世界方块、结构与群系生成、信标区域和传送授权的可视化管理。可用于调整整合包的后续地形、配置信标设施，以及向玩家发放有限的传送权限。

### 安装与入口

- 当前构建目标：**Minecraft 1.20.1**、**Forge 47.4.2+**、**KineticCore 26.9.20+**。
- 多人游戏时，客户端和服务端均安装 Realm Control 与 KineticCore。
- 可选：**Jade 11.0.0+** 显示信标信息。
- 进入世界后按 **F6**，在 KineticCore 中选择 **Realm Control**；可在按键设置中修改快捷键。
- 服务端全局设置和世界生成编辑器需要 **2 级权限**；单个信标设置还会在适用情况下检查所有权。

### 世界方块生成与替换

世界方块模块提供生成禁用、固定替换和加权随机替换三个专用编辑器。

- **生成禁用：**选择具体方块，或添加方块标签、模组规则，排除生成过程中的匹配方块。
- **固定替换：**选择一个或多个来源方块，点击完成，再选择最终目标方块。
- **加权替换：**选择来源与多个目标，为各目标设置正权重，目标占比为该权重除以总权重。
- 替换概率与目标权重作用不同：概率决定是否替换，权重在替换成功后决定选哪个目标。
- 利用分组和搜索管理大型整合包中重复矿石或其他生成方块。

例如，将某来源的随机目标设为石头和深板岩，权重分别为 3、1，则成功替换后两者占比为 75%、25%；这并不代替该规则本身的替换概率设置。

保存的方块规则影响后续生成。已有地形仅由下面单独的已加载区块替换功能处理。

### 可选的旧区块一次性替换

固定替换与加权替换各有独立的**已加载区块**开关，默认均关闭。

- 开关必须在**完整重启游戏或服务端进程**后生效。保存或 `/kt reload` 不能动态注册、移除该功能。
- 启动时开启后，旧区块在加载时排队处理，每 tick 最多处理 **1 个区块**。
- 固定和加权替换分别记录完成标记。修改规则不会自动重新扫描已完成同类替换的区块。
- 此操作依据方块内容判断，不依据玩家放置记录，因此已有建筑中的匹配方块也可能被替换。
- 该功能不会重新生成结构，也不会替换旧地形已经保存的群系。

需要迁移已有地形时再使用此开关，启用前检查来源规则与世界内容是否相符。若两个开关在启动时均关闭，就不会安装区块扫描处理器或处理队列。

### 结构生成

结构编辑器读取当前服务端注册表，展示每种结构可用的生成参数。

- 禁用单个结构，或修改其在结构集合内的权重。
- 修改生成频率与随机盐值。
- 随机分布放置可调整间距、最小间隔及线性、三角分布类型。
- 同心环放置可调整距离、扩散参数与数量。
- 恢复启动时从服务端注册表捕获的原始参数。
- 使用定位和传送工具检查已启用结构；其他维度的结构可能需要先切换维度。

放置参数属于**共享 StructureSet**。修改或恢复参数可能影响同一集合中的其他结构；禁用一个候选结构不会自动禁用其余候选。

点击 **Save All** 后，重新进入世界或重启服务端应用生成修改。修改仅影响未来区块，不移动、删除或重新生成已有结构。刷新注册表列表会保留尚未保存的本地编辑。

### 群系生成

群系编辑器支持替换单个群系、按标签替换、移除匹配的气候条目，以及限定维度或作用于全部维度。

- 来源可以是群系 ID 或 `#群系标签`，替换目标为群系 ID。
- 规则可指定维度，也可使用 `*` 表示全部维度。
- 移除会将匹配项排除出群系选择，不会把已有地形变为空地。
- 当前实现作用于使用 **MultiNoiseBiomeSource** 的维度，不覆盖所有自定义群系来源或原版末地的独立群系来源。
- 如果移除规则会清空某维度的全部气候条目，该结果会被拒绝，避免留下空群系来源。

保存后重新加载世界或重启服务端，新生成区域使用新选择结果；已保存区块的群系保持原状。

手动编辑时，条目格式为 `维度|来源|目标`，例如 `minecraft:overworld|minecraft:desert|minecraft:plains`。目标 `null` 表示移除；可视化编辑器会代为处理此格式。

### 信标区域

打开激活信标的界面，设定强加载与防生成控制后，点击 **Apply Settings** 应用。

- 开启区块加载，并在当前信标等级上限内设置半径。半径 0 覆盖 1×1 区块，半径 1 覆盖 3×3；单个信标填 `-1` 表示使用当前等级最大值。
- 单独设置防生成半径，目标可选全部实体、敌对实体或被动实体。
- 拦截代码留空使用全局规则，也可填写代码限制具体生成来源。
- 全局名单可允许或阻止指定实体 ID。在适用的保护区域与目标分类内，白名单先于黑名单判断。
- 全局配额及可选的玩家配额按跨维度去重后的强加载区块计算。
- 离线规则决定主人离线多久后关闭信标效果、区块加载或防生成。
- 范围渲染、配额信息及可选 Jade 信息可帮助检查覆盖范围。

拦截代码：**A** 自然、**B** 转化、**C** 命令、**D** 刷怪蛋/桶/发射器、**E** 刷怪笼、**F** 召唤、**G** 事件/增援、**H** 繁殖。没有本地代码或该实体专属全局规则时，默认拦截自然生成。

生成的默认等级半径为 `[0, 1, 2, 3]`，全局上限 **500 区块**，离线超时 **4320 分钟**，玩家独立配额默认关闭。超时 `0` 表示立即生效，`-1` 表示不超时。保存后拦截规则立即更新；已有信标的半径、配额和离线状态可能需要信标更新或重新加载世界。

### 传送授权与命令

传送模块修改原版 `/tp` 与 `/teleport`。**FREE** 模式允许玩家自由使用，**AUTHORIZED** 模式要求剩余次数或尚未过期的限时授权；总开关可关闭这一修改。受授权限制的玩家只能传送自己，不能传送其他实体。

管理员绕过默认豁免 2 级及以上权限。关闭绕过后，2 级也需要授权，3、4 级仍绕过。自定义拒绝消息支持 `&` 颜色码和 `{player}`。

| 命令 | 用途与权限 |
| --- | --- |
| `/kt world help` | 结构命令帮助 |
| `/kt world structure` | 检查玩家当前位置的结构 |
| `/kt world list-structures` | 列出已注册结构，需要 2 级权限 |
| `/kt tpd` | 查看自己的传送授权 |
| `/kt tpd help` | 传送授权帮助 |
| `/kt tpd allow count <玩家> <次数>` | 增加传送次数，需要 2 级权限 |
| `/kt tpd allow time <玩家> <秒数>` | 增加限时授权，需要 2 级权限 |
| `/kt tpd check <玩家>` | 查看其他玩家授权，需要 2 级权限 |
| `/kt tpd clear <玩家>` | 清空次数和限时授权，需要 2 级权限 |
| `/kt reload` | 重载已注册模块配置，需要 2 级权限 |

例如，`/kt tpd allow count Alex 3` 增加三次传送，`/kt tpd allow time Alex 300` 授权五分钟。次数累加到现有次数；限时授权未过期时会延长当前到期时间。限时授权使用现实经过时间。

### 文件与生效范围

| `config/kineticcore/` 下的文件 | 内容与生效方式 |
| --- | --- |
| `worldblock.json` | 方块禁用、固定/加权目标、概率和启动时读取的旧区块开关 |
| `worldblock.old.json` | 世界方块规则配套备份 |
| `worldgen.toml` | 结构、群系规则，在世界或服务端启动时应用 |
| `beacon.toml` | 信标全局行为、配额和离线规则 |
| `teleport.toml` | 传送模式、绕过、总开关与拒绝消息 |

信标强加载状态与旧区块一次性处理标记随世界保存，传送授权属于玩家数据。`/kt reload` 可重载已注册的方块、世界生成和传送配置，但不会重建运行中世界的生成设置，也不能代替旧区块模式所需的完整重启。
