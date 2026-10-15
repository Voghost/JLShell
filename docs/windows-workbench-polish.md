# Windows 菜单文字与工作区交互修复

日期：2026-10-15。针对 0.1.68 Beta 的 Windows 反馈。

## 与 IDEA 的差异

1. 我们的菜单、组件下拉框和 tooltip 原来使用整窗透明的 JWindow，圆角和阴影都在同一透明表面软件绘制。FlatLaf 3.5.4 的 FlatPopupFactory 明确说明，Java 在透明窗口中的子像素文字渲染有问题，因此其标准弹窗避免用透明内容窗口画阴影。该路径同时影响英文与中文，不是只换中文字库就能解决。
2. SwingTheme 原先用字体 family 字符串重新构造物理 FontUIResource，可能丢失原有复合字体的字符回退。现在系统默认保留 FlatLaf 原字体，自定义字体使用 FontUtils.getCompositeFont，随后包装 UIResource。
3. 当前 IDEA 的 LafManagerImpl 有 Inter UI 字体及 getFontWithFallback 路径；具体用户 IDEA 使用哪个字体取决于版本和字体设置，截图不能确定字体名称。JLShell 仍使用系统 UI 字体（Windows FlatLaf 默认通常为 Segoe UI），因此字形和字宽未承诺与 IDEA 完全相同。
4. IDEA 使用 JetBrains Runtime，其官方介绍包含字体、HiDPI 与窗口系统改进；当前分发使用 Liberica/OpenJDK。恢复正确文字表面和回退后，两个运行时仍可能有细节差异。
5. 终端右键菜单原来错误地使用普通列表样式，现统一改为 CONTEXT，使用同样的图标列和行间距。

源码依据：

- https://github.com/JFormDesigner/FlatLaf/blob/3.5.4/flatlaf-core/src/main/java/com/formdev/flatlaf/ui/FlatPopupFactory.java （sub-pixel text / translucent windows 注释）
- https://github.com/JFormDesigner/FlatLaf/blob/3.5.4/flatlaf-core/src/main/java/com/formdev/flatlaf/util/FontUtils.java
- https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/ide/ui/laf/LafManagerImpl.kt
- https://github.com/JetBrains/JetBrainsRuntime

## 修复

- Windows 菜单、组件弹窗、tooltip 使用不透明内容窗口，保留系统文字抗锯齿条件；Windows 11 原生库可用时由 DWM 提供圆角和边框。Windows 无原生圆角支持时采用不透明矩形回退。macOS/Linux 继续使用现有透明圆角路径。
- Windows 不再给内容施加软件圆角的复杂 clip；原生 DWM 负责窗口边界，避免内容文字退化或重复边框。
- 使用复合字体回退；不强制覆盖系统 ClearType 开关，不把终端字体套用于菜单。
- 分隔条悬停/拖动显式维护左右或上下 resize cursor，拖动出边界时保持，结束后恢复；主题更新后重新安装。覆盖左右工具栏、工作区分屏、SFTP 和插件表单分隔条。
- 连接树增加整行、轻量圆角 hover 背景，鼠标退出清除，不改变选中项、不触发连接；深浅主题使用独立颜色。
- 定位按钮统一解析 SessionWorkspaceTab.getConnectionProfile 与本地标签 userData，按连接 ID 查找；等待跨 FX/EDT 的实际目标返回后选择，清除挡住目标的搜索过滤并展开父目录。

## 验证与限制

- 完整 app reactor 测试覆盖 SSH 标签无 userData 时的连接解析、本地标签和非会话标签。
- 实际 Swing 窗口回归覆盖同名同地址不同 ID 的目标定位、折叠父目录展开、过滤清除、hover 像素与选中项隔离、分隔条拖动及主题更新。
- Preview Windows job 执行相同 UI 回归，并上传 Windows 菜单实际截图与测试报告；Windows 专属断言检查不透明窗口和可用中文字体的回退。
- 本机为 macOS，Windows 实际渲染需以 Windows runner 和用户显示器上的结果为准；远程 runner 的字体安装、ClearType 与 DPI 不一定与用户机器一致。
