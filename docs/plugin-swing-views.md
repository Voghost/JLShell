# 插件侧栏与 Swing 页面

默认 Swing 工作台中，右侧列表使用 SwingPluginList：搜索、两行摘要、完整说明 Tooltip、独立固定按钮和打开动作。固定会持久化，并立即更新右侧工具栏图标。列表不创建 JFXPanel。

会话插件与 Program 会话贡献使用同一页宿主。普通 JavaFX 表单 Node 仍可作为业务模型，通过 SwingFormRenderer 映射为真实 Swing 控件，保留输入绑定、按钮动作和异步更新。复杂第三方自绘 Node 尚无法通用转换，按需创建兼容桥；不会为全部会话预先创建 FX 表面。

## 原生页面接口

PluginContext 新增默认方法 `supportsSwingViews()` 和 `openSwingTab(String, Supplier<JComponent>)`。旧插件二进制在新宿主上继续工作。新版原生插件需运行在包含该 SDK 接口的新宿主上；本轮未发布 SDK 或插件。

```java
context.openSwingTab("My plugin", () -> {
    // 工厂由宿主在 EDT 调用。不要在这里执行 SSH、磁盘扫描等阻塞工作。
    return new MySwingPanel(context);
});
```

插件面板可以实现 AutoCloseable。关闭标签、移除页面、重连或关闭会话时，宿主在 EDT 释放组件；FX 模型的监听器在 FX 线程清理。原生命周期中的 deactivate、能力注销和会话关闭逻辑继续保留。宿主不在 EDT 等待 FX，反向也不等待。

系统监控插件提供 SwingMonitorDashboard，支持本机/远程切换、CPU/内存/网络/磁盘趋势、窄栏单列与宽栏双列。采样在后台执行；定时器随显隐启停；过期请求结果被丢弃；关闭释放后台线程。

## 构建与预览

`mvn -Pwith-plugins package` 将宿主、SDK 和四个仓库插件放入同一 reactor 构建；不会向 ~/.m2 安装同版本 SDK。独立 plugins/pom.xml 仍使用发布 SDK。系统监控插件通过能力探测兼容旧 SDK 和宿主，因此可独立构建；直接调用新接口的第三方插件需使用新版 SDK。

ui/run-preview.sh 默认使用该 profile，并将本轮 sysmon fat JAR 复制到每次启动独有的 ui/target/native-preview-plugins.* 目录，作为预览专用优先目录。插件 ID 去重，避免已安装旧 sysmon 重复出现；不替换用户已安装或正在运行的 JAR。这些副本用于保护运行中插件的延迟类加载，关闭所有预览进程后可清理。

## 验证

- RuntimePluginDirectoriesTest：预览专用目录优先、正式路径不受影响、Program 插件不混入会话目录。
- PluginManagerPerSessionTest：会话隔离、生命周期和能力注册不回退。
- SshWorkspaceUiSmokeTest：真实宿主固定/打开、原生工厂、旧表单转换与动作、无 FX Scene、资源只释放一次、终端监控仍采样。
- PluginProgressUiSmokeTest：深浅主题窄列表布局与搜索、更新进度/后台运行/快速关闭，以及从实际 sysmon fat JAR 创建原生监控页。

系统监控插件在旧宿主上自动走原 FX 页面，新版宿主上使用 Swing 工厂。
