2026年11月4日 — 26.11.4 — Mega structures and test placement / 巨型结构与测试放置
- Test Place in the structure editor puts a copy of a small or medium draft (up to 256×256) around the player, set into the ground by its embedding, as it generates: cut-outs, loot tables, spawners and kept entities applied, mobs set up as vanilla does. The box is saved as a snapshot in the world folder first, and Undo Test puts every block and container back and removes the entities it placed. Both run a slice per tick with a percentage and can be stopped; one test placement per world, never over the place the draft was scanned from.
- 结构编辑器新增“测试放置”：把小型或中型草稿（256×256 以内）以玩家为中心放一份，底部按嵌入深度埋进地面，效果与生成时相同：剔除区域、战利品表、刷怪笼和保留的实体都按设置写入，生物按原版做生成初始化。放置前先把整个范围存成快照（在存档文件夹里），“撤销测试”把方块和容器全部恢复，并移除测试放下的实体。放置和撤销都按 tick 分片进行，显示百分比，可以停止；一个世界同时只有一个测试放置，也不会放在草稿扫描时的原址上。
- The save options have Publish & Rejoin: once the publish succeeds, the single-player world is saved and entered again, so the structure loads at once.
- 保存选项页新增“发布并重进”：发布成功后保存并重新进入这个单人世界，结构立即生效。
- The structure editor publishes mega structures (footprint over 256×256, up to 1024×1024) into the KineticCore data pack. They are cut into 32×32 tiles and generate in new chunks where vanilla's random spread puts them, each part placed chunk by chunk with vanilla's template placement; an instance generates whole or not at all (biome checked at its middle). /locate finds them and they show in the structure panel. RealmControl must be installed; without it KineticCore leaves the files out and the world loads.
- 结构编辑器可以把巨型结构（占地超过 256×256，最大 1024×1024）发布到 KineticCore 数据包：切成 32×32 的分块，按原版随机分布选址，在新区块中逐区块用原版模板放置；一座结构要么整体生成，要么不生成（在中心检查群系）。可以用 /locate 找到，也会出现在结构生成管理面板中。需要安装 RealmControl；没装时 KineticCore 会跳过这些文件，世界照常加载。
- Mega structures fill a foundation down to the ground and clear the ground above them (or only fill, or neither), with a chosen foundation block, and by default keep other structures from starting inside them and lakes and forest boulders from cutting into them; cut-outs keep the natural ground. Only the 64 tiles used last are kept loaded, so a large structure never sits in memory whole. The generation page checks that the separation keeps two of them apart.
- 巨型结构会把地基填到地面并清空上方的地形（也可以只填地基或都不处理），地基方块可选；默认不让其他结构在其范围内生成，湖和林中巨石也不会切进建筑。剔除区域保持天然地面。同时只保留最近用过的 64 块模板，大结构不会整个留在内存里。生成规则页会检查最小分离是否足够让相邻两座不重叠。
- The structure panel previews a mega structure from its draft, coarser when it is large.
- 结构生成管理面板中，巨型结构的预览读取其草稿，结构较大时以较粗的精度显示。

