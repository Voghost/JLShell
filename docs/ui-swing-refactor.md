# Swing / IDEA Islands 界面重构验收

本地分支：`feature/idea-ui-refactor`，基于 `origin/develop` 的 `2b06768`。
独立工作目录：`JLShell-ui-refactor`。原工作目录、原分支及已安装应用均未切换。

## 实现边界

- 主窗口、菜单、工具窗口栏、项目切换、连接树、欢迎页和会话标签使用 Swing。
- JediTerm 原生嵌入 Swing，SSH 和本地终端均不再经过 `SwingNode`；JavaFX 对照入口仍保留。
- Swing 使用 FlatLaf 3.5.4 和统一的 IDEA New UI 语义配色、控件尺寸及 HiDPI 布局。
- 设置窗口的搜索树、面包屑、分栏和底部动作栏使用 Swing；文件页、原有设置/账号/连接表单和插件内容通过 `JFXPanel` 兼容承载。插件工具栏和服务器状态条保留原视图及事件处理。
- 业务动作继续调用原控制器。未修改 SSH/SFTP 服务、数据库、账户、更新协议、插件加载器、插件 SDK 或 RPC/会话注册机制。

依据用户提供的 IDEA 截图，进一步对照本地源码中的 Islands UI：

- `ManyIslandsDark/Light.theme.json`：颜色层级、24px 树行高、工具窗口栏和主工具栏。
- `IslandsUICustomization.kt`：4px 内容间距、16px 圆角直径及独立工具窗口面板。
- `IslandsTabPainter.kt`：圆角选中页签的背景和细边框。
- `MacToolbarFrameHeader.kt`：主工具栏与 macOS 窗口按钮处于同一标题区。
- `SquareStripeButtonLook.kt`、`ToolWindowHeader.kt`：窄工具栏、工具窗口标题和操作位置。
- `JBTerminalWidget`、`JBTerminalPanel`、`ShellTerminalWidget`：原生 Swing 终端集成。

文件夹、终端、设置、搜索、更多、新建和关闭图标使用 IDEA 原始 SVG 路径，转为一次初始化的
Java2D 图形；原始 SVG 和版权头保留在 `icons/idea/`，绘制过程不解析 SVG 或跨 UI 线程。
IDEA 图标及 FlatLaf 的 Apache 2.0 许可证和来源记录均随 UI 资源保留在 `third-party/`。
未引入 IntelliJ 平台服务或改变 JLShell 插件契约。

## 项目弹窗、快捷菜单和设置窗口

进一步读取本地 IDEA 的以下实现，并把可独立运行的 Swing 组件及渲染结构适配到宿主：

- `SelectablePanel.kt`：直接移植为 Java，保留选中区域的内边距、圆角和绘制算法。
- `GroupedItemsListRenderer.java`、`GroupedElementsRenderer.java`：分组标题/分隔线、图标槽、文字和快捷键列。
- `ProjectToolbarWidgetAction.kt` 中的 `ProjectWidgetRenderer`：24px 项目图标、名称与次级信息双行条目、搜索列表。
- `SettingsEntryPointAction.java`：设置快捷列表及主题/视图子菜单的分组结构。
- `SettingsDialog.java`、`SettingsEditor.java`、`SettingsTreeView.java`：左侧搜索树、150ms 搜索延迟、右侧粗体面包屑、分栏和底部取消/应用/确定。

对应实现位于 `ui/swing/idea/` 与 `SwingPreferencesDialog.java`；完整来源和 Apache 2.0 许可证保留在 `third-party/`。
项目条目的次级信息读取原项目描述；JLShell 没有 IDEA 的仓库克隆和最近项目业务，不添加虚构动作或路径。
顶部主菜单、齿轮、项目选择、侧栏更多及右键菜单使用统一列表绘制。支持键盘、搜索、悬停子菜单和父子列表关闭；快捷键显示读取宿主原快捷键设置。

再次对照 `ListPopupImpl`、`PopupDispatcher`、`AbstractPopup`、`PopupBorder` 和
`WindowRoundedCornersManager`，将列表放入独立的 owned `JWindow`，不再由
`JPopupMenu/MenuSelectionManager` 管理列表鼠标。活动根弹窗和子弹窗链只在显示期间注册
AWT 鼠标、焦点和键盘分发器；关闭时卸载。子菜单等待 250ms，并采用直接移植的
`MouseMovementTracker`/`ScreenUtil` 算法保护朝向子菜单的鼠标移动。
`GroupHeaderSeparator` 的几何和分组绘制直接移植；选中行内缩 8px、圆角直径 8px，
菜单外轮廓半径 8px。IDEA 使用 JBR 原生圆角，本工程在 OpenJDK 上以透明窗口、
Java2D 轮廓和软件阴影适配，不引入 IDEA 平台运行时。不支持透明窗口时使用不透明回退。
深色菜单使用 `#25282c`，亮色使用白色；快捷键和禁用条目使用次级文字色。

