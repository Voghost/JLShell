# 弹窗 Swing 适配清单

盘点与更新：2026-10-14。以下描述默认 `jlshell.ui.renderer=swing` 路径；显式选择 `javafx` 时仍保留旧界面回退。

## 本次实现

- 所有内置的阻塞式 `Alert` 统一经 `SwingAlerts` 显示。覆盖原有 31 个调用点，采用 Swing `JDialog`、主题图标、可换行正文和统一底部按钮栏，不创建 `JFXPanel`。
- 主机密钥提供「仅本次信任」「信任并保存」「取消」。仅本次信任只接受当前 SSH 握手，不写入 known_hosts；保存信任才持久化；取消、关闭和超时均拒绝。密钥变化时继续显示警告。非默认端口显示实际端口。
- 新建/编辑/复制连接及粘贴分享连接的输入窗口使用原生 Swing 文本框、密码框、下拉框、标签页和滚动容器。
- 凭据库使用左侧列表、右侧编辑布局。认证方式和加密方式有完整的本地化名称；仅显示所选认证方式所需的字段，字段标签同步隐藏。主密码输入也使用 Swing。
- 统一 ESC 关闭、默认按钮、父窗口禁用/恢复、嵌套弹窗归属，以及深浅主题。
- 消息和主机信任弹窗按正文换行后的实际高度自适应；超出屏幕可用高度时只滚动正文，底部按钮始终可见。

这些表单仍复用 JavaFX 控件作为数据、校验和动作模型，由 `SwingFormRenderer` 将模型映射到原生 Swing 控件。它们已不由 JavaFX 绘制，但暂时仍依赖 JavaFX runtime 和 FX Application Thread，不能据此删除 JavaFX 依赖。

## 报错、警告与确认入口

| 位置 | 涉及场景 | 默认渲染方式 |
| --- | --- | --- |
| `MainWindow.showError` | 连接列表加载、保存/编辑/复制/删除、分享加载、SSH/本地终端打开、会话关闭失败 | Swing |
| `MainWindow.showUpdateError` | 更新检查、下载/暂存失败 | Swing |
| `MainWindow` 其他 Alert | 切换项目、删除连接/文件夹、文件夹深度限制、分享复制完成、更新结果/重启/下载确认 | Swing |
| `AccountDialog.showError` | 登录、注册、验证码、账号请求失败与必填项错误 | Swing |
| `PreferencesDialog.themedAlert` | 设置中的账号、验证、API、导入等消息/失败提示 | Swing |
| `PreferencesDialog` 其他 Alert | 重启提示、导入确认、插件动作确认、启用/停用及相关反馈 | Swing |
| `ProjectManagerDialog.showError` | 项目校验、项目包导入/导出失败 | Swing |
| `ProjectManagerDialog` 其他 Alert | 删除项目/连接确认、项目包分享码结果 | Swing |
| `VaultManagerDialog` | 主密码解锁失败、凭据保存失败、未解锁警告、删除确认 | Swing |
| `SftpBrowserPane` 的 Alert | 本地/远程文件及目录删除确认、目录已存在警告 | Swing |
| `HostKeyConfirmationService` | 未知主机密钥、密钥变化与信任决策 | Swing |

SFTP 传输、刷新、创建目录、重命名等部分失败原本显示在文件页状态文本中，不是弹窗。默认 Swing 工作台已改用 SwingSftpBrowser，状态、目录/重命名输入和确认均由 Swing 绘制；旧 JavaFX 文件页仅用于 javafx renderer 回退。

## 其他弹窗/表单迁移状态

| 位置 | 剩余内容 | 说明 |
| --- | --- | --- |
| `MainWindow.promptFolderName` | 已迁移：新建/重命名连接分组 | 原生 Swing 输入、默认按钮与取消，按内容自适应高度 |
| `MainWindow.showConnectionShareDialog` | 已迁移：生成分享文本、分享码与预览表单 | 原生 Swing，保留动态加密预览与复制校验 |
| 启动阶段 | 已迁移：Splash、初始化失败提示 | 独立 Swing 窗口，不依赖 AppContext；诊断详情可展开 |
| 文件面板 | 默认路径已全部迁移为 SwingSftpBrowser | 旧 SftpBrowserPane 的 TextInputDialog 仅保留在 JavaFX 回退路径 |
| `AccountDialog` | 已迁移：登录、注册、浏览器登录引导等完整表单 | 原生 Swing，保留 FX 数据模型 |
| `PreferencesDialog` | 已迁移：全部设置页、账号改密、导入文本和分享码输入 | 原生 Swing，保留 FX 数据模型 |
| `ProjectManagerDialog` | 项目编辑主体、项目包密码/分享码输入 | 仍为 Swing 外壳嵌入 FX 表单；错误/结果已转 Swing |
| `AboutDialog` | 已迁移：专用品牌、项目入口和环境信息页面 | 原生 Swing，与设置关于页共用 |
| `ColorSchemeEditDialog` | 已迁移：颜色方案编辑器 | 原生 Swing 颜色选择、滑块和预览 |

