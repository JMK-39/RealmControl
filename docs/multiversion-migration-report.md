# 多版本迁移验收（2026-10-03）

## 支持状态

| 节点 | 构建 | 已有游戏版本启动 |
| --- | --- | --- |
| 1.20.1-forge | 完整离线 build 通过 | PCL2 启动 OTHERWORLD CLASH，已进入标题界面 |
| 1.21.1-neoforge | 完整离线 build 通过 | PCL2 已进入主菜单，运行时夹具通过 |
| 26.1.2-neoforge | 属性已预留，节点未启用 | 缺少对应 KineticCore 产物，尚未移植或测试 |

`gradlew buildAll --offline` 同时生成 `D:/NEWMODS/realmcontrol-forge-1.20.1-26.10.3.jar` 和 `realmcontrol-neoforge-1.21.1-26.10.3.jar`。两个节点共享上海日期版本号，使用 Java 21 字节码、Gradle 9.8.0、Stonecutter 0.9.8 和 MDG 2.0.148。发布动作尚未执行。

## 构建与代码变化

- 根构建拆分为 Stonecutter controller、Forge/NeoForge 脚本和共用节点脚本。CI 使用 `buildAll`，产物包含加载器和 MC 版本。
- 固定第三方依赖优先本地库；核心按 loader/MC 精确筛选，兼容旧名仅限 Forge 1.20.1。显式版本保持严格，离线不访问 GitHub。保留用户迁移前 Java 21 / 核心 26.10.3 意图，原有未提交差异保存在 `.gradle/migration/preexisting.patch`。
- SavedData、信标 NBT 接口、指南针 payload 和 KubeJS 世界事件接口按 1.21.1 签名适配。三类持久化数据的文件名与 NBT 字段保持不变。
- 1.21.1 物品规则支持组件写法，物品读取通过核心升级旧 `id{NBT}`。规则匹配保留原始组件补丁及旧 NBT 约束，避免完整 ItemStack 和 DataFixer 丢掉默认值或部分附魔字段。旧 NBT 仍采用子集匹配，包括额外附魔；显式组件采用组件值匹配，custom_data 保留 NBT 子集行为。旧 GunId 标识继续排除 Ammo 等可变字段。
- Forge 保留 JAVA_17 Mixin JSON、refmap、MixinConfigs 和 reobfJar；NeoForge 使用 JAVA_21、独立 TOML 与 Mojang 名称，无 refmap。配置和语言文件未改动。

## 本地依赖

1.20.1 保留原有固定坐标。1.21.1 使用 Architectury 13.0.11、Jade 15.10.6、KubeJS 2101.7.2-build.377、Rhino 2101.2.8-build.91、Better Advanced Tooltips 2101.1.0-build.5、修改版探索者指南针 1.21.1-3.0.4 和自然指南针 1.21.1-3.4.0。KubeJS 要求 Better Advanced Tooltips，因本地 flatDir 无传递元数据，已显式声明。

Modrinth 文件来自对应项目的官方版本 API，已核对 SHA-512；修改版指南针来自 [CurseForge 官方文件 8234657](https://www.curseforge.com/minecraft/mc-mods/explorers-compass-edited/files/8234657)。没有把修改版替换为原版。坐标见 `build.neoforge.gradle`，下载元数据保存在本地 `.gradle/migration/`。

## 验证证据

- `.gradle/migration/build-final.log`：双节点完整 buildAll、SavedData 检查、架构检查、最终核心引用和 Mixin 目标检查通过；核心引用各 0 个问题，19 个 Mixin 各 0 个新问题、2 个已知提示。
- `gradle/Verify-ReleaseJar.ps1`：两个 JAR 各 144 个类、字节码 65、4 个 Mixin 配置。检查 refmap/清单、对应加载器 TOML、核心范围 `[26.10.3,)`、pack.mcmeta JSON 和测试夹具未进入发布包。
- 迁移前 Forge JAR 对比：仅新增版本兼容辅助类 ItemRuleData；语言及其他资源一致，允许的元数据差异为清单、TOML 和 pack.mcmeta。
- `checkSavedDataRegression` 在两个节点验证区块加载/信标位置半径、玩家及全局引用/离线时间、固定与加权区块标记的存盘往返，并覆盖负数区块坐标和 long 边界。
- 1.21.1 已用 PCL2 原设置启动，进入主菜单；最终实际加载器日志于 14:58:59 输出 `REALMCONTROL_RUNTIME_VALIDATION_PASS`。夹具验证旧 NBT/组件物品规则、错误输入拒绝、GunId 稳定标识、额外附魔子集匹配与等级差异拒绝、实际 BeaconBlockEntity Mixin NBT 往返及三类 SavedData。
- 复核发现显式默认值会被 ItemStack 补丁规范化删除。新增用例在修复前实际触发 `explicit default component remains a constraint`；修复后同一夹具全部通过，并覆盖 damage=0、!custom_data、旧 Damage:0 / RepairCost:0、只指定附魔 ID、额外附魔和错误 NBT 标量类型。红绿运行日志单独保留。
- 旧名称/Lore 的 JSON 字符串与实际 codec 稳定化结果不同，也已通过新增夹具复现失败；规则侧仅对文本组件采用相同 codec 规范化后，名称自匹配、不同名称拒绝及 Lore 子集测试全部通过，其他组件仍保留原始部分字段约束。
- 1.20.1 于 14:42:27 使用 PCL2 启动，进入整合包标题界面，日志显示 `Game took 35.701 seconds to start`；RealmControl Mixin 正常应用，无该附属加载失败。后续 NeoForge 条件代码修复未改变 Forge JAR 的类及资源内容，逐项比较仅清单构建时间变化。
- 首次运行发现 pack.mcmeta 模板重复写入 supported_formats 的 JSON 键，已修正并增加发布包 JSON 检查；再次运行没有该元数据错误。夹具里的 damage=invalid 警告是错误输入测试的预期输出。
- 运行证据在本地 `.gradle/migration/runtime-evidence/`，测试结束后夹具移至游戏版本的 `codex-migration-backup/realmcontrol-20261003`。仅使用用户已有 F:/game 版本，没有下载游戏或修改 PCL 内存设置。

## 已知检查提示与边界

- `ExplorersCompassMixins$EnhancedLogic.realmcontrol_tpd$authorizeEnhancedTeleport` 使用通配目标和条件插件；检查器按单方法统计时包含构造器，形成原有提示。实际安装的修改版使用 LegacyLogic 路径。
- `TeleportCommandMixin.realmcontrol_tpd$interceptPos` 用 `@Coerce Object` 接收私有 LookAt 类型；检查器不理解此签名转换。两项登记于 known_mixin_issues，保留运行配置，不借迁移改变玩法。

未验证玩家实际传送权限、专用服务端、世界生成替换、区块强加载和多人同步。启动与存盘夹具不能代替这些玩法场景；整合包自身的其他模组警告未纳入本次修复。

26.1.2 仍需对应核心依赖、代码移植和完整验收，当前预留不代表支持。没有推送或发布 Release。