菜单边框不再叠加深色外轮廓、亮色内轮廓和背景三层填充。对照 `PopupBorder`、
`RectanglePainter2D.DRAW` 的 INSIDE 分支和 `PaintUtil.alignToInt`，使用单色、1px 向内
描边，一次填充外/内圆角路径之差；按设备像素对齐坐标及线宽，边框在内容之后绘制，
避免上下亮边和圆角被裁切。独立检查明暗主题的 100%/125%/150%/200% 渲染；
这四种缩放下四边的边框及相邻内部像素逐像素一致，并检查真实窗口截图。

设置保留原来的八个配置页和保存处理，通用页面改为单列分节表单，控件限制宽度并统一边框；终端页面去掉重复的内部目录。
Swing 与 FX 分别使用嵌套事件循环，设置调用在窗口关闭后才返回，因此原有刷新连接、字体与快捷键的后续动作仍保持原顺序。
配色编辑等嵌套 FX 弹窗使用传入的宿主 Stage，并在显示期间禁用 Swing 设置窗口，避免嵌入场景没有 Stage 的问题。

真实鼠标复现了设置下拉框能展开却无法选择的问题：AWT 的 `APPLICATION_MODAL` 过滤器
阻止了嵌入 FX 场景创建的 Glass 弹窗。现以无 AWT 模态过滤的设置窗口和禁用宿主窗口实现
模态交互，保留 FX 嵌套循环及原保存顺序；关闭后恢复宿主原状态。
“应用”立即刷新已有 Swing/FX 页面的主题、字体及行高，原保存与取消逻辑不变。
FX 内容的 Esc/Enter 先交给原处理器，录制快捷键时 Esc 只取消录制，不关闭设置。

依据 `KeymapPanel` 与 `KeyboardShortcutDialog`，快捷键页改为固定行高的虚拟化动作树，
保留原动作分类、主/次快捷键录制、冲突检测、清除和恢复默认处理器。
行宽跟随实际视口，避免复用树单元格时发生宽度反馈导致右侧按钮裁切。
字体/字号表单参考 `FontComboBox`、`AppFontConfigurable` 和 `AppearanceConfigurable`；
UI 字号、终端字号、行距和回滚行数改用数值步进控件，保留原取值范围及保存模型。

设置、独立关于、项目管理进一步参考 `DialogHeader`、`DialogWrapperPeerImpl`、
`IntelliJSpacingConfiguration` 和 IDEA `AboutDialog`。macOS 使用完整窗口内容、透明
原生标题区及主题化 Swing 标题，保留系统窗口按钮和拖动；Windows 使用 FlatLaf 窗口
装饰。关于和项目管理复用原 FX 内容与关闭事件，并由同一个非 AWT 模态 Swing 外壳
承载；嵌套原 FX 对话框打开期间禁用外壳，关闭时恢复宿主。

修复状态/版本标签背景与文字同为蓝色、项目列表误用文字色作为背景的问题。标签按
文字宽度布局，使用浅主题色背景及正常文字色。对话框专用包装层使用统一面板色，
不影响主终端或外部插件自己的 inline style。设置采用 6px 行距、20px 分节间距，
账户页改为单列，登录按钮允许换行；项目描述框限制高度，操作按钮在窄窗口下换行。
关于页采用两列入口及紧凑项目/运行信息，正文使用界面字体和整数次级字号，移除旧
卡片堆叠和低对比装饰。关于、项目管理通过 JFXPanel 按实际显示比例绘制，避免混用
旧独立 FX 窗口的装饰与字体管线；不强制把截图放大后的效果作为真实清晰度指标。

## 窗口结构与会话工具窗口

顶部使用一体化主工具栏，放置项目选择、连接动作和主菜单入口；macOS 保留原生窗口按钮，
Windows/Linux 使用 FlatLaf 的窗口装饰和按钮占位。主菜单保留原业务动作及动态启用状态。