## IDEA 源码参考

本次实际操作了 IDEA 的「Data Sources and Drivers」窗口，并阅读本地 `intellij-community` 源码：

- `platform/platform-api/src/com/intellij/openapi/ui/DialogWrapper.java`：内容边距、左右按钮组、默认动作、紧凑底栏及分隔线。
- `platform/platform-api/src/com/intellij/openapi/ui/messages/MessageDialog.java`：消息/图标/动作的结构与默认动作处理。
- `platform/platform-api/src/com/intellij/openapi/ui/Messages.java`：统一消息与错误对话框入口。
- `platform/platform-impl/src/com/intellij/ide/ui/laf/darcula/ui/DarculaTextBorderNew.kt`、`DarculaButtonPainter.java`、`DarculaComboBoxUI.java`：输入边框、按钮状态、下拉框背景与箭头区域。
- `platform/platform-impl/src/com/intellij/ide/ui/laf/darcula/DarculaNewUIUtil.kt`：圆角和边框绘制。
- `platform/platform-resources/src/themes/islands/ManyIslandsDark.theme.json`：8px 圆角、1px 描边、输入/下拉框底色与悬停颜色。

Community 源码中不包含数据库插件的数据源表单实现；本次借鉴的是实际可读的通用 Swing 控件绘制和主题配置，并结合数据源窗口的实际布局。

实现使用当前工程的 FlatLaf、`SwingTheme` 和 `SwingDialogChrome`，不引入 IDEA 平台依赖。主题颜色、字体和缩放沿用宿主配置。

## 验证入口

- `InteractiveHostKeyVerifierTest`：一次性接受不写盘、永久接受写盘、已保存密钥不重复提示、变化密钥的一次性信任不改文件、旧 boolean 回调兼容。
- `PresentationUiSmokeTest`：原生连接控件、字段校验、密码/私钥/凭据库联动、测试连接、编辑结果、取消、本地终端切换和原插件兼容。
- `NativeDialogsUiSmokeTest`：原生消息/信任/凭据库控件、三个信任结果、真实 Swing 输入保存、密码/私钥显隐、深浅主题截图。

UI 测试使用临时用户目录与测试凭据，不使用正式 SSH 连接或正式凭据库。

## 启动与旧入口清理（2026-10-14）

- `StartupSplash`：520×300，深蓝背景、统一品牌图标、阶段状态与不定进度条；启动成功或失败后停止动画并释放窗口。提前关闭不会因异步 show 再次弹出。
- `SwingStartupFailure`：统一窗口标题与底部按钮区、错误摘要、折叠诊断详情。退出、窗口关闭与 ESC 只触发一次资源清理回调。
- `SettingsUiSmokeTest` 覆盖原生分组输入结果（去首尾空格）、分享预览及取消；`StartupUiSmokeTest` 覆盖窗口释放、详情展开、重复关闭和截图。
- 终端旧插件工具条、选择浮层和全局折叠顶栏入口已删除，不再保留隐藏的 FX 工具条桥。

### 启动首帧修复

Swing 启动框从 `Application.init()` 提前至 `Launcher.main()`：在诊断、字体、JavaFX 运行时和 AppContext 初始化前打开，并在 Launcher 线程等待 EDT 首次绘制完成（正常路径不增加固定等待时间）。窗口的原生底色预设为深蓝，启动期间置顶但不抢输入焦点，主工作台就绪立即释放。首页仍为 `SwingWorkbench` 的 Swing 欢迎页；JavaFX 仅为保留的业务模型与插件兼容依赖。

`LauncherUiSmokeTest` 使用全新子 JVM 和临时用户目录，Robot 截取真实屏幕首帧并校验深蓝内容，再执行完整 Launcher 启动并检查工作台显示、启动窗口释放；不再只用离屏 `window.paint()` 判断首帧。

更新检查与下载进度现统一使用非阻塞 SwingProgressDialog，支持百分比、不定进度和后台运行。关闭仅隐藏进度，不伪装成取消实际下载。系统应用菜单 APP_ABOUT 调用 MainWindow.openAbout，与帮助菜单共用 Swing 品牌页。