2026年11月3日 — 26.11.3 — Structure previews and the structure editor / 结构预览与结构编辑器
- New structure editor (K with the structure controller in hand, or /kt structure; operators only): turn a building in the world into a data pack structure that generates in new chunks. The structure controller (/kt structure wand) selects the region: right-click sets corner A, Shift + right-click corner B, Ctrl + wheel moves the face you look towards (16 blocks with Shift), Alt + wheel switches between its modes (region, cut-out boxes, containers, spawners, entities). The region, its boxes, the markers of the mode and a status line are drawn while the controller is in hand, and left clicks never break blocks. Regions are kept per player and dimension on the server.
- 新增结构编辑器（手持结构控制器按 K，或 /kt structure；仅管理员）：把世界里的建筑做成数据包结构，在新区块中自然生成。结构控制器（/kt structure wand）用来选区：右键设角点 A，Shift + 右键设角点 B，Ctrl + 滚轮推拉视线方向的那个面（Shift 一次 16 格），Alt + 滚轮切换模式（选区、剔除框、容器、刷怪笼、实体）。手持时显示选区、剔除框、当前模式的标记和状态行，左键不会破坏方块。选区按玩家和维度保存在服务器上。
- Markers decide what the structure does with its containers, spawners and entities, on the Markers page or with the controller: a container keeps its items or gets a loot table that fills it when first opened (a new roll for every placement); a spawner stays as scanned or spawns chosen mobs by weight with its own delays, counts and ranges; entities are kept or left out by type or one by one. Several rows (or containers gathered with Shift + right-click) change together. A rescan keeps the settings of everything still in its place.
- 标记决定结构里的容器、刷怪笼和实体如何保存，可在“标记”页或用控制器设置：容器保留原有物品，或改用战利品表，在第一次打开时填充（每次生成重新抽取）；刷怪笼保持原样，或按权重刷出选定的生物，并设置延迟、数量和范围；实体可按类型或逐个保留、丢弃。可以多选行（或用 Shift + 右键收集多个容器）一起修改。重新扫描后，位置不变的标记保留原来的设置。
- New loot tables for containers are written into the KineticCore data pack: empty, a copy of another table, or made from the container's items (one entry of equal weight per kind, with their counts and data). With ContentStudio installed, Edit This Table opens its loot editor on the table's file. A table whose items belong to a removed mod still loads without those entries; a container naming a table that does not exist stops the publish.
- 容器可以新建战利品表，写入 KineticCore 数据包：空表、复制现有表，或由箱内物品生成（每种物品一个等权重条目，保留数量和附加数据）。安装 ContentStudio 时，“编辑这张表”会在其战利品编辑器中打开这张表的文件。表中物品所属模组被移除时，表去掉这些条目后照常加载；容器引用不存在的表时拒绝发布。
- Publish opens the save options: whether block entities, loot tables, spawners, entities and cut-outs are written, mobs that never despawn, entities cleared of owners, leashes and memories, and where it goes: the KineticCore data pack, this world's datapacks folder or a ZIP file under kineticcore/structure_exports. On 1.20.1 it can also write the 1.21+ folders, so one pack loads in both.
- “发布”改为打开保存选项：是否写入方块实体、战利品表、刷怪笼、实体和剔除区域，生物是否不自然消失，是否清理实体的主人、拴绳和记忆，以及发布位置：KineticCore 数据包、当前世界的 datapacks 文件夹或 kineticcore/structure_exports 下的 ZIP 文件。在 1.20.1 上可以同时写 1.21+ 的目录，同一个包在两种版本中都能加载。
- New from region scans the selection on the server a slice per tick (at most 8 ms, half below 15 TPS, one scan at a time) with a percentage and a cancel button, only reading the world. Drafts are kept in the game folder under kineticcore/structure_projects, outside config.
- “从选区新建”在服务器上每 tick 扫描一部分（最多 8 毫秒，TPS 低于 15 时减半，同时只扫描一个），显示百分比并可取消，只读取世界不改动。草稿保存在游戏目录的 kineticcore/structure_projects 中，不在 config 里。
- The editor lists the drafts with a 3D preview as they will generate; its pages set the name, embedding, air (indoor air carves, open-sky air leaves the terrain) and ignored blocks, cut-outs on a map seen from above (box, brush, eraser, fill, natural ground detection, add-back boxes, undo and redo), and generation: dimension, biomes and biome tags to include or exclude, spacing, separation, chance, salt, height and terrain adaptation.
- 编辑器列出草稿并按生成效果显示 3D 预览；各页可设置名称、嵌入深度、空气（室内挖空、露天不覆盖地形）与忽略的方块，在俯视图上编辑剔除区域（框选、画笔、橡皮、填充、识别天然区域、加回、撤销与重做），以及生成规则：维度、包含或排除的群系与群系标签、间距、最小分离、出现概率、盐值、高度和地形融合。
- Publish writes structures up to 256×256 as vanilla data pack files into KineticCore's data pack folder, pack realmcontrol: a jigsaw structure with its templates, template pools, random-spread structure set and biome tag. A small structure (up to 64×64) is one template. A medium one is cut into 64×64 tiles that vanilla jigsaw puts back exactly where they were, around a frame as large as the structure that holds only jigsaw blocks; it is centred on its start, the seams leave no trace, tiles cut out whole are left out, and the save options show roughly how much memory its templates take once loaded. Cut-outs become structure void so the world fills them, and every biome entry is optional. The structure generates in new chunks after re-entering the world, /locate finds it, and it keeps generating without RealmControl. A new publish removes the templates an earlier one wrote that are no longer needed.
- “发布”把占地 256×256 以内的结构写成原版数据包文件，放进 KineticCore 数据包文件夹的 realmcontrol 包：拼图结构及其模板、模板池、随机分布结构集合和群系标签。小型结构（64×64 以内）是一个模板；中型结构切成 64×64 的分块，围绕一个和结构一样大、只有拼图方块的“外框”，由原版拼图把每块放回原位：结构以生成点为中心，拼接处没有痕迹，整块被剔除的分块不写，保存选项页会显示模板加载后大约占用的内存。剔除区域写成结构空位，由世界生成填充；群系条目都是可选的。重新进入世界后在新区块中生成，可以用 /locate 找到，卸载 RealmControl 后仍会生成。重新发布时会删除上次发布留下、已用不到的模板。
- The structure panel shows the selected structure in 3D beside the list. The sample is generated the way world generation places the structure (jigsaw pieces, processors, entities) without touching the world. Drag to turn, right-drag to move, scroll to zoom, double-click to reset; Another Sample shows a different layout. Under the preview: size and number of pieces, then the block, container and entity counts.
- 结构生成管理面板在列表右侧用 3D 显示选中的结构。样例按世界生成放置结构的方式生成（拼图部件、处理器、实体），不会改动世界。左键拖拽旋转，右键拖拽平移，滚轮缩放，双击复位；“换一个样例”显示另一种布局。预览下方显示尺寸与部件数，以及方块、容器、实体数量。
- Structures whose templates use blocks from mods that are not installed are marked red with a Missing materials tag, and the preview lists every missing block with its count. They still generate, with air in those places, as in vanilla.
- 模板中用到未安装模组方块的结构在列表中标红并带“缺失材料”标记，预览下方列出每种缺失方块及数量。这些结构仍会生成，缺失处为空气，与原版一致。
- Every row shows a small picture of its structure. The pictures are made on the server in the background, after any preview you open, and kept under kineticcore/cache/structure_thumbnails in the game folder until the world seed, datapacks or mods change; Refresh Structures redraws them.
- 每行左侧显示结构的小图。小图在服务端后台生成（排在打开的预览之后），保存在游戏目录的 kineticcore/cache/structure_thumbnails 中，世界种子、数据包或模组变化后重新生成；“刷新结构”会重新绘制。
- Rows no longer carry an Edit button: Edit Rules in the header or a double-click on a row opens its rules, and the rules page shows the structure's preview beside the fields.
- 列表行不再带“编辑”按钮：点击顶部“编辑规则”或双击行打开规则，规则页在字段右侧显示该结构的预览。
- Structures published by the structure editor carry a Custom tag in the structure panel and come first. Their Edit Rules opens the editor's generation page, so the panel and the editor change the same values, and saving the rules of a published structure writes them straight into its published files (they apply after re-entering the world); the panel's override rules leave these structures alone. The generation page can also switch a structure off, which publishes its structure set with a chance of 0.
- 结构编辑器发布的结构在结构生成管理面板中带“自定义”标记并排在前面。对它们点“编辑规则”会打开编辑器的生成规则页，面板和编辑器改的是同一份数值；保存已发布结构的规则时直接写进它已发布的文件（重新进入世界后生效），面板的覆盖规则不再作用于这些结构。生成规则页还可以停用结构，结构集合的出现概率写为 0。