macOS 复用原 MenuBar 动作，并通过 `Desktop.setDefaultMenuBar(JMenuBar)` 注册 Swing 原生
系统菜单；Swing 窗口内不增加菜单行，菜单打开时从原注册表刷新动态条目。Windows/Linux
则在 Swing 标题区显示菜单。
工作区标签与正文之间不再绘制内容分隔线，正文承载层独立裁剪四角；各侧栏及正文的
`IslandPanel` 作为子控件重绘起点，终端更新、目录树及滚动视口的局部重绘仍经过圆角裁剪。
右侧文件/插件尚未打开或已收起时，将导航轨道移到分割布局外，卸载右侧内容及拖动分隔条；
欢迎页不保留右栏。只有打开具体工具页后才恢复可调整宽度的右侧面板，缩放窗口不会重新撑开空白区域。

连接树跟随视口宽度；长连接名与分组名通过 JLabel 原生省略号绘制，保留节点完整名称，
悬停显示完整名称及连接地址，不再产生横向滚动条。标签溢出列表复用 IdeaListPopup 的圆角、
列表悬停、键盘选择与关闭行为；长标题限制弹窗宽度并提供完整提示，重复标题按组件身份定位。
标签悬停及溢出/滚动按钮采用统一的 headerHover/headerPressed 配色。项目、会话和菜单选中标记
均绘制 WorkbenchIcon.CHECK 矢量图形，避免 Windows 缺字方框。插件固定按钮采用 Lucide pin
图标，已固定时使用主题强调色，原始 SVG 及 ISC 许可随资源保留。
标签拖放的分屏命中仅覆盖正文区域；整条标签头（含左右边缘及空白处）只排序或跨组移动，
不显示分屏预览，也不提交分屏。预览与释放使用同一套命中规则。
分屏时每组保留选中标签，但只有当前活动组绘制蓝色选中背景；其他组绘制中性底色与边框。
点击标签、正文获得键盘焦点及会话切换时同步活动组；转入文件/插件工具窗时保留最近的活动组，
避免工具窗操作导致会话上下文和高亮消失。深浅主题分别配置非活动标签颜色。
最左侧 macOS 应用菜单由 `MacApplicationMenu` 在启动时根据已保存的 `ui.language`
翻译“关于、设置、服务、隐藏、退出”。通过 JNA 调用公开的 Objective-C/AppKit API，
只设置标题，保留 JDK 注册的命令、Services 子菜单与系统快捷键。操作异步交给 AppKit
主线程，FX/EDT 均不等待；Windows/Linux 不加载 Objective-C 运行时。语言修改重启后生效。
连接面板的搜索按需展开，新建文件夹、编辑、删除、刷新和项目管理可从标题栏更多菜单进入，
右键操作、拖放移动和快捷键继续保留。

SSH 和本地终端固定显示在主工作区，不再把终端移到底部或显示空白占位。
底部会话工具窗口承载原有服务器监控、文件、插件和插件动态页面，通过原 Tab 模型切换。
文件页继续按原逻辑懒加载，服务器监控继续使用原采样及悬浮明细，插件仍使用原 Node 和事件。
隐藏、显示或切换工具窗口均不移动主终端、不更换连接器、不重新激活插件。
拖动横向分隔调整工具窗口高度，切换会话时保留高度。本地会话不显示空的辅助工具窗口。
左工具栏仅保留一个连接面板按钮；项目管理仍可从顶部及侧栏更多菜单进入。
侧栏单元格清理复用状态并按实际字体度量行高，避免图标/文字重叠。

## 插件兼容

插件仍返回同一个 JavaFX `Node`，不要求重新编写 Swing 界面。兼容容器只给外层包装节点
应用宿主主题，保留插件自身样式表、inline style 和按钮事件；事件仍在 FX 线程执行。
关闭容器时解除主题监听并释放嵌入 Scene；插件激活/停用和会话资源释放仍走原有路径。

终端右键也使用相同列表窗口。terminal 模块仅增加展示回调接口，UI 模块负责菜单呈现，
没有模块反向依赖；菜单名称、可用状态、快捷键和回调继续来自原 JediTerm 动作链。

## 重绘与拖动

对照 `DefaultScrollBarUI` 的窄轨道重绘范围，终端滚动条设为不透明并单独绘制背景，
避免拖动透明滑块时扩大父容器重绘区域。主题刷新缓存有效配置，重复刷新不重装 LAF；
终端原字体/配色刷新合并继续保留。配色预览的色块和文字节点仅创建一次，透明度拖动
直接更新绘制属性，不再每次重建节点或格式化/解析 inline CSS。原整数字号与滑块小数值相互写回的震荡路径以数值步进
控件替代；动作树虚拟化并缓存字体枚举，减少设置页重排和重复扫描。
这些变更消除已定位的额外重绘路径；没有进行全平台帧率基准，不承诺固定帧率。

