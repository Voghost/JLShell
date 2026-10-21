# 插件侧栏与 Swing 页面

默认 Swing 工作台中，右侧列表使用 SwingPluginList：搜索、两行摘要、完整说明 Tooltip、独立固定按钮和打开动作。固定会持久化，并立即更新右侧工具栏图标。列表不创建 JFXPanel。

会话插件与 Program 会话贡献使用同一页宿主。`openTab(Node)` 返回的页面完整保留 JavaFX 节点树，按需通过 JFXPanel 嵌入，保留自定义 ListCell、图标、CSS 和 RichTextFX 等第三方控件；不再尝试将第三方页面自动转换为 Swing。`SwingFormRenderer` 仅用于宿主已知的内部表单，不是通用插件 UI 转换器。

Program 插件的全局设置页即使嵌套在宿主 Swing 设置界面中，也使用独立 JavaFX 子树边界，避免被表单映射器处理。项目扩展仍在项目对话框的完整 JavaFX 页面内运行。

兼容修复针对 Beta 0.1.68 / 0.1.69 中自动表单转换造成的内容丢失：通用 Region 分支会生成空面板，ListView 映射不保留自定义 cellFactory，图形和 FX 图标字体也无法完整转成 AWT。此问题需要更新宿主；旧插件无需为了恢复原 JavaFX 页面而重新编译。第三方库依赖及资源是否完整，仍由插件自己的打包负责。

## 原生页面接口

Maven Central 已发布 `net.oomn.jlshell:plugin-api:1.6.0` 和 `net.oomn.jlshell:program-api:1.6.0`。其中 `PluginContext` 提供默认方法 `supportsSwingViews()` 和 `openSwingTab(String, Supplier<JComponent>)`。Swing 工作台 Beta 0.1.68 起实现该能力；使用新版接口前应检测宿主能力，避免在尚未支持的宿主上直接调用。SDK 中的 JavaFX 属性和旧页面接口仍保留，当前 SDK 尚未完全解除 JavaFX 依赖。

| 入口 | Swing 支持情况 |
| --- | --- |
| 会话插件 `activate(PluginContext)` | 支持 `openSwingTab` |
| Program 插件 `sessionIntegration()` 会话贡献 | 获得同一个 `PluginContext`，支持 `openSwingTab` |
| Program 插件全局 `settingsView(...)` | 仍返回 JavaFX `Node` |
| 项目 `createView(...)` / `createManagementView(...)` 扩展 | 仍返回 JavaFX `Node` |
| `program-api` 的 RPC、会话、账号服务 | 无界面接口，不依赖 JavaFX，可以在 Swing 插件后台调用 |

```java
if (context.supportsSwingViews()) {
    context.openSwingTab("My plugin", () -> {
        // 工厂由宿主在 EDT 调用。不要在这里执行 SSH、磁盘扫描等阻塞工作。
        return new MySwingPanel(context);
    });
} else {
    // 在 JavaFX Application Thread 创建旧页面；只用于兼容旧宿主。
    javafx.application.Platform.runLater(() -> context.openTab("My plugin", createFxView(context)));
}
```

插件面板可以实现 AutoCloseable。关闭标签、移除页面、重连或关闭会话时，宿主在 EDT 释放组件；FX 模型的监听器在 FX 线程清理。原生命周期中的 deactivate、能力注销和会话关闭逻辑继续保留。宿主不在 EDT 等待 FX，反向也不等待。

渐进迁移时按页面选择技术栈：旧页面继续调用 `openTab`；迁移完成的页面调用 `openSwingTab`。JavaFX 控件仅在 FX 线程操作，Swing 组件仅在 EDT 操作，网络与磁盘工作放后台。监听 `themeNameProperty()`、`localeProperty()` 的回调需要切回 EDT 后更新 Swing，并在页面关闭时注销监听。插件应将宿主 SDK 声明为 `provided`，fat JAR 不打入 SDK、JavaFX 或宿主内部模块；使用 RichTextFX、Flowless、图标库时保留该插件自身的依赖、CSS、字体和服务注册资源。

系统监控插件提供 SwingMonitorDashboard，支持本机/远程切换、CPU/内存/网络/磁盘趋势、窄栏单列与宽栏双列。采样在后台执行；定时器随显隐启停；过期请求结果被丢弃；关闭释放后台线程。

## 构建与预览

`mvn -Pwith-plugins package` 将宿主、SDK 和四个仓库插件放入同一 reactor 构建；不会向 ~/.m2 安装同版本 SDK。独立 plugins/pom.xml 仍使用发布 SDK。系统监控插件通过能力探测兼容旧 SDK 和宿主，因此可独立构建；直接调用新接口的第三方插件需使用新版 SDK。

ui/run-preview.sh 默认使用该 profile，并将本轮 sysmon fat JAR 复制到每次启动独有的 ui/target/native-preview-plugins.* 目录，作为预览专用优先目录。插件 ID 去重，避免已安装旧 sysmon 重复出现；不替换用户已安装或正在运行的 JAR。这些副本用于保护运行中插件的延迟类加载，关闭所有预览进程后可清理。

## 验证

- RuntimePluginDirectoriesTest：预览专用目录优先、正式路径不受影响、Program 插件不混入会话目录。
- PluginManagerPerSessionTest：会话隔离、生命周期和能力注册不回退。
- SshWorkspaceUiSmokeTest：真实宿主固定/打开、原生工厂、旧 JavaFX 页面与动作、资源只释放一次、终端监控仍采样。
- SwingPluginPageTest：保留自定义列表单元格、图形、第三方编辑器 Region 及 inline style；主题切换保持原节点树，关闭时释放 FX Scene；Swing 工厂与关闭回调仍在 EDT 执行。
- PluginProgressUiSmokeTest：深浅主题窄列表布局与搜索、更新进度/后台运行/快速关闭，以及从实际 sysmon fat JAR 创建原生监控页。

系统监控插件在旧宿主上自动走原 FX 页面，新版宿主上使用 Swing 工厂。

## 插件图标

市场列表优先读取网站返回的 `iconUrl`。插件卡片和已安装列表优先读取本地插件 JAR 图标，缺失、格式不支持或读取失败时显示名称首字占位。固定到侧栏的图标使用主题前景色，不保留品牌色；卡片和市场使用原图颜色。图标异步加载并缓存，失败不会阻塞页面。

本地插件可在 `META-INF/MANIFEST.MF` 声明 `JLShell-Plugin-Icon: branding/logo.png`，路径相对 JAR 根目录。未声明时依次检查 `META-INF/jlshell/icon.png`、`icons/plugin.png`、`icon.png`。支持 PNG、JPEG、GIF 等 ImageIO 图片格式，建议提供带透明背景的 PNG，单张不超过 2 MB、2048×2048。此约定不需要新增 SDK 方法；未携带这些资源的旧插件继续显示名称首字占位。
