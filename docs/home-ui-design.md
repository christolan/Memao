# Memao 首页 UI 方案对比

版本：0.35。日期：2026-11-08。状态：R70 未修改退出不新增草稿已完成；编辑器未改动显示“未修改”，原有草稿与真实改动保留。0.1.13（14）构建及 72 项不同检查通过，已保留数据覆盖安装手机并核验版本和冷启动。此前历史：R69 六行折叠、正文点击展开／收起、长按编辑和服务器笔记详情移除已完成。0.1.12（13）构建与累计 79 项不同检查通过，主 Agent 已复核截图并保留数据覆盖安装手机，实机手势及实体折叠待用户试用。此前历史：R68 独立页面状态已实现并通过验收；笔记／草稿／归档切回保留列表和滚动位置，不自动刷新。0.1.11（12）已通过 73 项不同检查，三页截图复核通过，并保留数据覆盖安装手机，实际连续切页及实体折叠体验待用户试用。此前历史：右上角刷新按钮已移除，笔记／归档改为下拉刷新。0.1.10（11）构建、4 项相关 UI 回归及 5 张截图复核通过，已保留数据覆盖安装至手机并核验版本和启动；实际手机手势体验待用户试用。此前历史：标签展示精简已完成：选中标签并入笔记页顶栏，侧栏标题保持；搜索框下提示和列表／详情底部额外标签行已移除，正文标签保留。0.1.9（10）构建、2 项相关 UI 回归与 4 张截图复核通过，已保留数据覆盖安装手机并核验版本和启动。此前页面标题已合入吸顶区，顶部应用名及正文重复标题已移除。0.1.8（9）构建、9 项相关 UI 回归与 5 张截图复核通过，已保留数据覆盖安装至手机并核验版本与启动。此前标签选中项右侧勾与占位已移除，保留背景高亮、文字及无障碍选中语义。0.1.7（8）构建、两项相关界面回归及浅／深色截图复核通过，已保留数据覆盖安装至手机并核验版本与启动命令。启动列表、后台保留现场等既有规则保持。此前 0.1.3 官方抽屉动效优化的 27 项 JVM 与 22 项 UI／媒体检查作为历史证据保留。窄屏采用 ModalNavigationDrawer、DrawerState 和带状态的 ModalDrawerSheet，宽屏常驻与极简视觉保持；旧版本截图和预览留作历史。主 Agent 结果、实际截图及目标设备待验证边界见 [Memao 首版开发准备与接口核对](development-and-acceptance.md)。

关联规划：[Memao Android 客户端需求与落地规划](planning.md)。

## 独立搜索页 · R75

笔记页顶栏分割线下直接进入列表，首条笔记从和后续笔记相同的 8 dp 上边距开始。每条笔记仍显示自己的创建时间。

笔记页固定标题栏右侧放置放大镜按钮。进入搜索页后，顶部固定搜索框和紧随其后的「取消」，下方随输入与提交状态展示最近搜索或匹配结果。初次进入聚焦搜索框，键盘搜索键和输入框内放大镜均可提交。输入框内的清除按钮恢复最近搜索，点选历史会直接重新检索。另有标签筛选时，顶部说明本次搜索沿用的范围。