新增依赖暴露了原有版本读取问题：类路径中的第一个 manifest 可能来自 FlatLaf，导致将
`3.5.4` 错认成插件宿主版本。现限定读取承载 JLShell UI 类的 JAR manifest，避免改变插件兼容判断。

## 独立预览

从本工作目录执行：

```bash
./ui/run-preview.sh
./ui/run-preview.sh --javafx --no-build
```

脚本仅执行 `mvn package`，不执行 `mvn install`，不会以相同坐标覆盖原工程使用的本地模块。
默认配置、数据库和密钥位于本工作目录的 `ui/target/preview-home/.jlshell`。
预览入口关闭自动更新提示；正常启动入口的更新行为保持原样。

复用已经打包的预览产物：`./ui/run-preview.sh --no-build`。
通过 `JLSHELL_PREVIEW_JAVA_HOME` 指定 JDK 21；通过 `JLSHELL_PREVIEW_HOME` 指定独立预览目录。
测试已有插件时只将插件 JAR 放入预览目录下的 `.jlshell/plugins` 或 `.jlshell/program-plugins`，
无需复制原用户数据库、密钥或凭据。

## 验证

```bash
JAVA_HOME=<JDK21> mvn package -Djlshell.ui.smoke=true
```

桌面验证覆盖明暗主题、打开的对话框、插件样式保留和按钮事件、4,000 个连接的虚拟化、
搜索后的文件夹展开状态、设置导航，以及终端刷新合并和配色更新不触发字体重排。
`jlshell.ui.smoke` 需要桌面环境，无显示器的 CI 默认跳过这组验证。

macOS 实际运行使用独立示例数据库，检查了原生本地终端、中文输出、设置窗口和明暗主题，并确认标签排序后两个终端视图都保留。
在最终打包 JAR 中，用仓库原有 Script Snippets 示例插件完成外部 JAR 的发现、激活、界面交互与停用验证，未修改插件源码。
上一轮完整测试与打包通过：143 个测试，0 失败、0 错误、0 跳过。回归检查主终端及缓冲区在工具窗口显隐、会话排序和插件页切换后保持完整。
`SettingsUiSmokeTest` 在独立 JVM/数据库中检查真实齿轮菜单的主题子菜单打开/返回、设置搜索切页、应用保存且不关闭、取消丢弃后续修改、确定保存并关闭，以及原配色编辑弹窗打开/取消与父窗口禁用恢复。
预览图片保存在 `ui/target/review/`，包括旧版插件界面。

`MousePopupUiSmokeTest` 使用独立 JVM、数据库和专用测试窗口，真实 Robot 鼠标验证齿轮
主题子菜单及 Esc 返回后重开、设置页 FX 主题下拉选择、应用后即时刷新及保存、字体名称/字号生效到原连接树和 FX
表单、快捷键录制与 Esc、明暗终端右键菜单原动作、终端历史滑块拖动，并检查 macOS 原生
菜单安装且主窗口不增加菜单行。终端测试用模拟内容，不连接真实 SSH 或读取用户凭据。
macOS 全局菜单现直接由 AWT Desktop 管理；点击行为仍通过原菜单动作模型分发。

本次已读取本机 IDEA 源码及用户截图；IDEA 应用控制接口返回系统 Computer Use 权限未授予，
没有更改用户 IDEA 的主题或设置，也不将其现场操作列为完成项。

`DialogUiSmokeTest` 在独立配置中检查设置账户状态/关于版本标签的实际文字与背景对比、
标签宽度、关于和项目管理的 Swing 外壳、原关闭事件和宿主恢复、原项目保存回调、
“检查更新”入口关闭窗口后调用原回调，并核对 JFXPanel 后备缓冲区比例与当前显示器
比例一致。明暗设置账户、关于和项目管理截图保存在 `ui/target/review/`。

合并前仍需在 Windows/Linux 进行实际窗口、系统缩放和中文输入法验收，并以真实 SSH/SFTP
会话检查断线重连、插件开页及关闭释放。当前没有真实远端连接，不将这些列为已验证项。
在验收完成前，不合并到 `develop`，不切换原工作目录。

## 正式启动路径的死锁回归

通过用户使用的 `ui/run-preview.sh --no-build` 和全新隔离目录复现了启动页卡住。
线程堆栈显示 FX/AppKit 线程在 `installSystemTray -> Menu.add` 等待 AWTTreeLock，
而 EDT 在连接树绘制期间持有该锁并等待 macOS 输入法/原生事件循环，形成死锁。

