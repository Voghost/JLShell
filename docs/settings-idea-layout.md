# 设置页 IDEA 布局适配

2026-10-14。默认 Swing 呈现路径。

## 实际阅读的 IDEA 源码

本地 `intellij-community-master`：

- `platform/platform-impl/src/com/intellij/openapi/options/newEditor/SettingsDialog.java`：`DialogStyle.COMPACT`，内容区与默认、取消、应用动作。
- 同目录 `SettingsTreeView.java`：隐藏根节点、导航树行高和左侧留白。
- 同目录 `SettingsEditor.java`：设置树、页面内容和搜索导航的分工。
- `platform/platform-impl/src/com/intellij/ide/ui/AppearanceConfigurable.kt`：Swing DialogPanel 的 UI DSL 表单、行布局、缩进及对应说明。
- `platform/platform-api/src/com/intellij/ui/dsl/builder/SpacingConfiguration.kt`：行距 6、组间距 20、标签间距 6、组件间距 16、对话框边距 10/12 等逻辑像素。

## 当前实现

- 设置外壳：240px 初始导航宽度、28px 标题栏、紧凑搜索框与树行高；路径区透明显示，带有真实的前进/后退历史、Alt+方向键和同组页面切换菜单。
- 常规页使用独立的 `SwingSettingsGeneralPage` 行布局，不再把旧 GridPane 等宽映射到 Swing。主题与主题色同排，字体与字号同排，输入框采用自然宽度；说明文字放在关联配置下方；组间距 20px、行距 6px。主题色名称与色点、字体及字号、连接/保活、维护、更新仍走原有校验和保存动作。
- 全部页面：统一深浅主题背景、分组标题/分隔线、说明、行距、输入框、按钮与焦点状态。导入页移除重复的页内侧边目录。
- 常规、账号、终端、导入、API、插件、快捷键、关于全部采用原生 Swing 控件；`SwingPreferencesDialog` 已移除 `SwingFxPanel`。插件市场/已安装列表用 JList，详情动态更新；顶部标签只保留一组。快捷键使用可交互的 JTree 行与原生按键录制，导入使用可编辑 JTable（认证下拉框、密码遮罩与删除动作）。
- 配色编辑、账号登录/注册及改密、导入 JSON 示例/粘贴与分享码输入均使用原生 Swing 子窗口；设置导入文件/目录由 JFileChooser 选择。
- 表单数据暂时继续通过 JavaFX 属性及原有控件模型绑定业务服务，创建视图前在 FX 线程快照，在 EDT 构建和更新 Swing 控件。没有创建 JavaFX Scene、skin 或 JFXPanel；这属于渲染迁移，尚未移除 JavaFX runtime。设置中的既有插件 Node 表单通过相同适配器转换，本次不修改插件 SDK，也不表示会话中的任意插件页面已经迁移。
- IDEA 使用自有 Swing DialogPanel/UI DSL；本项目借鉴其布局与模型/视图分工，控件基于 Swing + FlatLaf，不引入整个 IntelliJ 平台。

## 验证

`SettingsUiSmokeTest` 使用独立用户目录，验证原生页面无 JFXPanel、导入表格编辑/密码遮罩/JSON 粘贴、搜索、实际配置保存、Apply/Cancel/OK、历史导航保留未保存值、字体/字号同排、原生文本编辑、切换主题、颜色方案子弹窗和各页面截图。截图位于 `ui/target/review/settings-*.png`。

`PresentationUiSmokeTest` 使用本地回环商店和原有插件 JAR，验证市场动态详情、已安装选择、启用及插件配置保存回调；`MousePopupUiSmokeTest` 用系统鼠标和键盘验证原生快捷键录制及 Esc 取消。

## 品牌与页面视觉更新

- `SwingAboutPage`：设置页与菜单独立关于窗口共享的原生品牌展示。使用终端符号标记、36px 产品名称、低饱和主题色背景、四个项目入口、对齐的环境信息和次要版权文字；窄宽度时入口改为单列。保留检查更新、打开链接和复制环境报告的原有动作。
- `SwingTerminalFontPanel`：参考 IDEA `FontOptions` 的配置/预览分工，左侧紧凑字体配置，右侧实时字体示例；较窄时上下排列。
- 插件页：列表缩至约 38%，加强名称、版本等信息层级，统一头像和详情留白。
- 额外阅读 IDEA `ide/actions/AboutDialog.java`（产品图标、信息区及复制动作）、`application/options/colors/FontOptions.java`、`ide/plugins/PluginManagerConfigurablePanel.kt`。采用对应的页面分工，自绘 JLShell 品牌标记，不复制 IDEA 品牌资产。


