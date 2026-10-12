# Swing / IDEA New UI 界面重构验收

本地分支：`feature/idea-ui-refactor`，基于 `origin/develop` 的 `2b06768`。
独立工作目录：`JLShell-ui-refactor`。原工作目录、原分支及已安装应用均未切换。

## 实现边界

- 主窗口、菜单、工具窗口栏、项目切换、连接树、欢迎页和会话标签使用 Swing。
- JediTerm 原生嵌入 Swing，SSH 和本地终端均不再经过 `SwingNode`；JavaFX 对照入口仍保留。
- Swing 使用 FlatLaf 3.5.4 和统一的 IDEA New UI 语义配色、控件尺寸及 HiDPI 布局。
- 文件页、原有设置/账号/连接表单和插件内容仍使用 JavaFX，通过 `JFXPanel` 兼容承载。插件工具栏和服务器状态条也保留原视图及事件处理。
- 业务动作继续调用原控制器。未修改 SSH/SFTP 服务、数据库、账户、更新协议、插件加载器、插件 SDK 或 RPC/会话注册机制。

参考了本地 IDEA 的 `JBUI`、`expUI_dark/light.theme.json`、`JBTerminalWidget`、
`JBTerminalPanel` 和 `ShellTerminalWidget` 的设计；未复制其平台实现或引入整个 IntelliJ 运行时。
FlatLaf 的 Apache 2.0 许可证随 UI 资源保留在 `third-party/`。

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
完整测试与打包通过：137 个测试，0 失败、0 错误。
预览图片保存在 `ui/target/review/`，包括旧版插件界面。

合并前仍需在 Windows/Linux 进行实际窗口、系统缩放和中文输入法验收，并以真实 SSH/SFTP
会话检查断线重连、插件开页及关闭释放。当前没有真实远端连接，不将这些列为已验证项。
在验收完成前，不合并到 `develop`，不切换原工作目录。