系统菜单注册、托盘创建和移除现在都异步交给 EDT，FX 不等待 EDT。
改动仅涉及 UI 启动与系统窗口集成，不改业务服务及插件加载机制。
`DesktopStartupSmokeTest` 在独立 JVM 调用真实 `Launcher`，使用全新配置检查启动页关闭、
Swing 主窗口显示、系统托盘加载、FX/EDT 响应和正常退出。该测试已通过；
最终打包文件通过用户预览脚本再次冷启动核验，线程已回到正常事件循环。

## SSH 终端空白与辅助窗口回归

此前将 SSH 终端从 CardLayout 移出时，组件被布局器设置为不可见，挂入底部容器后没有恢复，
因此 SSH/Shell 初始化成功而终端显示为空。现将原生终端固定在主工作区，并显式恢复句柄
组件可见性；底部只显示文件、插件和监控页面。窗口第一次显示时重新定位分隔，避免在
布局尺寸尚为零时将主终端压缩到过小的区域。

`SshWorkspaceUiSmokeTest` 使用原 SessionWorkspaceTab 初始化流程和模拟 SSH/文件服务，
检查终端实际显示尺寸与 isShowing、插件节点样式及动作、工具窗口切换、原缓冲区保留和关闭。
不访问真实远端、不读取测试之外的凭据。另修复了 SFTP 文件树在关闭时 selection 中存在
空项导致的 UI 空指针异常；SFTP 业务服务未改。

## 新建连接与插件页补齐（2026-10-13）

新建/编辑连接复用原 FX `Dialog<ConnectionFormData>`，由统一 Swing 外壳展示。
`SwingDialogHost` 原样触发源按钮，保留必填禁用绑定、按钮事件过滤及结果转换，
取消返回空结果。表单使用 6px 行距和可滚动内容，只展示当前认证方式的字段；
认证及主机密钥校验枚举仅本地化显示，底层值不变。选择凭据库时移除对已绑定控件
`disable` 属性的重复写入，避免选择/清除凭据触发 UI 绑定异常。

插件页实际阅读并对照 `PluginManagerConfigurablePanel.kt`、
`TabbedPaneHeaderComponent.java`、`ListPluginComponent.kt` 与
`PluginDetailsPageComponent.kt`。市场/已安装切换放入设置顶部栏；内容独立切换，
列表去掉外层卡片和逐行亮线，使用 10px 条目内边距和明确的选中背景。详情标题、
作者与操作行分开，窄窗口下动作可换行，原简介、更新说明、元数据与插件设置节点
分别显示在概览/更新说明/其他信息中。修复作用域与启用状态文字和背景同色。

`PresentationUiSmokeTest` 使用全新用户目录及回环 HTTP 商店，未连接真实 SSH。
检查连接确定的源校验绑定、原测试回调、凭据选择/清除与认证切换、编辑结果完整
保留、取消无结果、本地终端隐藏字段不占位；源码中原有 Session/Program Demo
未经修改编译打成外部 JAR，验证原加载器发现、启用回调及插件设置按钮写入自身
存储。明暗截图：`connection-dark/light.png`、`plugins-marketplace-dark/light.png`、
`plugins-installed-dark/light.png`，均在 `ui/target/review/`。

本轮回归共 144 项：144 项通过，0 失败、0 错误、0 跳过。最初桌面休眠/锁定时，
真实鼠标验证停在前台窗口检查；用户解锁后补跑成功。测试等待 macOS 一体化标题栏
坐标稳定，并等待异步主题应用完成，未放宽结果断言。设置、对话框及插件集成已
重新验证，预览包已生成，原工作目录未切换、未合并。

额外以 `-Djlshell.ui.realMouse=true` 运行 `PresentationUiSmokeTest`：测试进程先激活
自己的窗口，再以 Robot 实际点击连接的确定/取消、市场/已安装页签、旧 Session
插件启用及原 Program 插件的 Save note 按钮，全部通过。插件保存检查原命名空间
中的真实写入结果，不以点击事件次数代替保存验收。

## 一级菜单宽度与分类图标

按后续确认，仅把主菜单最小宽度从 330 调整为 144，当前层文字较长时仍自动撑宽，
并为文件/连接/账号/视图/帮助添加 16px 分类图标。账号、视图、帮助使用 IDEA 的
原 SVG 路径；二级菜单的宽度、定位、边框及动作均未修改。明暗实际主菜单检查
均为 144 逻辑宽度、5 个分类图标；现有 SettingsUiSmokeTest 菜单导航验证通过。
截图位于 `ui/target/review/main-menu-compact-dark/light.png`，预览包已更新。

## Windows Preview 布局反馈