2026年10月26日 — 26.10.26 — Clearer editors / 更清晰的编辑界面
- World generation, structure rule, biome control and beacon spawn rule titles are centred on the screen; the structure-set note moved into the Save tooltip and the beacon legend text is gone. Save and Apply buttons read Save and Apply.
- 世界生成、结构规则、群系控制与信标生成规则的标题改为屏幕居中；结构集合说明移入保存按钮的悬浮提示，信标规则页去掉图例说明；按钮文字统一为“保存”“应用”。

2026年10月24日 — 26.10.24 — Buttons at the top, standard slots / 按钮置顶与统一物品格
- 矿物封禁的方块网格批量绘制图标，滚动更流畅。
- Ore ban: search next to Back, Save at the top right, one more column of blocks. Ore merge: Save and Loaded Chunks swapped. Structure and biome pages: every button in the header, Save renamed from Save All, no dividers under the search field, the lists start right under it. Structure rule, biome rule and beacon editors: buttons at the top, Apply instead of Apply and Return.
- 矿物封禁：搜索框紧挨返回，保存在右上角，多显示一列方块；矿物合并：保存与已加载区块互换位置；结构与群系页面：按钮全部置顶，“保存全部”改为“保存”，搜索框下不再有分割线，列表紧接其下；结构规则、群系规则与信标编辑：按钮置顶，“应用并返回”改为“应用”。

2026年10月20日 — Beacon configuration / 信标配置

