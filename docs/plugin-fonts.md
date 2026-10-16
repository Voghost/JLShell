# 插件读取并跟随宿主字体

本接口只提供读取和监听，不允许插件修改宿主全局字体。Session 的 `PluginContext`、
Program 的 `ProgramPluginContext`，以及 Program 贡献会话时收到的 `PluginContext`
统一通过 `context.fonts()` 使用。

| API | 含义 |
| --- | --- |
| `fonts().available()` | 当前上下文是否提供字体服务 |
| `fonts().terminalFont()` | 当前偏好设置中的终端字体、字号 |
| `fonts().uiFont()` | 当前偏好设置中的界面字体、字号 |
| `fonts().onTerminalFontChanged(listener)` | 跟随终端字体变化，返回可关闭的订阅 |
| `fonts().onUiFontChanged(listener)` | 跟随界面字体变化，返回可关闭的订阅 |

`FontSpec` 只包含 `family()`、`size()`，不依赖 JavaFX、Swing、core 或具体字体实现。
字号采用宿主偏好设置中的逻辑值，不要再次乘屏幕缩放比例。
`systemDefault()` 为 true（family 为空）表示使用 UI 技术栈的系统字体及其字体回退，
不是一个名叫空字符串的物理字体。终端偏好的字体名可能是字体别名；临时终端缩放、
插件自己的字体覆盖、行距和连字开关不属于本接口的字体快照。

## 读取和监听

```java
PluginFonts fonts = context.fonts();
if (fonts.available()) {
    FontSpec terminal = fonts.terminalFont();
    FontSpec ui = fonts.uiFont();
    // 读取快照不会访问磁盘、枚举字体或等待 UI 线程。
}
```

订阅会**异步先发送最新值**，再发送后续变化；因此通常直接订阅即可，不必先读一次
再给控件设置初始值。相同字体/字号不重复通知。订阅尚未开始交付时连续发生多次修改，
首次回调会收到最新配置，已过时的排队事件不会把控件字体退回旧值。

回调线程不是 API 保证的一部分。Swing 控件更新使用 `SwingUtilities.invokeLater`；
JavaFX 控件更新使用 `Platform.runLater`。回调只做轻量工作，不同步等待 UI、磁盘或网络。

JavaFX 正文或编辑器示例：

```java
PluginFonts fonts = context.fonts();
Registration subscription = Registration.noop();
if (fonts.available()) {
    subscription = fonts.onTerminalFontChanged(spec -> Platform.runLater(() -> {
        if (viewClosed) return;
        String family = spec.systemDefault() ? Font.getDefault().getFamily() : spec.family();
        editor.setFont(Font.font(family, spec.size()));
    }));
}
// 页面移除 / deactivate / 会话结束时：
viewClosed = true;
subscription.close();
```

Swing 界面字体示例（`label` 为正文标签）：

```java
Registration subscription = Registration.noop();
if (context.fonts().available()) {
    subscription = context.fonts().onUiFontChanged(spec -> SwingUtilities.invokeLater(() -> {
        if (viewClosed) return;
        Font base = UIManager.getFont("Label.font");
        Font selected = spec.systemDefault() ? base
                : new Font(spec.family(), base.getStyle(), base.getSize());
        label.setFont(selected.deriveFont((float) spec.size()));
        label.revalidate();
        label.repaint();
    }));
}
```

示例中的 `viewClosed` 应是插件自己的线程安全生命周期标记。插件停用/会话关闭时，
宿主会自动关闭对应上下文的全部订阅；单个页面提前移除时仍应主动关闭句柄。
插件自己已经排入 UI 队列的工作由插件取消或通过生命周期标记跳过。
不要对整个节点树无差别覆盖字体：保留图标字体、标题粗细和代码编辑器的独立设置。

## 兼容与发布

新增能力使用公共默认接口，旧上下文实现不需要补方法；在新版 SDK 上运行时，未接入
宿主字体服务的上下文会返回 `available() == false`。不可用时读取快照会抛出
`UnsupportedOperationException`，不会伪造一套宿主字体配置。

这不等于旧宿主中缺少新 API 类也能直接调用：正式采用本接口的插件仍需声明包含该
能力的最低宿主版本。此改动尚未发布新的公共 SDK；Maven Central 的 1.6.0 不含本接口。
后续 SDK 发布沿用独立的发布流程，不覆盖既有版本。

## 验证

- 完整 `mvn test` 通过，覆盖 API 默认兼容行为、Session 关闭和 Program 停用/重新启用。
- `HostPluginFontsTest` 的 3 项真实 JavaFX 回归通过：持久设置跟随、连续变更顺序、无重复通知、回调异常隔离、关闭前排队事件取消，以及快照读取无存储访问。
- `mvn -pl plugin-api,program-api -am package` 通过，SDK JAR 内包含新增字体契约。