进一步阅读 IDEA `platform/platform-api/src/com/intellij/ui/tabs/impl/DragHelper.java`
与 `platform/platform-impl/src/com/intellij/openapi/wm/impl/customFrameDecorations/header/toolbar/IdeMenuHelper.kt`。
工作台标签在按下时锁定来源，拖动超过阈值后按抓取偏移跟随鼠标，越过邻近标签中点
即时调整预览顺序。仅重绘标签带，按设备缩放渲染拖动影像；邻近标签和松手归位使用
160ms 缓动。松手只向原 FX 模型提交一次排序，Esc 或拖到栏外还原原顺序。
不替换会话视图、连接器、终端缓冲区或插件节点。

Windows/Linux 在一体化顶栏横向展示原有文件、连接、账号、视图、帮助等菜单，菜单
动作及动态启用状态仍来自原 FX 模型，支持 Alt 字母入口。窄窗口恢复紧凑菜单入口，
避免挤占窗口按钮；macOS 继续使用已有系统菜单。

顶部会话名改为可搜索的已打开会话选择器，当前会话带勾选标记，同名会话按各自对象
切换，不调用连接动作。原连接按钮移动到连接列表底部，仍使用原有侧栏选择和连接回调。

底部只保留 Swing 会话工具导航。宿主内层 TabPane 使用定向 CSS 隐藏旧标签头，规则在
主题变更和皮肤重建后持续生效；外部插件自己的 TabPane 不受影响。
应用及启动器依赖模块连同桌面回归共 148 项通过，0 失败、0 错误、0 跳过。

## IDEA 标签组与分屏（2026-10-13）

本轮重新接通 Computer Use，实际在 IDEA 中执行 Split Right、Split Down、
Change Splitter Orientation 和 Unsplit All，检查嵌套组后恢复原单组布局。
未编辑 IDEA 当前打开的业务文件。对应读取本地 IDEA 源码：

- `platform/platform-impl/src/com/intellij/openapi/fileEditor/impl/EditorWindow.kt`：
  `split` 的二叉分屏、初始 50/50 比例、兄弟组查找及 `unsplit/removeFromSplitter`。
- `platform/platform-impl/src/com/intellij/ide/actions/SplitAction.java`、
  `SplitterAction.java` 与 `MoveEditorToOppositeTabGroupAction.kt`：动作可用状态、移动与合并。
- `platform/platform-impl/resources/idea/PlatformActions.xml` 的 `EditorTabPopupMenu`：
  按组关闭、分屏和合并的菜单分组。
- `platform/platform-impl/src/com/intellij/ide/ui/laf/darcula/ui/DarculaMenuItemUIBase.java`：
  紧凑菜单的图标、文字和快捷键列。

`WorkspaceSplitLayout` 用递归 Swing 标签组实现左右/上下嵌套分屏，支持切换方向、
移动到相邻组、取消当前分屏和全部合并。关闭组的最后一个标签时收起空组。
分隔条连续布局；原标签拖动动画保留，跨组松手仅提交一次原排序回调。

IDEA 可以为同一文件创建两个编辑器视图。终端承载已有 SSH 会话，因此 JLShell
分屏移动当前会话视图到新组，不自动复制连接；仅有一个标签时保留空来源组，
可把另一个已有标签拖进去。终端宿主、缓冲区、插件 Node 与原关闭回调保持不变。

标签菜单采用独立紧凑样式：统一预留 16px 图标列，12px 内侧间距、6px 上下留白，
按组关闭与分屏动作之间使用分隔线；原明暗菜单边框不叠加第二条描边。
分屏图标使用 IDEA 原 SVG 几何并保留 Apache-2.0 声明。每组右侧有操作入口，
支持 Shift+F10/菜单键；关闭快捷键服从用户已有自定义绑定。

回归命令：`mvn -q -pl app,launcher -am test -Djlshell.ui.smoke=true
'-Dtest=*,!MousePopupUiSmokeTest' -Dsurefire.failIfNoSpecifiedTests=false`。
149 项通过；随后补充并通过跨组拖放和右键菜单点击用例，累计 151 个独立用例通过。
`SwingWorkspaceSplitTest` 的四个用例检查嵌套布局、方向切换、移动/合并保留缓冲区和插件组件、
组内关闭范围、拖放只提交一次排序，以及从标签右键弹窗点击“向右分屏”后的布局变化。

一次全量回归中的旧 `MousePopupUiSmokeTest` 未通过：系统鼠标选择嵌入主题菜单后
仍为 Light，期间 Computer Use 同时切换了前台窗口。本轮没有修改该测试或放宽
断言，不能把它计入通过结果。CUA 已能显示本地验收窗口并检查操作入口位置，
但后续点击/按键多次没有形成可观察的变化，鼠标完整分屏验收仍未完成。
Windows/Linux 的系统窗口交互也仍需要各自实机验收。

