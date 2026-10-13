# JavaFX 渲染清单与 Swing 迁移顺序

盘点日期：2026-10-13

本文记录默认 `jlshell.ui.renderer=swing` 路径下仍由 JavaFX 绘制的界面，供后续逐步迁移到 Swing。需要区分“JavaFX 负责显示像素”和“JavaFX 只负责业务模型、事件或线程调度”：FX Application Thread 上出现的日志本身不能证明对应组件正在由 JavaFX 渲染。

## 当前窗口边界

- 正常启动的主窗口是 `SwingWorkbench` 创建的 Swing `JFrame`。主工具栏、连接树、欢迎页、会话外层标签、分屏、底部工具窗口外壳和原生 Swing 终端均由 Swing 绘制。
- SSH/本地终端在 Swing 工作台路径中直接挂载 Swing 终端组件，不经过 `SwingNode`。JavaFX-only 回退路径仍保留 `SwingNode`。
- `MainWindow`、`SessionWorkspaceTab`、`TerminalWorkspaceView` 等仍是 JavaFX 业务层。它们管理会话生命周期、内层标签/插件契约、工具栏和状态内容，并把相应节点交给 Swing 宿主。
- `SwingFxPanel` 继承 `JFXPanel`，将 JavaFX `Node` 放入 JavaFX `Scene` 后嵌入 Swing。包含这种面板的窗口虽然外框和部分操作区是 Swing，面板内部仍是 JavaFX 实际绘制。
- JavaFX 菜单对象也继续作为动作模型使用；常规菜单经 `SwingMenuBridge` 转成 Swing 行绘制，macOS 全局菜单由系统菜单后端绘制。这类情况属于模型/事件仍依赖 FX，不等于主窗口菜单由 JavaFX Scene 绘制。

## 仍由 JavaFX 绘制的界面

### 会话中的右侧文件和插件页面

`SwingSessionView` 将原 `SessionWorkspaceTab` 内层 `TabPane` 放进 `SwingFxPanel`。其文件页 `SftpBrowserPane`、插件页 `PluginsTabView` 和兼容插件动态创建的页面仍是 JavaFX 控件/节点。这是主窗口中最主要的一块 JavaFX 内容。

相关代码：

- `ui/src/main/java/com/jlshell/ui/swing/SwingSessionView.java`：`compatibility.setView(model)`。
- `ui/src/main/java/com/jlshell/ui/view/SessionWorkspaceTab.java`：创建 Terminal / Files / Plugins 内层标签。
- `ui/src/main/java/com/jlshell/ui/view/SftpBrowserPane.java`：SFTP 文件树、列表、工具栏及操作弹窗。
- `ui/src/main/java/com/jlshell/ui/view/PluginsTabView.java`：会话插件列表及插件页面。

### 终端周边工具栏和监控状态

终端主体由 Swing 绘制；`TerminalWorkspaceView` 提供的终端工具栏节点和服务器状态/监控节点则分别装进 `SwingFxPanel`。监控采样、连接状态、部分详情提示、进程与磁盘信息等内容仍使用 FX 节点。`SwingSessionView` 负责把它们放在 Swing 布局中的对应区域。

相关代码：

- `ui/src/main/java/com/jlshell/ui/swing/SwingSessionView.java`：`installChrome` 中的工具栏和状态 `SwingFxPanel`。
- `ui/src/main/java/com/jlshell/ui/view/TerminalWorkspaceView.java`：状态条、监控内容、终端外观设置及插件操作节点。

### 设置窗口页面

设置窗口的窗口框架、搜索、左侧导航树、面包屑、页面分节标签和底部按钮是 Swing。具体设置表单仍来自 `PreferencesDialog` 的 JavaFX `TabPane`，当前选中的页面通过 `SwingFxPanel` 显示。因此通用、终端、账户、插件、快捷键、API、导入等表单控件及设置页内嵌套标签仍由 FX 绘制。

设置内部打开的 JavaFX `Alert`、颜色方案编辑器等嵌套窗口也仍可能显示原生 JavaFX 窗口。

相关代码：

- `ui/src/main/java/com/jlshell/ui/swing/SwingPreferencesDialog.java`：Swing 外壳和 `SwingFxPanel content`。
- `ui/src/main/java/com/jlshell/ui/dialog/PreferencesDialog.java`：设置页面、控件及保存模型。
- `ui/src/main/java/com/jlshell/ui/dialog/ColorSchemeEditDialog.java`：颜色方案编辑弹窗。

### Swing 外壳中的 FX 表单内容