- The structure generation rule editor shows its title in the header row next to Back, above a header divider, like the other editors.
- 结构生成规则编辑器的标题移到顶栏、返回按钮右侧，并加上顶栏分隔线，与其他编辑器一致。
- Editors use one white outer frame. Internal lists and settings regions use borderless surfaces and gray-white dividers, leaving cards and interactive selection outlines intact.
- 编辑器统一保留一层白色外框；内部列表和设置区改为无边框底色与灰白分隔线，保留卡片及交互选中描边。
- Item icons in ore bans and both block replacement editors use native item tooltips. The weighted editor's selected-source icon uses its actual header position for hover; ore-ban scrolling only responds inside its clipped grid and scrollbar region.
- 矿物禁用与两种方块替换编辑器的物品图标使用原生物品提示；加权替换当前源物品图标按实际顶栏位置命中悬浮，矿物禁用网格仅在其裁剪视口与滚动条区域响应滚轮。
- Beacon loading radii use four fixed level fields with live chunk coverage and a clear -1 to 16 range.
- Advanced spawn rules browse every registered entity directly, with name/ID search, translated blocked-reason tooltips and standard model cards. Green borders mark configured or modified rules, red borders mark invalid rules or unavailable entities, and a breathing border marks the current entity. Selecting a mob creates no rule; saving and cancelling preserve untouched legacy rules, duplicate entries and unknown codes.
- 信标强加载半径改为四个固定等级的输入框，实时显示区块覆盖范围，并明确标注 -1 到 16 的取值范围。
- 高级生成规则直接浏览全部已注册生物，支持按名称或 ID 搜索，并以核心标准模型卡片和翻译提示展示生成方式。已配置或修改的规则显示绿框，错误规则或未加载生物显示红框，当前生物另有呼吸边框；仅选择不会创建规则，保存与取消均保留未修改的旧规则、重复条目及未知代码。

---

2026年10月16日 — GUI 输入与快捷删除

- 大型生成规则编辑页统一使用 640×360 画布，返回按钮距外框 4 像素；结构与矿物搜索加宽，满行搜索使用可用宽度。
- 信标生物规则、生物群系规则及方块替换页的 Delete 键复用当前条目的既有删除动作，选中描边使用核心主题。
- 群系规则的紧凑编辑窗口在画布中水平、垂直居中，字段与操作按钮随窗口一起定位。
- 普通方块合并与加权方块合并采用相同的窄左栏，标准画布宽度为 184 点，右侧留出更多方块显示空间。

---

2026年10月15日 — Spawn rule editor and replacement layouts / 生成规则编辑器与替换界面排版

- The beacon's advanced spawn rules have a visual editor: add mobs with the entity selector and tick which spawn reasons are blocked near beacons (natural spawning, conversion, commands, spawn eggs, spawners, summons, events, breeding). The stored lines keep their format.
- The block replacement editor uses the whole page: both panels reach 4 px from the edges, the left panel is wider, the Loaded Chunks switch sits at the right of the top row and the block grid shows one more row.
- The weighted block replacement editor moves its help into the New Rule tooltip, the Loaded Chunks switch into the top row and the step prompt, weight and chance into the right panel's header, so both panels are taller.
- The banned ore list's top-left button reads Back.

- 信标的高级生成规则改为可视化编辑：通过生物选择器添加生物，再勾选信标范围内要拦截的生成方式（自然生成、转化、命令、刷怪蛋、刷怪笼、召唤、事件、繁殖）。保存的行格式不变。
- 方块替换编辑页使用整个页面：两侧面板距页面边缘 4 像素，左侧面板更宽，“已加载区块”开关移到顶部一行右侧，方块网格多显示一行。
- 加权方块替换编辑页把说明移入“新建规则”的悬浮提示，“已加载区块”开关移到顶部一行，步骤提示、权重与概率移到右侧面板标题栏，两侧面板因此更高。
- 矿物封禁列表左上角按钮改为“返回”。

---

2026年10月14日 — Biome rule editor spacing / 生物群系规则编辑器间距

- The biome rule editor is a compact panel: dimension, source biome, target biome and the remove switch follow each other with even spacing, and Save sits at the bottom right of the fields instead of far below them.
- 生物群系规则编辑页改为紧凑面板：维度、源生物群系、目标生物群系与移除开关间距均匀，保存按钮位于字段右下方，不再远离内容。

---

2026年10月11日 — Replacement editor navigation / 替换编辑器返回按钮

- Standalone block replacement editors now label the upper-left navigation button Back.
- 独立打开方块替换编辑器时，左上角按钮统一显示“返回”。

---

2026年10月10日 — Navigation placement / 返回按钮位置

- Back is now at the top left of the structure, biome, banned-block and replacement editors, including individual rule pages. Standalone replacement pages place Close in the same position.
- Block grids, rule lists and editing fields keep their existing layout. Header titles and searches leave room for navigation, and long status text stays clear of nearby controls.
- Navigation button labels use white text.

- 结构、群系、禁用方块与替换编辑器的“返回”现在位于左上角，单条规则页面也保持一致。独立打开的替换页面在相同位置显示“关闭”。
- 方块网格、规则列表与编辑字段保持现有布局。顶部标题与搜索为返回控件留出空间，过长状态文本不会压到相邻控件。
- 返回类按钮的文字统一使用白色。

---

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