窄屏和折叠展开的搜索页都占用完整内容区。取消后恢复原笔记列表及对应窗口宽度的抽屉布局。结果复用笔记正文展开、长按编辑、附件预览和行菜单，支持加载更多及下拉刷新。离线结果注明仅覆盖本机缓存。历史和其他交互默认见 [R75](planning.md#r75-独立搜索页与最近搜索)。

## 图标优化 · 当前方案

用户已指定采用 Lucide。完整映射、统一尺寸与状态、Android 矢量接入、分步实施和验收清单见 [Memao Lucide 图标优化落地方案](lucide-icons.md)。保持留白极简及既有双栏设计，覆盖导航、工具栏、编辑器、媒体和空状态；桌面启动图标作为独立品牌资产处理。后续具体实施与验收更新到该方案页。

## 桌面图标定稿 · R74

2026-11-15 用户选定 [B「折纸 M」](assets/design/launcher-icons/b-folded-m.png)。桌面图标使用浅灰底、深灰字形与浅一阶的折面，应用内的导航抽屉和设置页复用同款折纸标志，放在既有「Memao」标题左侧。下面保留两轮提案与出图记录。

## 桌面图标候选 · 2026-11-15

用户说明 Memao 是 Memos 的 Android 客户端，命名与自己的昵称「帽子」有关，并希望保留现在的简约风格。首轮提供三款候选，最终选择见上方定稿记录。

| 候选 | 设计 |
| --- | --- |
| [A 帽沿便签](assets/design/launcher-icons/a-hat-note.png) | 炭黑底配暖白图形。棒球帽的侧影叠在便签上，同时呼应昵称与笔记用途。 |
| [B 折纸 M](assets/design/launcher-icons/b-folded-m.png) | 浅灰底配粗体 M，使用折线和斜切端点表现纸带，延续原有字母标识。 |
| [C 纸折渔夫帽](assets/design/launcher-icons/c-paper-hat.png) | 松绿色底配暖白帽形，一块浅绿折面呼应纸页翻角。 |

[桌面图标对比图](assets/design/launcher-icons/comparison.png)包含圆形和圆角方形的小尺寸示意。候选通过内置 ImageGen 生成，原图均为 1254 × 1254 PNG。预览为静态视觉比较，目标设备上的桌面效果待选定方案并接入后验证。

<details>
<summary>候选原图的生成提示词</summary>

**A**

> Use case: logo-brand. Generate ONE standalone square Android launcher icon for Memao, a minimalist Memos notes app named after the maker's nickname Hat. The central glyph must unmistakably read as a NOTE PAGE at first glance, and have a charming compact recognizable side-profile baseball cap cocked to the RIGHT on its top edge. Design an ingenious overlapping two-piece flat logo with a narrow negative-space gap between the objects. The bottom two thirds is a vertical memo page rendered as a warm ivory filled rectangle with subtle corner radii, a small triangular notch at the bottom right, and exactly two short thick charcoal horizontal text dashes in the page. The upper third is the small ivory baseball cap, a simple low dome that rises to the left with a clearly pronounced short asymmetric visor sticking out to the right ONLY. Slight rotation of cap (about 8 degrees), the memo page remains upright. No knob or button on top of hat. The cap is not an umbrella, not fedora or a cloche, avoid a symmetric hat brim. Precise flat clean geometric contours. Unifying warm ivory #F4F1E9 centered artwork, 58% width and around 61% height, over solid charcoal #242629 covering all four square corners uniformly. Bold and legible at 32px, big negative spaces, no strokes, no gradients, no shadows, no bevels, no texture, no text, no border or frame, no icon container, no mockup. Render the individual app icon artwork.

**B**

> Use case: logo-brand. Create ONE sophisticated, minimalist Android launcher icon for Memao, a Memos Android notes app. Square full-bleed light paper grey #F4F5F6 background with a centered large #242629 mark. The mark is a bold custom uppercase M shaped like a single strip of folded stationery, with robust stems, an intentional steep inner fold, and one blunt diagonal paper-cut end at the lower right. Use delicately eased outside corners and geometric, carved negative space to suggest the folds using at most one broad cut and two flat fills, charcoal and one subtly lighter graphite face. Make it read immediately as an M, with calm, grounded proportions, designed for reproduction in one color and at tiny size. Art direction is a quiet modern design studio identity mark. The glyph occupies only about 60 percent of the square width, with generous space on all sides. Pixel-crisp flat vector-style design. Full-bleed background extends through all four square corners. No text other than the M mark, no decorative stamp, no underline, no branding captions, no tablet, no frame, no visible paper texture, no gradients or shadows. Output only the icon.

**C**

> Use case: logo-brand. ONE refined flat geometric square Android launcher icon for Memao, minimalist notes app. Draw a highly recognizable modern folded-paper BUCKET HAT in side three-quarter view. Imagine the silhouette of a short flat-crowned bucket hat, with a sloping brim that fans diagonally down and outward, folded from one sheet of stationery. The crown has a broad FLAT slightly tilted top, never a pointed triangular top, with two clearly defined inward-sloping shoulders. The solid connected brim tilts down at the far ends and right side like a real bucket hat, never like a boat hull, and a very small triangular sage cut face on the right of the crown evokes a page fold. Use a maximum of three clean contiguous flat geometric planes with a small amount of rounding on the outer silhouette. No dots, no fabric or stitching, no ruled lines, no text. A bold friendly silhouette with refined details, central and 58-60 percent square canvas in width. Warm ivory #F4F0E6 main plane, soft pale sage #ACC3A4 folded plane, solid deep pine green #263D35 full bleed square background extending through all four corners. Carefully spaced and optically centered. Logo mark itself only, no panel, gradients, lighting, textures, shading, mockup, lettering or watermark.

</details>

## 桌面图标候选 · 棒球帽与黑白灰

2026-11-15 用户要求探索棒球帽图形，配色限于黑白灰。第二轮提供三款候选，编号延续首轮。

| 候选 | 设计 |
| --- | --- |
| [D 棒球帽剪影](assets/design/launcher-icons/d-cap-silhouette.png) | 浅灰底配黑色侧影，用留白勾出帽冠的缝线和向右伸出的帽檐。 |
| [E 帽上笔记](assets/design/launcher-icons/e-cap-memo.png) | 黑底白帽冠，灰色帽檐朝左，帽冠内一长一短两条横线表现笔记。 |
| [F M 帽徽](assets/design/launcher-icons/f-cap-monogram.png) | 银灰底配正面的深灰棒球帽，在帽冠中央放置白色 M。 |

[棒球帽对比图](assets/design/launcher-icons/baseball-cap-comparison.png)提供圆形与圆角方形的小尺寸预览。三款均由内置 ImageGen 生成。

<details>
<summary>候选原图的生成提示词</summary>

**D**

> Use case: logo-brand. Produce exactly ONE polished minimalist Android launcher-icon artwork for Memao. Motif is a single unmistakable classic six-panel BASEBALL CAP seen from the side, facing right. Wide angled projecting visor with a smooth shallow curl, low rounded paneled crown, a tiny top button fused to the silhouette, and one decisive curved light-colored cut to indicate the front panel. Logo is a bold nearly black solid silhouette on uniform full-bleed pure neutral light gray #F0F0F0. A second short knockout just under the visor can define its curl if necessary. The black silhouette should feel stylish and ownable through its measured geometry and the angled cap profile. Flat simplified vector-style forms, optically centered, about 64 percent of square width, comfortable surrounding space. Primary shapes should remain clear at 32 px. STRICTLY achromatic black, white, and neutral gray; no tint. Fill all four square corners with the same background. No text, badge, letters, extra objects, circles, frames, gradients, backdrop texture, drop shadows, mockups, or captions. Output the icon artwork only.

**E**

> Use case: logo-brand. Generate a single concept for a minimalist Android Memos client launcher icon, square full bleed. Design a clever single-piece BASEBALL CAP + NOTE symbol. The connected shape is an unmistakable sporty baseball cap seen in side three-quarter view with a long curved bill projecting to the LEFT, subtly angled. Its rounded white crown shows just TWO clean short black parallel memo lines, first line long and second line shorter, integrated within the crown as an unmistakable typographic idea. Design a single graphic hat silhouette with space for this negative-space flourish, with the hat recognizable first at reduced size. No separate sheet below the cap and no other object. The visor is a bold slightly lighter or darker neutral gray face to express its curve, strongly distinguishable against the dark field. Purely black white neutral gray, paper white #FFFFFF and medium gray #A1A1A1 motif against uniform nearly black #181818 field; no warm or cool tints. Broad, geometric, flat shapes, confident proportions, 62 percent of square width for motif, generous margins, optically centered, legible at tiny size. Background goes to all four square corners. No lettering, frames, shadows, gradients, texture, gradients, outdoors scene, fabric photography or mockup. Show only the resulting icon design.

**F**

> Use case: logo-brand. Design one exquisite, achromatic minimalist Android launcher icon for the notes app Memao. Combine the recognizable front view of a classic baseball cap with a simple compact uppercase M monogram on its crown, like a team cap emblem. Front view should be straight on or slightly from above, clean geometric dome with a small top button, a flat dark-black broad visor with a crescent lower edge and subtly flared ends lower than the rounded stable crown. The emblem is a clear white bold M centered high enough on the crown and large enough for launcher size. Graphite cap crown #3C3C3C; defined black gray visor #181818; white M. Solid full-bleed medium-light silver gray #D3D3D3 through all four square corners. No noise, gradients, warm or cool tint, icons outside the cap, shading, stitches, textiles or texture. Limit to flat overlapping shapes with softened corners. Motivated by refined monochrome sports identity graphics. Give the mark ample surrounding space and center it optically, hat around 61 percent of the square width. No mockup, no lettering except the white M centered on front of the baseball cap, no captions, no border, no surrounding panel. Output only the single app icon.

</details>

## 已确认的视觉要求

用户明确不喜欢 Material Design，已查看四种首页方案并选定第一个“留白极简”。后续以黑白灰、分隔线、留白和清晰内容层级为基础，继续设计首页、详情与编辑器。

主要设备为 OPPO Find N6，布局按实际窗口宽度适配：普通手机与窄分屏采用默认隐藏的侧边抽屉，折叠屏展开和平板宽窗口采用常驻抽屉；四个入口均为笔记、草稿、归档、设置。按 R69，服务器笔记在列表原位阅读，宽屏无编辑任务时列表占满可用内容区；编辑及草稿预览时，空间足够保留约 1:2 双栏，不足回单列。折叠展开继续当前列表阅读或编辑，保留笔记展开状态、未发送内容、选区、筛选及阅读位置。用户本轮明确要求取代原底部 dock 和窄导航方案。

## 页面标题与吸顶区

2026-11-06：用户明确要求当前页面标题移到吸顶区，移除顶部 APP 名称，页面内容内不重复页面标题。按授权将窄屏菜单合并至页面固定标题栏，保留返回及页面操作；列表刷新已按 2026-11-08 的最新要求改为下拉触发，移除固定栏右侧刷新按钮，日期、说明、搜索仍在内容区；按用户最新要求，选中标签只显示在页面顶部标题后，搜索框下不再展示。宽屏在各内容栏保留对应的固定标题，抽屉品牌保留。滚动时标题始终可见，切页和编辑时标题对应当前内容。实现、构建及 9 项窄／宽屏相关回归通过，主 Agent 复核滚动后标题、编辑和深色大字体截图；0.1.8 已安装手机。详情见开发准备，旧预览只作历史。

## 下拉刷新

2026-11-08 用户明确要求去掉右上角刷新按钮，改为下拉刷新。笔记／归档列表共享该交互，草稿保持本地自动更新。

按授权确定：到顶下拉后松手刷新；空列表、短列表和筛选无结果均可触发。保持黑白灰进度反馈，刷新期间防重复，完成或失败后收起；加载更多使用列表底部反馈。固定标题与搜索／标签状态保留，刷新失败保留已有内容及原重试／离线提示。已完成实现与 4 项相关 UI 回归；主 Agent 复核刷新中、分页、离线、宽屏空归档和深色大字体截图。手动刷新空列表保留空态；草稿不刷新，并提供无障碍自定义刷新动作。0.1.10 已保留数据覆盖安装到手机，详细验收与实际设备边界见开发准备。

## 页面切换与状态保留

2026-11-08：用户明确确认笔记、草稿、归档分别使用独立 Compose 页面和 ViewModel。切换时保留各自列表和滚动位置，不因切回页面而自动刷新；三个页面各自管理数据、分页和加载状态。首次进入笔记／归档加载一次，手动下拉、关键词／标签变化继续请求；草稿由本地数据变化更新。归档／恢复／删除等操作同步相关列表。视觉、侧边栏、固定标题和窄宽布局保持，启动默认笔记列表的既定规则保持。此项已实现，六项新增专项及相关回归全部通过，三页滚动后截图已复核，验收见 [Memao 首版开发准备与接口核对](development-and-acceptance.md)。

## 列表内容展示

2026-11-15 用户确认新版笔记列表设计。每条笔记从标题或正文开始，附件紧随正文。创建时间居左，「展开全文／收起」与更多菜单居右，同一行垂直居中。展开／收起用上下箭头标明状态，短笔记的底栏显示时间和更多菜单。普通正文为 15 sp，0.1.24 起创建时间和「展开全文／收起」为 14 sp，行高均为 27 sp。底栏文字与图标共用柔和灰色，浅色模式为 `#5D6268`，深色模式为 `#B3B8BE`。条目上下各留 12 dp，底栏距上方内容与下方分割线也各为 12 dp。菜单与展开／收起的最小触摸区为 48 dp。底栏宽度不足时，时间使用单行省略以保留操作入口。

长按后编辑器准备期间保持列表位置，准备完成后直接进入编辑。等待期间可用系统返回取消，读取失败仍显示错误。

2026-11-08 用户明确确认 R69：笔记正文默认最多展示 6 行，超过 6 行折叠；点击正文展开／收起，长按笔记直接编辑；取消服务器笔记详情页，列表展开即可阅读全文。旧全文列表与详情预览仅作历史。

按授权确定：按当前宽度、字号与 Markdown 排版实际累计 6 行；短文不显示无意义展开入口。笔记和归档一致，图片与音频在正文下方继续独立操作；链接短按保留打开能力。正文长按优先编辑，全文复制移至每条笔记更多菜单，菜单同时保留编辑、归档／恢复及删除，删除仍二次确认。当前账号页面会话内保留每条展开状态，切页和窗口重排保持，新启动默认折叠。宽屏浏览列表占满可用内容区，编辑时继续原双栏；草稿预览保留。工程分工、自动化回归和截图验收结果记录于开发准备文档。

列表默认按创建时间倒序，新创建的笔记在前；编辑旧笔记不会使其前移。外屏与展开内屏左侧列表保持相同排序。

## 新建笔记入口

抽屉内保留「记录」，页面右上角记录按钮已按用户要求移除；窄屏通过菜单打开抽屉后新建，宽屏直接使用常驻抽屉。沿用黑白灰与 Lucide 图标。双栏时在右栏编辑，单列时进入编辑器；新建自动聚焦正文并弹出键盘。附件、自动保存草稿和切页前保护未发送编辑的既有规则保持，底部不再放置 dock 导航。

## 标签筛选 · 已实现并通过回归

2026-11-06：用户要求将笔记页标签筛选移到侧边栏；移除搜索框下的横向全部／标签筛选栏，关键词搜索留在列表。按授权采用抽屉内独立「标签」分区，置于笔记／草稿／归档入口之后、设置之前，只在浏览笔记时显示；全部清除标签，再点已选标签也可取消，关键词保留。窄屏等待抽屉关闭后应用筛选，宽屏原位更新并保持常驻。行高至少 48dp、长标签换行、选中状态可访问；按用户最新明确要求，选中项只保留背景高亮，删除右侧勾与占位间隔，文字可利用腾出的宽度。用户最新明确要求选中标签移到笔记页面顶部标题后，侧边栏标题不变；搜索框下选中标签提示取消。笔记列表（含归档）及详情底部独立标签行一并移除，正文中的 #标签内容及编辑数据保留。按授权默认使用「笔记 #标签」格式，清除后恢复「笔记」，长标题沿用固定顶栏最多两行省略。编辑、草稿、归档及设置不增加标签筛选。此段的交互细节属于按用户授权确定的默认方案，并非用户逐项确认。

## 抽屉动效优化 · 已实现并通过模拟器回归

2026-11-06：用户反馈 0.1.2 开合不协调，已授权修复并在验收后安装手机。窄屏改为官方 ModalNavigationDrawer／DrawerState 管理侧向滑入、滑出和遮罩，保留留白极简、Lucide、既有宽度与入口。不得在关闭动画结束前卸载抽屉；切换目的地或新建应与关闭流程协调，避免键盘与抽屉抢焦点。宽屏仍常驻，窗口重排继续保留草稿、选区、预览、筛选与列表位置。本轮已通过开关中间帧、保持同一抽屉节点、反向中断、手势、遮罩、系统返回，以及关闭结束后才导航／新建的测试；跨宽度编辑选区与预览回归通过。官方预测返回处理路径已接入，实机连续预测返回手势仍以设备实测为准。

## 未修改退出编辑 · R70

2026-11-08 用户明确要求：进入编辑后无修改，退出不要保存草稿。以正文和附件实际变化判断，光标／选区／预览不触发新增草稿；本次新编辑副本若退出时与进入基线一致即清理，原有草稿保留。未改动状态显示“未修改”，有改动沿用本地保存反馈；返回、切页及切账号遵循同一规则。已尝试发送的内容继续保护。已完成实现与 72 项不同检查，主 Agent 复核“未修改”状态截图；0.1.13 已保留数据覆盖安装手机并核验版本和启动，工程与验收边界见开发准备。

## 常规交互的决策与默认方案

用户已授权：无明显个人偏好差异的常规交互由助手按成熟平台惯例自主确定，不逐项询问；仅明确存在个人喜好差异的选择再请用户确认。以下为按授权确定的默认方案，可调整，不表示用户逐项选择或获准开始开发。完整规则与验收见关联主规划“按用户授权确定的常规交互默认方案”D01—D08。

- 编辑中持续本地保存，退出、切换账号或右栏内容前完成保存；显示保存状态，本地保存失败保留编辑现场并提供重试。
- 草稿页按最近编辑时间倒序，区分新建与修改草稿，点击恢复；删除草稿单独确认。发送失败保留内容，结果未知先核对，防止重复发送。
- 键盘展开时系统返回先收起键盘；预览与源码切换保留内容、光标和滚动位置；取消附件选择不清空输入。
- 首次加载、刷新、加载更多、空列表、搜索无结果、离线与错误分别呈现。刷新失败保留内容。按 R75，清除关键词回到最近搜索，笔记页的标签筛选仍用于本次搜索。离线搜索提示只覆盖本机缓存。
- 折叠展开保留筛选与列表位置；分屏或放大字体后空间不足则回到单列，键盘不遮挡输入和发送。启动／重启不自动打开历史笔记；后台切回保留现场。已返回列表或切页时，迟到的详情加载结果不能再次打开笔记。
- 图标有语义标签，可点击区域以至少 48dp 为基准，支持字体缩放与屏幕阅读器；错误用文字说明并保留输入。沿用留白极简视觉。

状态保存与适配依据：[Android 状态保存](https://developer.android.com/topic/libraries/architecture/saving-states)、[列表与详情适配](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail)、[可访问性默认行为](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)。这些资料支持通用行为，具体 Memao 默认方案由助手结合既定需求确定。

## 图片查看

点击图片后进入全屏查看，支持双指缩放，并可左右滑动浏览同一笔记的其他图片。图片统一位于文字下方；列表中的单张图片按原比例展示，多张采用整齐网格，点击查看完整图片。具体网格列数、尺寸与间距在设计预览中细化。

## 主题模式

首版支持浅色与深色，默认跟随系统，允许在设置中手动切换；设置选项为跟随系统、浅色、深色。两种外观均延续留白极简方向，具体色值、对比度与控件状态继续设计。

## 已选方向与折叠屏适配

已选定“留白极简”，其他风格保留为对比参考。现已完成首轮外屏单列、展开双栏首页和编辑器预览，并支持深浅色切换；此前四种首页风格保留在历史参考部分。预览检查不等同于 Android 真机或服务端适配验收。

当前确认布局：窄窗口采用可展开的模态侧边抽屉，宽窗口常驻同一组图标加文字入口。常驻门槛按授权初设为可用宽度 600dp；内容是否双栏独立判断，需在扣除抽屉后满足列表约 240dp、详情约 480dp，并随字体放大提高要求。空间不足只回退内容为单列，抽屉仍按窗口宽度常驻。

当前确认启动内容：启动或重启 APP 一律显示笔记列表，宽屏右侧为未选中空态，不恢复最近笔记或自动选第一条。后台切回保留当前阅读／编辑现场；旧恢复规则已被用户于 2026-11-06 取消。

已确认折叠展开衔接：继续显示当前笔记详情或编辑页面，保留未发送内容、光标与阅读位置，不返回列表，不自动发送。布局切换保持当前任务，不以启动列表规则覆盖正在编辑的草稿。

已按授权确定最近笔记不可用的回退、筛选与滚动位置衔接、空间不足回到单列及键盘避让。待通过设计预览和设备验证确定最小可读宽度、具体切换断点和控件排布；不再逐项询问常规交互。

本轮已按既定需求完成下方四视图预览，展示全文列表、创建时间倒序、单图原比例、多图网格、固定记录入口与约 1:2 内容双栏。首屏右栏使用示例账号上次查看的笔记呈现恢复状态。用户已认可当前留白、字体、深浅色与编辑器控件的整体观感；配套登录、账号管理、草稿、归档、设置及异常状态已沿用同一视觉基准完成首轮预览，见下方关联文档。设备真实尺寸与最终断点仍需验证。

## 首轮关键页面设计 · 已认可（历史预览）

下方 HTML 预览与旧截图记录此前底部导航／窄栏设计，保留为历史。当前导航以本页自适应抽屉规格及 Android 实现为准。

2026-10-25：用户确认进入下一步。本轮交付为设计预览，Android 工程功能代码未改动。已确认需求继续有效。用户随后明确表示“我很满意这一套设计”，当前展示的四视图及深浅色整体视觉已获认可；以下参数作为后续设计基准，Android 单位转换与真机验证仍待后续完成。

[memao-key-screens-review.html](assets/design/memao-key-screens-review.html)

预览底部可切换“外屏首页”“展开首页”“外屏编辑器”“展开编辑器”。通过任一首页的“设置 → 外观”切换深浅色，或使用会话提供的设计调节控件调整外观、字号、留白与圆角。预览以展开内容便于检查为目的，实际 APP 列表应滚动且外屏记录与导航固定于底部，不将预览纵向长度视为真实设备高度。

### 本轮页面与布局

- 外屏首页：顶部账号切换，标题与日期，其下为关键词和单标签筛选；全文笔记按创建时间倒序排列，以分隔线和间距组织，底部固定记录入口与笔记／草稿／归档／设置导航。示例包含文字、双图和纯音频记录。
- 展开首页：左侧窄导航，扣除导航后的内容宽度按约 1:2 分配列表与详情；选中笔记使用浅背景表达当前位置，详情中保留创建时间、全文、图片与标签，操作位于详情顶部。无额外卡片装饰。
- 外屏编辑器：顶部返回与发送，编辑／预览切换及本地保存状态；源码正文后统一显示附件。格式快捷栏与图片、已有音频、标签入口放在键盘上方，私密状态只作说明。
- 展开编辑器：保留左侧导航与笔记列表，右栏编辑当前笔记；输入、附件、格式栏及键盘区域在右栏呈现，避免键盘遮挡发送。跨折叠状态的产品规则沿用 D04—D06。

### 按授权提出的视觉参数

浅色背景 #FFFFFF、正文 #242629、次要文字 #6B7076、分隔线 #E9EBED、轻背景 #F4F5F6；深色背景 #171819、正文 #ECEEEF、次要文字 #A9AFB5、分隔线 #34373B、轻背景 #222529。主操作采用黑白反转，不使用额外强调色或动态壁纸配色。

正文以 15 为预览基准，窄列表以 14、详情和输入以 16 呈现；标题约 24—28，常规控件圆角约 7，列表纵向留白约 21。数字为预览 CSS 参数，不等同于 Android dp／sp 或 Find N6 屏幕规格。当前视觉已获用户整体认可；后续页面沿用这组层级与间距，仍可根据后续反馈调整。

多图初稿采用两列与小间距缩略图；单图保留示例原始比例，完整图片在查看器中展示。原图比例规则已获用户确认，具体列数、裁切、尺寸与留白为本轮设计方案。

预览在可用宽度小于 660 CSS 像素时将展开视图切为单列，仅用于演示窗口不足的回退；最终断点需结合 Android 窗口、字体缩放和目标真机确定。没有推定 OPPO Find N6 的物理尺寸或分辨率。

### 交互演示与验证边界

可演示单标签与关键词取交集、空结果、查看详情、图片左右切换与缩放、格式快捷按钮、源码／预览切换、示例附件添加、退出后在草稿页继续编辑、归档与取消归档、删除确认，以及深浅色切换。发送仅展示反馈并保留草稿，不访问服务器。账号入口与音频进度、播放暂停为示意；无真实账号、音频播放或完整 Markdown 渲染。

键盘是排布示意，非 Android 输入法。示例内容、图片、服务器域名与账号均为演示数据；草稿仅在当前预览交互中保留，不代表已实现跨重启持久化、可靠附件导入、冲突检测或真实离线同步。选中恢复与宽度回退可查看；Android 进程恢复、设备折叠和服务端行为仍待开发后验证。

已通过独立浏览器检查：四视图在约 754、388、356、302 等实际内容宽度下均无横向溢出；窄展开视图回到单列，四视图深色外观已检查，脚本错误为零。单标签加关键词命中 1 条、空结果、图片切至第 2 张并放大到 150%、新建正文聚焦、源码／预览内容保留、退出生成草稿，以及归档页无搜索控件均已验证。此记录属于设计预览 QA，不视为 APP 验收。

### 配套页面与异常状态

[Memao 配套页面与异常状态设计](supporting-ui-design.md)：已完成连接服务器、账号管理、草稿、归档、设置、离线浏览、发送失败、版本冲突、加载与空状态九视图。文档保存本轮可交互预览、危险操作文案、状态与流程、窄窗口和深色检查，以及未实现能力的边界。首页与编辑器的既有认可继续有效；配套页面与异常状态也已获用户明确认可，共同作为首版设计基准。

### 开发准备入口

[Memao 首版开发准备与接口核对](development-and-acceptance.md) 已保存固定 Memos v0.31.0 的接口与 Markdown 证据、可恢复提交方案、开发顺序和验收计划。用户已确认首版优先自用，并选择原生服务器＋发送前冲突检查，接受检查与提交之间的并发窗口；三种已检测冲突的选择和现有视觉基准保持。用户已确认：笔记及附件完整缓存后才可离线编辑，部分缓存仍可浏览。配套预览的编辑入口、文案与完整缓存设计开关已同步，未开始 APP 开发。

### 下一轮设计

用户已认可首页与编辑器整体视觉，本轮配套页面与异常状态已交付首轮预览。配套页面与异常状态现已获用户整体认可。下一步梳理首版范围、接口兼容与开发准备；常规交互继续自主完善。整体视觉认可不等于真实服务器、持久化或设备能力已验收；全部关键页面确认且用户明确授权后才开始 APP 开发。

## 历史首页方案预览

下方可切换四个首页设计。笔记内容、账号名称与图片为设计样本；搜索、标签、账号、归档与记录入口用于交互示意，未连接真实服务器，播放器没有真实音频。

[memao-home-ui-directions.html](assets/design/memao-home-ui-directions.html)

## 四种候选风格

### 留白极简

以黑白灰、分隔线和留白组织笔记，减少卡片容器；账号入口在顶部，搜索与单标签筛选直接可见，底部以矩形按钮进入记录。偏重清晰、安静和日常浏览。

### 纸感手记

采用暖纸色、衬线标题、时间轴和带留边的图片，记录入口使用细线与文字。偏重手记感和阅读氛围，视觉节奏更舒展。

### 编辑部

采用蓝色强调、方正结构、强标题与版面分区，短笔记以矩形强调块呈现。偏重鲜明排版和辨识度，与其他方案在布局和文字层级上形成区别。

### 夜航

采用深色背景、低饱和绿、细线时间轴与描边记录按钮，减少容器边界。偏重夜间阅读和克制的视觉氛围；选择此方向不等于已经决定 APP 只做深色模式。

## 所有方案共同遵守的范围

- 只展示当前账号本人的未归档个人笔记，没有其他用户或 Spaces 入口。
- 顶部提供服务器账号切换，支持搜索与单标签组合筛选。
- 图片位于文字下方，单图按原比例展示、多图采用整齐网格；点击可全屏查看完整图片、双指缩放，并左右滑动浏览同一笔记的其他图片；展示音频播放、暂停及进度拖动控件。
- 提供记录、草稿、简化归档和设置入口；统一侧边抽屉，窄窗口收起、宽窗口常驻。新建入口见本页当前规格。
- 不提供置顶、可见性切换或 APP 内录音入口。
- 保持留白极简、全文列表和空间允许时约 1:2 内容双栏；导航已按本轮要求替换。

## 后续确认

后续按自适应侧边抽屉验证窗口变化、文字放大、键盘、编辑与阅读状态保留；沿用已确认的留白极简与内容功能范围。

其他功能边界继续以主规划中的已确认与待定状态为准，首页方案不代表全部需求已经确认。

## 选择记录

2026-11-06：用户确认页面标题统一移入吸顶区，去掉顶部 APP 名称与正文重复标题；上述合并方式为按用户授权确定的默认交互。

2026-11-06：用户明确去掉标签选中项右侧勾，仅保留背景高亮；此指示取代此前同时使用背景与勾的实现。

2026-11-06：用户取消“进入大屏首页时恢复上次笔记”，改为启动／重启统一笔记列表；宽屏右侧保持空态，手动选择后再展示。用户另明确后台切回保留阅读／编辑现场。下方 2026-10-25 的确认与历史预览仅保留沿革，当前以此规则为准。

2026-11-06：用户要求把笔记页标签筛选移入侧边栏，当前方案与验收按上方「标签筛选」规格执行；旧预览保留为历史。

2026-11-06：用户要求去掉突兀的页面右上角「记录」按钮；保留抽屉中的记录入口，其他导航和动效保持。

2026-11-06：用户要求取消底部 dock，改为窄屏隐藏、宽屏常驻侧边抽屉；已更新当前视觉要求、新建入口及适配规格。具体门槛与关闭交互按授权确定，可按目标机复核结果调整。

2026-10-25：用户选择第一个“留白极简”方案，并要求考虑 OPPO Find N6 折叠屏的大屏适配；具体展开布局待确认，当前不开始开发。

2026-10-25：用户确认展开内屏采用左列表、右详情或编辑的双栏布局，外屏保持单列；导航与双栏细节继续确认，暂不开发。

2026-10-25：用户确认展开大屏采用左侧窄导航栏，包含笔记、草稿、归档和设置；内容双栏保留，比例等细节继续确认，暂不开发。

2026-10-25：用户确认扣除窄导航栏后，内容区默认列表约占 1/3、详情或编辑约占 2/3；右栏初始状态等细节继续确认，暂不开发。

2026-10-25：用户选择进入大屏首页时恢复上次查看的笔记，按服务器账号分别记忆，首次使用时显示空状态；折叠展开衔接等细节继续确认，暂不开发。

2026-10-25：用户确认折叠或展开时继续显示当前详情或编辑页面，保留未发送内容、光标及阅读位置，不返回列表；外屏导航和其他适配细节继续确认，暂不开发。

2026-10-25：用户确认外屏使用笔记、草稿、归档、设置四入口底部导航，与展开大屏左侧窄导航对应；深浅色等细节继续确认，暂不开发。

2026-10-25：用户确认支持浅色和深色模式，默认跟随系统，并允许在设置中手动切换；具体配色与列表密度继续设计，暂不开发。

2026-10-25：用户确认列表显示全文，并明确长文不属于产品目标，不做长文专项优化；列表不采用摘要和折叠，暂不开发。

2026-10-25：用户确认笔记列表按创建时间倒序，编辑旧笔记不前移；外屏和展开内屏列表采用同一规则，暂不开发。

2026-10-25：用户确认图片支持全屏查看、双指缩放，以及左右滑动浏览同一笔记的其他图片；缩略图布局继续确认，暂不开发。

2026-10-25：用户接受列表单张图片按原比例展示、多张采用整齐网格，点击查看完整图片；具体网格列数、尺寸与间距继续设计，暂不开发。

2026-10-25：用户接受外屏在底部导航上方放置固定「记录」按钮，展开大屏在左侧窄导航放置「＋」按钮，点击后在右栏编辑；初始输入行为继续确认，暂不开发。

2026-10-25：用户确认新建笔记后自动聚焦正文并弹出键盘，图片和音频通过编辑器内的附件按钮添加；退出编辑时的草稿保存方式继续确认，暂不开发。

2026-10-25：用户接受退出自动保存草稿，并授权助手按成熟惯例自主确定无明显个人偏好差异的常规交互，仅对明确存在用户喜好差异的选择询问；新增默认方案并关联主规划 D01—D08，暂不开发。

2026-10-25：用户确认进入关键页面设计阶段。已完成留白极简外屏／展开首页与编辑器四视图，上传可交互预览、补充视觉参数和验证边界；待用户视觉评审，暂不开发。

2026-10-25：用户明确表示“我很满意这一套设计”，认可当前留白极简外屏／展开首页与编辑器的整体视觉。将当前方案作为后续页面基准，保留现有预览及演示边界；继续设计，不开始 APP 开发。

2026-10-25：用户确认继续下一步。沿用已认可视觉完成配套页面与异常状态九视图，在 [配套页面与异常状态设计](supporting-ui-design.md) 中记录预览、流程和检查结果；配套设计待整体评审，未开始 APP 开发。

2026-10-25：用户明确表示“我很满意”，认可本轮配套页面与异常状态整体设计；两轮关键页面共同作为视觉基准，下一步进行开发准备，未授权开始 APP 开发。

2026-10-26：补充开发准备文档入口及自用、原生接口冲突保证决定；两轮视觉认可继续有效，未开始 APP 开发。

2026-10-26：用户确认 P02 完整缓存后离线编辑；同步配套预览与设计说明、主规划 R48 和开发准备验收标准，首页与编辑器既有视觉基准保持，未开始 APP 开发。

## 检查记录

2026-11-08：R69 已完成，累计 79 项不同检查通过；实际行数、Markdown 跨块、点击／长按、管理菜单、窄宽和后台状态、编辑取消及链接／省略号命中均已检查。主 Agent 复核折叠、展开、编辑、深色宽屏和菜单截图；0.1.12 保留数据覆盖安装手机并核验版本及启动，真实手势和实体折叠待用户试用。详情见开发准备。

已用独立浏览器检查四个方案的宽屏与小屏布局，未发现横向溢出或脚本错误。已核对示例中的关键词与单标签组合筛选、空结果、账号切换及无搜索控件的简化归档入口。此为设计预览检查，不是 Android APP 或真实服务端联调验收。