连接编辑、项目管理和“关于”窗口使用 Swing 对话框外壳，但其主体仍是原 JavaFX `DialogPane` 内容，由 `SwingDialogHost` 搬入 `JFXPanel` 显示。迁移这些表单时，不能只替换外层对话框。

当前主要入口：

- `ui/src/main/java/com/jlshell/ui/swing/SwingDialogHost.java`。
- `ui/src/main/java/com/jlshell/ui/view/ConnectionDialog.java`。
- `ui/src/main/java/com/jlshell/ui/dialog/ProjectManagerDialog.java`。
- `ui/src/main/java/com/jlshell/ui/dialog/AboutDialog.java`。

### 仍直接显示的 JavaFX 弹窗和临时提示

以下流程仍直接创建 JavaFX `Alert`、`Dialog` 或 `TextInputDialog`，不经过 Swing 外壳。它们出现时会显示 JavaFX 窗口/弹窗：

- SSH 主机密钥首次信任和密钥变化确认：`ui/src/main/java/com/jlshell/ui/service/HostKeyConfirmationService.java`。
- 更新检查、下载进度、更新结果、重启提示及主窗口中的错误/确认/文本输入：`ui/src/main/java/com/jlshell/ui/view/MainWindow.java`。
- SFTP 文件/目录重命名、删除、新建等确认和输入：`ui/src/main/java/com/jlshell/ui/view/SftpBrowserPane.java`。
- 账户、保险库、项目管理和关于/设置流程中由原 JavaFX 代码直接打开的嵌套提示：`ui/src/main/java/com/jlshell/ui/dialog/AccountDialog.java`、`VaultManagerDialog.java`、`ProjectManagerDialog.java`、`PreferencesDialog.java`。

这些弹窗数量较多，可以后续通过统一 Swing 对话框服务迁移；当前 `SwingDialogHost` 只能承载现有 FX 节点，若目标是彻底去除 FX 渲染，还需将表单内容本身改成 Swing。

## JavaFX 仍参与但通常不直接绘制主窗口的部分

- `MainWindow` 和 `SessionWorkspaceTab` 仍负责会话初始化、生命周期、连接状态、JavaFX 标签模型和业务动作。
- JavaFX `TabPane` 是内层页面的数据/选择模型，并会被兼容面板渲染；终端工具页切换会同步到 Swing 导航栏。
- 插件当前可返回 JavaFX `Node`。这属于插件 SDK 的 UI 契约；只替换宿主页面还不能移除插件内容的 FX 绘制，需保留 FX 兼容桥或设计版本化的 Swing 插件视图接口。
- `ui/src/main/java/com/jlshell/ui/view/TerminalWorkspaceView.java` 的 `SwingNode` 嵌入是 JavaFX-only 回退路径；默认 Swing 工作台会使用 `NativeTerminalHost`，直接将终端挂到 Swing。
- 原 JavaFX 主窗口及其节点仍留在 `MainWindow` 作为回退实现。切换到 JavaFX renderer 时会恢复该路径。
- FX Application Thread 仍用于调用现有服务/模型、主题属性和插件回调。减少 FX 渲染并不自动意味着可以移除 JavaFX runtime 或 FX 线程。

## 建议迁移顺序

1. **独立弹窗与提示**：先处理主机密钥确认、更新/错误提示、SFTP 操作确认和文本输入。它们边界清晰，也能较早减少用户遇到的 JavaFX 原生窗口。
2. **终端工具栏和监控状态区**：将按钮、状态栏和监控信息改成 Swing 视图，由现有业务服务提供不可变数据/动作回调；保留采样服务，不搬动业务逻辑。
3. **SFTP 文件页**：重写文件树、路径栏、列表和操作弹窗为 Swing。SFTP 服务接口与文件操作语义保持不变。
4. **插件宿主页**：先把市场/已安装列表、详情和页面容器改为 Swing。同步确定外部插件 JavaFX `Node` 的兼容策略；可以在过渡期通过 `JFXPanel` 保持旧插件工作。
5. **设置页及复合表单**：按页面迁移 JavaFX 控件，复用现有设置服务、校验和保存流程；逐步替换连接、项目、账户、保险库、关于等表单内容。
6. **收尾 JavaFX 运行时依赖**：前述 UI 与插件兼容路径稳定后，再评估移除 `SwingFxPanel`、JavaFX-only 主窗口回退、FX Scene/CSS 和相关运行时。FX 线程仍服务业务模型的部分也需要单独迁移或保留，不能只凭渲染替换结论直接删除。

建议每个迁移项单独验收明暗主题、窗口缩放、键盘/输入法、弹窗焦点、插件/会话生命周期及无头测试；不要把“窗口外壳已是 Swing”记作“页面已无 JavaFX”。
