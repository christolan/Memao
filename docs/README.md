# Memao 项目上下文

本目录统一维护 Memao 的需求、设计、开发和验收记录。开始项目工作前，先读 [项目协作规则](../AGENTS.md) 和 [需求与落地规划](planning.md)，再查阅当前任务相关的文档。

| 文档 | 内容 |
| --- | --- |
| [Android 客户端需求与落地规划](planning.md) | 产品范围、已确认需求、交互默认方案、待定事项和变更记录 |
| [首版开发准备与接口核对](development-and-acceptance.md) | Memos 0.31.0 接口、技术方案、实施里程碑、构建与验收历史 |
| [开发问题记录](development-issues.md) | 问题复现、修复、复验及设备验证缺口 |
| [首页 UI 方案对比](home-ui-design.md) | 留白极简方向、桌面品牌图标、列表、编辑器、抽屉和折叠屏设计 |
| [配套页面与异常状态设计](supporting-ui-design.md) | 连接、账号、草稿、归档、设置、离线及冲突交互 |
| [Lucide 图标优化落地方案](lucide-icons.md) | 图标映射、Android 接入、验收截图、离线画廊和素材包 |

## 版本与验收状态

- 当前构建配置为 `0.1.28`（versionCode 29），包含 [标签胶囊与正文筛选](development-and-acceptance.md#标签胶囊与正文筛选)及此前的侧边栏和笔记卡片排版。沿用 [应用内更新](development-and-acceptance.md#应用内更新)与[按版本发布](development-and-acceptance.md#侧载发布条件)。
- 用户反馈 `0.1.16` 可在 Find N6／Android 16／ColorOS 上启动。[`0.1.17` 的登录输入框居中修正](development-issues.md#i31-登录输入框光标偏上) 已通过 Android 16 模拟器验证，目标设备上的居中效果仍待确认。启动问题的修复记录见 [I30](development-issues.md#i30-官方优化包启动即退出)。
- 各版本的安装、模拟器、隔离服务端和实机证据见 [开发准备与验收](development-and-acceptance.md)。历史交付记录中的 APK 名称与构建路径用于追溯当时的产物。

## 文档维护

用户确认的调整写入相关正文和变更记录，并同步更新受影响的验收条件。保留原有需求、问题编号和当时的证据，注明后续复核结果。原型、截图与图标交付素材按主题存放在 `assets/`，HTML 预览可下载后在本地浏览器打开。