本轮仅本地修改、构建，没有推送 `preview/ui-refactor`，没有 dispatch Preview
Actions，也没有修改发布工作流或网站。

## 工作台欢迎页与顶部控件（2026-10-13）

对照原 `ui/view/WelcomePane.java` 的 640px 居中内容、图标、快速开始、
最近/收藏两列和快捷键说明，Swing 欢迎页改成最多 680px 宽的紧凑区域。
原新建连接、新建文件夹及最近连接回调保持不变；仅调整显示和排版。
移除顶部重复的新建连接、搜索按钮，侧边栏入口继续提供这两个动作。
左侧工具条选中背景参考 IDEA `SquareStripeButtonLook.kt` 改为 8px 圆角；
项目、会话和侧边栏标题的下拉箭头改用 IDEA 16px `chevronDown.svg` 几何，
不再用文本字符。侧边栏底部连接按钮使用圆角样式。
欢迎页次要文字在主题切换时重新取色。IDEA 代码和图标来源补入
`ui/src/main/resources/third-party/idea-NOTICE.txt`。
本地 Swing 欢迎页画面已检查；相关的 `WorkbenchUiSmokeTest`、
`SwingWorkbenchDockTest` 和 `SwingWorkspaceSplitTest` 共 12 项通过，
`app` 模块打包成功。未触发 Preview Actions。

## Windows/Linux 标题区一致性（2026-10-14）

- Windows/Linux 顶部横向菜单使用 HeaderButton，与项目选择器、设置按钮共用 headerHover/headerPressed 圆角绘制，避免混入 FlatLaf toolbar 默认悬停背景。
- WindowHeaderSupport 统一窗口按钮占位：跟随 FlatLaf 的实际按钮宽度，额外保留 12px 间距；标题标签增加内边距，非 macOS 设置窗口不再压缩为 28px，统一为 34px 标题区。
- Windows 原生窗口装饰可用时，集成工具栏使用 FlatLaf 的 JComponent.titleBarCaption 命中检测，不安装 setLocation 鼠标拖动处理器。空白区域交给 Windows 的原生 caption 拖动流程，按钮区域仍接受操作，使系统贴边分屏/原生窗口管理能够工作。其他平台保留移动回退。
- MousePopupUiSmokeTest 和 SettingsUiSmokeTest 在 macOS 上通过。Windows 原生贴边吸附、最大化与多屏 DPI 需要在 Windows 实机验收，不能用 macOS 的 Swing 回归结果替代。

### macOS 终端输入法空指针（2026-10-14）

JediTerm 3.61 的 `MyInputMethodRequests.getCommittedText()` 返回 null；JDK 21 的
`CInputMethod.attributedSubstringFromRange()` 会直接调用迭代器的 `getEndIndex()`，
因此 macOS 输入法查询终端上下文时可能打印 InvocationTargetException/NPE。
`RefreshableTerminalPanel` 现在通过 `TerminalInputMethodRequests` 包装该接口：
无 committed text 时返回新的空迭代器，保留原来的 length=0、候选窗定位、选区与取消语义。
不以终端输出/历史填充输入法上下文，也不关闭中文输入或屏蔽异常日志。

`RefreshableTerminalPanelImeTest` 以真实终端面板重复执行 macOS 的迭代器读取顺序，覆盖
null/指定属性、独立迭代器以及合法的无选区状态；与字体/行距刷新回归共 5 项通过。

### 连接入口与预览运行包隔离（2026-10-15）

连接列表侧栏入口改用 IntelliJ `expui/nodes/ssh.svg` 的 SSH 图标，保留原始资源和
Apache-2.0 来源记录，Java2D 路径沿用统一主题颜色与选中状态。

`ui/run-preview.sh` 为每次运行复制应用 JAR 和内置系统监控插件，Java 退出后清理副本。
避免运行中执行下一次 `package` 覆盖原 JAR，导致延迟类加载出现
`TerminalActionPresentation` / `ThrowableProxy` 缺失，进而中断终端按键与异常日志处理。
`PreviewScriptRuntimeTest` 对比直接运行构建包和脚本独立副本，运行中覆盖原应用及插件，
验证前者类加载失败、后者仍可加载未使用的类和原插件，并在退出后清理副本。
已发生类加载失败的旧实例需要退出后重新启动。

### 目录上报初始化与 SFTP 默认跟随（2026-10-15）

