# Swing 插件页面

## 当前源码契约

`analysis/remove-javafx` 分支将 Plugin SDK 的页面、设置与项目扩展接口统一为 Swing 工厂，移除了 SDK、会话加载器和 Program 加载器的 OpenJFX 依赖。此改动尚未发布到 Maven Central。

- `PluginView.createView(PluginContext)` 返回 `Supplier<JComponent>`。
- `PluginContext.openTab(String, Supplier<JComponent>)` 委托给 `openSwingTab`。工厂在 EDT 执行，插件激活时可以只登记工厂。
- `JlShellProgramPlugin.settingsView(ProgramPluginContext)` 返回 `Supplier<JComponent>`，没有设置页时返回 `null`。
- `ProjectCreationContribution.createView` 和 `createManagementView` 返回 `Supplier<JComponent>`。
- `themeNameProperty()`、`localeProperty()`、项目名称与描述返回 SDK 自有的只读 `ObservableValue<T>`。通过 `get()` 读取，通过 `subscribe(...)` 订阅。订阅包含初始快照，返回的 `Registration` 用于注销。
- `program-api` 的 RPC、账号与会话接口继续独立于界面技术栈。

这些接口变更涉及二进制兼容性。使用旧页面返回类型或 JavaFX 属性的插件需要迁移并重新编译。仅替换插件的 SDK JAR 无法完成迁移。加载器会记录无法加载的外部插件并继续扫描其他 JAR。

## 页面工厂

```java
@Override
public Supplier<JComponent> createView(PluginContext context) {
    return () -> {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel("Session tools"), BorderLayout.NORTH);
        JButton action = new JButton("Run uptime");
        action.addActionListener(event -> context.sshSession().ifPresent(ssh ->
            ssh.commandExecutor().execute("uptime").whenComplete((output, error) ->
                SwingUtilities.invokeLater(() -> {
                    action.setText(error == null ? output.stdout() : error.getMessage());
                }))));
        panel.add(action);
        return panel;
    };
}
```

网络、磁盘、采样和数据库操作放到后台。工厂与 Swing 控件操作放到 EDT。页面实现 `AutoCloseable` 时，宿主在移除标签或关闭会话时释放资源，页面自身的关闭方法应允许重复调用。

## 状态订阅

```java
Registration subscription = context.themeNameProperty().subscribe(theme ->
    SwingUtilities.invokeLater(() -> applyTheme(theme)));
// 页面关闭时释放订阅，同时在已关闭页面的异步 UI 回调中检查关闭状态。
subscription.close();
```

通知可能来自发布者线程，UI 订阅者需自行调度到 EDT。读取与订阅界面字体和终端字体继续使用 `context.fonts()`，该接口只提供读取与监听。

## 构建与迁移

宿主 SDK 使用 `provided` 作用域。插件 fat JAR 保留自身业务依赖及资源，排除 SDK、OpenJFX、SLF4J 和宿主内部模块。仓库内的脚本片段、Session Demo、Program Demo 和系统监控插件均已使用 Swing。

使用 JDK 21，在桌面仓库执行 `mvn -Pwith-plugins package` 可同时验证 SDK 与四个插件。宿主其余旧表单和启动模型仍在迁移，移除 SDK 的依赖并不代表整个桌面运行时已完成 OpenJFX 清理。