## 2026-10-14 下拉框、字体列表及窄宽插件页修正

继续阅读本地 IDEA 源码：`ui/FontComboBox.java`、`ui/FontInfoRendererBuilder.kt`、`ui/popup/list/ComboBoxPopup.java`、`ide/ui/laf/darcula/ui/DarculaComboBoxUI.java`，以及插件列表 `ide/plugins/newui/ListPluginComponent.kt` 和 `TextHorizontalLayout.java`。

- 下拉菜单：原生 Swing ComboBox 保留鼠标、键盘及数据模型，弹层改为带细边框和阴影的圆角窗口；条目采用内缩圆角选中背景，收起态保持界面字体。弹层不参与后续业务窗口的 owner 选择。
- 字体选择：界面字体按等宽/比例字体分组；展开时以对应字体显示名称，不能显示名称时回退界面字体。列表宽度不逐个测量全部字体，避免过长名称撑大菜单；原有字体及字号保存逻辑保持。
- 常规表单：主题及主题色、字体及字号作为整体换行，按实际宽度计算行高，保持基线对齐和组间距。
- 插件列表：JList 跟随视口宽度，名称和简介在列表内省略，头像保留宽度，不出现横向滚动条。详情介绍依据可用宽度换行并计算高度。窄栏搜索框单独占一行，类型与搜索动作同排。
- 验证：`SettingsUiSmokeTest` 增加深浅主题弹层、字体分组与自身字体预览断言及截图；`PresentationUiSmokeTest` 使用长描述验证窄窗口列表宽度和详情换行；`MousePopupUiSmokeTest` 验证真实鼠标下拉选择；`NativeDialogsUiSmokeTest` 覆盖原生对话框路径。全部使用隔离用户目录。


## 亮暗切换后的缓存页配色修复

设置页面通过缓存原生表单保留输入状态，未选中的页面会从内容容器移除。此前主题刷新只遍历窗口组件树，遗漏了已缓存但暂未挂载的账号、终端等页面，导致重新进入时保留上一主题的背景和文字色。

统一主题刷新入口现在同时更新窗口及所有脱离组件树的缓存页，不重建表单。`SettingsUiSmokeTest` 先访问全部页面，再执行亮→暗→亮切换，逐页断言视口背景、输入文字色和页面实例保留，并输出 `settings-account-switched-dark/light.png`。隔离界面回归测试通过。


## 下拉弹窗跨页残留修复

圆角下拉弹层使用独立 JWindow，原输入框从设置内容区移除时，旧实现没有同步结束弹层生命周期。现在在输入框隐藏、外部点击、所属窗口失焦/关闭或移动缩放时，统一通过 JComboBox 的标准关闭动作收起菜单；关闭时移除临时的全局事件、层级及窗口监听器并释放弹窗。

`MousePopupUiSmokeTest` 使用真实鼠标和键盘覆盖条目选中、点击外部、Esc；另外在弹层打开时通过搜索切换设置页，断言原 ComboBox 已关闭且不存在残留的 `settings.comboWindow`。继续运行设置页主题及表单回归测试。


## 独立中英文字体、行距预览与主题边框

- `FontProfile.family` 保持原有英文字体/网格尺寸语义，新增可选 `chineseFamily`。中文及中文标点独立选字体，默认自动，保存到 `terminal.font.chineseFamily`；旧四参数构造器和旧配置继续兼容。
- `TerminalFontResolver` 由真实终端和预览共用，指定中文字体时即使英文字体也能显示中文，仍优先使用所选中文字体；缺字时自动回退。粗斜体及字号保持。
- 旧预览为普通 JTextArea，只订阅字体字号，未订阅行距；新 `TerminalFontPreview` 订阅完整 FontProfile，使用终端字体度量、网格与行距绘制中英文样例，调整后立即重绘。
- 底部按钮栏曾将亮色边框颜色固化在 MatteBorder 内，切暗后留下亮线。改用按当前 palette 绘制的 `SwingTheme.border`，同时覆盖字体预览分隔线及预览框边框。
- 验证：`TerminalFontResolverTest` 覆盖选择、样式、旧配置兼容与新配置重新加载；`TerminalVisualRefreshTest` 覆盖终端字号及行距刷新；`SettingsUiSmokeTest` 覆盖即时预览、中文字体及行距保存，并在亮→暗→亮切换中逐像素断言底部边框；真实鼠标下拉操作回归继续通过。
