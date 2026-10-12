# Swing / IDEA Islands 界面重构验收

本地分支：`feature/idea-ui-refactor`，基于 `origin/develop` 的 `2b06768`。
独立工作目录：`JLShell-ui-refactor`。原工作目录、原分支及已安装应用均未切换。

## 实现边界

- 主窗口、菜单、工具窗口栏、项目切换、连接树、欢迎页和会话标签使用 Swing。
- JediTerm 原生嵌入 Swing，SSH 和本地终端均不再经过 `SwingNode`；JavaFX 对照入口仍保留。
- Swing 使用 FlatLaf 3.5.4 和统一的 IDEA New UI 语义配色、控件尺寸及 HiDPI 布局。
- 文件页、原有设置/账号/连接表单和插件内容仍使用 JavaFX，通过 `JFXPanel` 兼容承载。插件工具栏和服务器状态条也保留原视图及事件处理。
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

## 窗口结构与终端停靠

顶部使用一体化主工具栏，放置项目选择、连接动作和主菜单入口；macOS 保留原生窗口按钮，
Windows/Linux 使用 FlatLaf 的窗口装饰和按钮占位。主菜单保留原业务动作及动态启用状态。
连接面板的搜索按需展开，新建文件夹、编辑、删除、刷新和项目管理可从标题栏更多菜单进入，
右键操作、拖放移动和快捷键继续保留。

终端默认停靠底部，可以通过左下终端按钮、工具窗口标题按钮或工作区提示展开回主工作区。
拖动横向分隔调整终端高度，切换会话时保留高度。停靠只移动同一个 JediTerm 组件，
不会创建新的 shell、替换连接器或重新激活插件。SSH 的文件和插件页面仍通过原 Tab 模型
在上方工作区显示，底部终端可继续使用；本地会话上方提供展开入口。
会话切换、排序、插件页选择及关闭都保留原有生命周期。

## 插件兼容

插件仍返回同一个 JavaFX `Node`，不要求重新编写 Swing 界面。兼容容器只给外层包装节点
应用宿主主题，保留插件自身样式表、inline style 和按钮事件；事件仍在 FX 线程执行。
关闭容器时解除主题监听并释放嵌入 Scene；插件激活/停用和会话资源释放仍走原有路径。

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
完整测试与打包通过：138 个测试，0 失败、0 错误、0 跳过。新增停靠回归检查同一终端和缓冲区在展开、停靠、会话排序及插件页切换后保持完整。
预览图片保存在 `ui/target/review/`，包括旧版插件界面。

合并前仍需在 Windows/Linux 进行实际窗口、系统缩放和中文输入法验收，并以真实 SSH/SFTP
会话检查断线重连、插件开页及关闭释放。当前没有真实远端连接，不将这些列为已验证项。
在验收完成前，不合并到 `develop`，不切换原工作目录。