原先在终端开始渲染之后输入 OSC 7 钩子，只按原文匹配回显；zsh/readline 的
着色、换行与重绘会打断匹配，执行内部命令还会额外产生一份提示符。
现在终端工厂先在 I/O 执行器提交会话级初始化，再启动渲染；初始化事务使用随机
开始标记和附加在额外提示符末尾的一次性结束标记。保留开始标记前的登录 banner、
登录提示和第一份提示符，仅移除带控制序列的初始化回显及额外提示符。
脚本不修改远端 rc 文件，保留 bash 原有字符串/数组 PROMPT_COMMAND 和 zsh precmd hooks。
初始化输入采用 gzip/base64 缩短至 1024 字符以内，避免小 PTY 规范行缓冲截断；
远端需要 bash/zsh、base64、gzip。事务有 5 秒和 128 KiB 上限，异常时恢复显示，
不会无限隐藏登录提示或阻塞交互。真实 PTY 测试覆盖 bash 3.2、zsh 彩色多行提示符、
登录 banner、中文/空格/百分号目录，以及单字符分包的语法高亮回显。

OSC 7 解析拆为独立流式解析器，支持 ESC ]、BEL、ST 在任意 read 边界拆分；
正确解码百分号编码并保留字面加号，限制异常序列长度，忽略非法路径。
Swing SFTP 默认跟随本会话终端，首次打开立即对齐已记录的目录；远端工具栏的
定位图标和菜单共用跟随状态，暂停后可独立浏览，重开立即同步。
跟随比较当前请求目标而非已显示目录，避免 cd 到慢目录后又返回原目录时被旧响应覆盖。
关闭面板解除监听，断线禁用跟随操作。

终端协议、真实 shell、SFTP 跟随/暂停/关闭、SSH 工作区共 11 项定向检查通过并打包。
较广的既有 SFTP Robot 弹窗用例在本机两次遇到窗口焦点/按键时序问题，未计入通过项。

### Powerlevel10k 首屏提示符丢失修复（2026-10-15）

用户反馈 zsh 首次连接仅显示瞬态箭头，回车后才显示完整多行及右侧提示符。
前一版按纯文本计数验证提示符，未覆盖主题的 zle-line-finish 重绘：Powerlevel10k
在接受内部初始化命令时会收缩提示符，并按命令实际换行数回退光标、重新绘制输入。
删除命令文字却保留这些光标回退会擦掉正常首屏；随后隐藏完整的新提示符则只留下箭头。

初始化事务在当前会话检测 Powerlevel10k 瞬态模式，以随机协议标记告诉过滤器：
保留初始完整提示符和 banner，移除内部输入及其 line-finish 光标回退、收缩和再次回显。
不改变用户的瞬态提示符配置，不修改远端启动文件；普通用户命令继续保留主题原有行为。

验证使用官方 Powerlevel10k 源码临时 checkout d05a1b0（不打入产品），配置多行左提示、
右提示和 transient=always，在 50/120 列真实 zsh PTY 中执行初始化及 cd。
测试将过滤后的 ANSI 输出输入 JediTerm，检查最终屏幕的 banner、完整第一行、右侧内容
及提示符唯一性，而非只检查原始字节流中出现过某段文字。另有默认执行的合成回归覆盖
16 行隐藏输入对应的光标回退和重绘。真实主题用例通过
-Djlshell.p10k.source=/path/to/powerlevel10k 启用，不依赖 CI 联网下载。

本次终端、目录上报、SFTP 跟随和工作区共 13 项定向测试通过，with-plugins 打包成功。
主题行为参考：https://github.com/romkatv/powerlevel10k#transient-prompt 。

### 原生首页视觉整理（2026-10-15）

首页继续使用纯 Swing。以更大的品牌标识、标题和简短说明建立视觉中心；
“新建连接”使用主题色主按钮，新建文件夹为次按钮。最近连接与收藏采用并排的
圆角分区，连接名称下显示主机信息，长名称保留省略与完整提示，点击继续走原有连接动作。
最近会话按连接 ID 去重；空列表提供图标和简短引导。底部仅保留两项常用快捷键与操作提示。
内容最大宽度 840px，随窗口高度调整上方留白；窄于 580px 时两分区上下排列，整页可滚动，
避免压缩卡片或裁剪操作。主按钮颜色与次要文字跟随主题，新增文案提供中英版本。

WelcomeUiSmokeTest 输出深色、浅色、空列表及窄窗口截图到 ui/target/review/welcome-*.png，
检查新建和连接动作以及窄窗口布局；与工作区 dock 回归一同验证。
