2026年10月05日 23时46分 — 26.10.5

- Saving the world-block item rules (ore merging, banned ores, weighted block merging) answers only the admin who saved; the rules are no longer sent to every online player. Other players receive them when they log in. The /reload command still refreshes everyone.
- Built against KineticCore 26.10.5, whose lists, buttons and text fields keep text clear of frames and scroll long text instead of cutting it off.

- 保存世界方块物品规则（矿物合并、禁用矿物、加权方块合并）只回复保存的管理员，不再发送给所有在线玩家；其他玩家在登录时获得规则。/reload 命令仍会刷新所有玩家。
- 基于 KineticCore 26.10.5 构建：其列表、按钮与输入框的文字不压边框，过长文字滚动显示而不截断。

---

2026年10月04日 13时42分 — 26.10.4

- Added NeoForge 26.1.2 support (Java 25, KineticCore 26.10.3+). Beacon chunk loading and spawn prevention, teleport authorization, world-block item rules and world generation controls work on 26.1.2; existing saves keep their data.
- On 26.1.2 the Explorer's Compass and Nature's Compass teleport authorization follows the mods' 26.1 releases; item rules use 26.1's /give syntax.
- The KubeJS integration is built for 26.1.2 but untested: KubeJS's current 26.1 release fails to start a 26.1.2 server (a KubeJS issue).
- Checked on 26.1.2 and 1.21.1 dedicated servers: saved data round trips, beacon data, item rules and server start-up.

- 新增 NeoForge 26.1.2 支持（Java 25，需要 KineticCore 26.10.3+）。信标区块加载与刷怪抑制、传送授权、世界方块物品规则和世界生成控制均可在 26.1.2 中使用，已有存档的数据保持不变。
- 26.1.2 中探险家指南针与自然指南针的传送授权适配这两个模组的 26.1 版本；物品规则使用 26.1 的 /give 写法。
- KubeJS 联动已针对 26.1.2 构建但尚未测试：KubeJS 当前的 26.1 版本无法启动 26.1.2 服务端（KubeJS 自身的问题）。
- 已在 26.1.2 与 1.21.1 专用服务端中检查：存档数据往返、信标数据、物品规则与服务端启动。

---

2026年10月04日 — Language key validation / 语言键一致性检查

- Require identical authored English/Chinese keys and string values in source, version overrides and packaged resources; generated formatting keys are rejected during builds.

- 强制检查源码、版本覆盖与最终资源的中英文完整键名一致、值为字符串；构建禁止派生格式语言键。

---

2026年10月03日 16时51分 — 26.10.3

- Added NeoForge 1.21.1 support alongside Forge 1.20.1, using Java 21 and matching KineticCore 26.10.3+.
- Adapted saved world/beacon data and 1.21.1 item-component rules while retaining legacy item-NBT rule compatibility.
- Fixed untranslated Jade beacon information and the 1.21.1 teleport-command callback.
- Both builds and targeted runtime checks passed; world generation, ordinary-player teleport permissions, and dedicated servers have not been fully tested.
- The 26.1.2 node is reserved and disabled; it is not a supported release.

- 新增 NeoForge 1.21.1 支持，同时保留 Forge 1.20.1；使用 Java 21 和对应版本的 KineticCore 26.10.3+。
- 适配世界与信标存储，以及 1.21.1 物品组件规则；保留旧物品 NBT 规则兼容。
- 修复 Jade 信标信息显示原始翻译键，以及 1.21.1 传送命令回调。
- 两个版本构建及针对性运行检查通过；世界生成、普通玩家传送权限和专用服务端尚未完整测试。
- 26.1.2 节点仅预留、未启用，不代表已支持。

---

2026年10月02日 13时53分

- Removed 11 unused imports without changing behavior.
- Enabled addon architecture validation. The full build and final-JAR API verification passed.

- 删除 11 个未使用引用，不改变功能。
- 接入附属架构检查，完整构建和最终 JAR API 检查通过。

---

2026年09月29日（原记录未标注小时、分钟）

- Updated the editors for beacon controls, world block rules, world generation, and biome rules.
- Improved Explorer's Compass teleport authorization across supported versions, including use limits and remaining-time feedback.
- Updated compatibility with KineticCore 26.9.29.

- 更新信标控制、世界方块规则、世界生成和生物群系规则编辑器。
- 改进对不同受支持版本 Explorer's Compass 的传送授权处理，并完善次数与剩余时间提示。
- 更新对 KineticCore 26.9.29 的兼容。
