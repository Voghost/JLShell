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

macOS 对照 `MacNativeActionMenu` 的动作模型/原生展示分离，复用原 MenuBar 动作。
OpenJDK Aqua 在 JavaFX Glass 嵌入模式下明确禁用屏幕菜单，直接设置 JFrame 菜单会额外
占据窗口一行。现通过已有 JavaFX 原生系统菜单后端发布全局菜单，Swing 窗口内不增加
菜单行；快捷键在菜单打开时从原注册表刷新。当前 fat JAR/classpath 启动验证成功；
适配器使用 JavaFX 内部后端反射，不将尚未验证的 module-path 启动列为支持范围。
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
实际 macOS 全局菜单点击尚需人工核对；系统菜单模型和启动路径已通过测试。

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
