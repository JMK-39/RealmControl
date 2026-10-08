2026年10月08日 — Block preview spacing / 方块预览间距

- Block generation and replacement editors show complete item icons on checker backgrounds, with space between cells and inside rule rows.
- Grid gaps no longer select neighbouring blocks. The existing panels, controls and replacement workflow stay in place across all supported versions.

- 方块生成与替换编辑器在棋盘格背景上完整显示物品图标，格子之间及规则行内部留有间距。
- 点击格子间隙不再选中相邻方块。各支持版本保留现有面板、控件位置与替换操作流程。

---

2026年10月08日 — Structure page hint / 结构页面提示

- Hint text on the structure page no longer runs onto the panel frame.

- 结构页面的提示文字不再压到面板边框。

---

2026年10月07日 00时45分 — Same layout in every language / 各语言排版一致

- The Replacement Chance label in the ore merge and weighted block merge editors and the weight label in the weighted block merge editor have fixed widths, so the fields after them sit in the same place in every language; longer labels scroll.
- The Loaded Chunks button and the chance field keep 2 px below the panel's top line, and the structure and biome lists keep 2 px from their frames.

- 矿物合并与加权方块合并编辑器的"替换概率"标签以及加权方块合并编辑器的"权重"标签使用固定宽度，其后的输入框在所有语言下位置相同；过长标签滚动显示。
- "已加载区块"按钮与概率输入框与面板顶边线保持 2 像素，结构列表与生物群系列表与其边框保持 2 像素。

---

2026年10月06日 08时21分 — 26.10.6

- In the weighted block merge editor, the yellow hint line ran under the Loaded Chunks button; it now ends before the button.
- In the ore merge and weighted block merge editors, a long Replacement Chance label pushed the Loaded Chunks button out of its panel or over other controls; the label now scrolls within its own space.
- In the structure rule editor, a long Structure Set line no longer runs under the Structure Enabled button.
- With KineticCore 26.10.6, long button and label text shows an ellipsis and scrolls on hover instead of being cut off.

- 加权方块合并编辑器中，黄色提示行会压到"已加载区块"按钮下面；现在在按钮之前结束。
- 矿物合并与加权方块合并编辑器中，过长的"替换概率"标签会把"已加载区块"按钮挤出面板或压到其他控件上；现在标签在自己的空间内滚动。
- 结构规则编辑器中，过长的"结构集合"一行不再压到"结构启用"按钮下面。
- 使用 KineticCore 26.10.6 时，过长的按钮与标签文字显示省略号、悬停时滚动，而不再被截断。

---

2026年10月05日 23时46分 — 26.10.5

- Saving the world-block item rules (ore merging, banned ores, weighted block merging) answers only the admin who saved; the rules are no longer sent to every online player. Other players receive them when they log in. The /reload command still refreshes everyone.
- Lists, buttons and text fields keep text clear of frames and scroll long text instead of cutting it off.

- 保存世界方块物品规则（矿物合并、禁用矿物、加权方块合并）只回复保存的管理员，不再发送给所有在线玩家；其他玩家在登录时获得规则。/reload 命令仍会刷新所有玩家。
- 列表、按钮与输入框的文字不压边框，过长文字滚动显示而不截断。

---

2026年10月04日 13时42分 — 26.10.4

- Added NeoForge 26.1.2 support (Java 25, KineticCore 26.10.3+). Beacon chunk loading and spawn prevention, teleport authorization, world-block item rules and world generation controls work on 26.1.2; existing saves keep their data.
- On 26.1.2 the Explorer's Compass and Nature's Compass teleport authorization follows the mods' 26.1 releases; item rules use 26.1's /give syntax.
- KubeJS's current 26.1 release cannot start a 26.1.2 server, so the integration is unavailable with that release.

- 新增 NeoForge 26.1.2 支持（Java 25，需要 KineticCore 26.10.3+）。信标区块加载与刷怪抑制、传送授权、世界方块物品规则和世界生成控制均可在 26.1.2 中使用，已有存档的数据保持不变。
- 26.1.2 中探险家指南针与自然指南针的传送授权适配这两个模组的 26.1 版本；物品规则使用 26.1 的 /give 写法。
- KubeJS 当前的 26.1 版本无法启动 26.1.2 服务端，因此该版本无法使用 KubeJS 联动。

---

2026年10月03日 16时51分 — 26.10.3

- Added NeoForge 1.21.1 support alongside Forge 1.20.1, using matching KineticCore 26.10.3+.
- Adapted saved world/beacon data and 1.21.1 item-component rules while retaining legacy item-NBT rule compatibility.
- Fixed untranslated Jade beacon information and the 1.21.1 teleport-command callback.
- Minecraft 26.1.2 is not yet supported.

- 新增 NeoForge 1.21.1 支持，同时保留 Forge 1.20.1；使用对应版本的 KineticCore 26.10.3+。
- 适配世界与信标存储，以及 1.21.1 物品组件规则；保留旧物品 NBT 规则兼容。
- 修复 Jade 信标信息显示原始翻译键，以及 1.21.1 传送命令回调。
- 尚不支持 Minecraft 26.1.2。

---

2026年09月29日（原记录未标注小时、分钟）

- Updated the editors for beacon controls, world block rules, world generation, and biome rules.
- Improved Explorer's Compass teleport authorization across supported versions, including use limits and remaining-time feedback.
- Updated compatibility with KineticCore 26.9.29.

- 更新信标控制、世界方块规则、世界生成和生物群系规则编辑器。
- 改进对不同受支持版本 Explorer's Compass 的传送授权处理，并完善次数与剩余时间提示。
- 更新对 KineticCore 26.9.29 的兼容。
