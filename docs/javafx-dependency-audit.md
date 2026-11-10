# JavaFX 依赖与移除范围审计

审计日期为 2026-11-10，代码基线为 `v0.2.2`（`a9bb50a`），分析分支为 `analysis/remove-javafx`。
下文依赖统计和产物分析对应上述发布基线，后续实施情况记录在检查点中。

## 实施检查点

第一批改动已经开始实施。

- `terminal` 的目录读取/订阅和断连通知改用普通 Java 契约及 Swing EDT，删除两个直接 OpenJFX 依赖声明。会话关闭会注销订阅并忽略延迟到达的通知。
- 主机密钥确认窗口直接使用 Swing，保留单次信任、保存信任、取消和超时拒绝行为。
- 国际化服务改用标准 Java ResourceBundle 与可注销订阅，在通知观察者前更新文案快照。
- 内存回收状态改为 EDT 通知，重启辅助类移除 FX 退出调用。

第二批迁移已完成以下内容。

- Plugin SDK 的页面及项目贡献改为 `Supplier<JComponent>`，主题、语言和项目属性改用可注销的 Java 订阅。SDK、两类插件加载器和四个内置/示例插件均已移除直接 OpenJFX 依赖。
- 八个设置页及其导航窗口改用 Swing，保留懒加载、搜索、未保存编辑、应用、取消、主题刷新与关闭后的连接刷新。
- 账号登录、浏览器登录、注册验证码、修改密码、关于及配色编辑使用原生窗口。密码控件在提交或关闭后清空，验证码计时器和浏览器登录等待在窗口关闭时释放。
- 导入继续使用原解析器及加密持久化，文件读取与连接写入在后台执行。插件市场保留下载校验、安装替换、更新检查、启停、卸载及原生插件设置页。
- 已通过 JDK 21 的 `mvn -B -Pwith-plugins test`。设置导航缓存、所有设置页、应用/取消、关于布局、字体订阅和原生对话框的真实窗口回归通过。

宿主启动、工作台/会话模型、连接/项目/凭据表单和旧 FX 嵌入适配仍待迁移。
当前完整应用仍需要 JavaFX，尚未形成无 FX 安装包。新的 SDK 页面签名需要插件迁移并重新编译，尚未发布到 Maven Central。

## 1. 当前依赖范围

宿主默认显示 Swing 工作台，但启动生命周期、业务控制器、表单模型、属性监听和插件接口仍使用 JavaFX。
彻底移除需要同步替换这些调用链。

主 Maven 工程的 `src/main/java` 中有 71 个文件直接引用 JavaFX 类型或类名，另有 32 个测试文件。
独立 `plugins/` 工程还有 7 个生产源码文件引用 JavaFX。
统计覆盖 import、全限定名和反射类名，排除生成目录。它不包含所有通过宿主封装间接依赖 FX 的文件。

| 模块 | 主工程生产文件数 | 当前用途 |
|---|---:|---|
| `ui` | 51 | FX 工作台、业务表单与属性模型、Swing/FX 桥接、主题和设置 |
| `plugin-api` | 7 | Node 页面签名、主题/语言/项目属性监听 |
| `plugin-loader` | 3 | 插件状态属性、FX 页面上下文及线程调度 |
| `program-plugin-loader` | 3 | Program 插件上下文、扩展注册表状态属性 |
| `terminal` | 3 | 当前目录属性及 FX 线程回调 |
| `app` | 3 | Application 启动入口、渲染器选择、打包烟测 |
| `launcher` | 1 | Windows 子进程的 FX 模块启动参数 |

### Maven 依赖

- 根 POM 管理 `org.openjfx` 21.0.11 的 base、graphics、controls、swing、fxml，以及 javafx-maven-plugin 0.0.8。
- `app`、`ui`、`terminal` 直接声明 controls 和 swing。
- `plugin-api`、`plugin-loader`、`program-plugin-loader` 直接声明 controls。
- `app` 的跨平台 profile 额外声明 Windows/Linux classifier 的 FX 依赖。
- `core` 经 `plugin-api` 传递引入 FX。这也使依赖 core 的底层模块进入这条传递依赖链。
- 独立 `plugins/` 的四个示例/系统监控模块均声明 controls。
- `program-api` 自身只有 Gson 等依赖，生产源码没有 JavaFX 引用。UI 型 `ProgramPluginContext` 位于 `plugin-api`，仍有 FX 属性签名。

## 2. 正常功能中的实际调用

### 启动与主界面

- `app/.../Launcher.java` 调用 `JlShellDesktopApplication.main()`。
- `JlShellDesktopApplication` 继承 `javafx.application.Application`，经 `launch()` 启动 FX，再构造 `Stage` 和 `MainWindow.createScene()`，最终打开 Swing 窗口。
- `ui/.../view/MainWindow.java` 仍使用 FX 的 Tab、Tree、Scene、Stage 和选择模型组织业务动作。Swing 控件把操作交给 FX 控制器执行。
- 因此删除旧 FX 渲染器分支后，还需迁移被 Swing 共用的控制器与模型。

### 设置、对话框和主题

- `PreferencesDialog`、`ConnectionDialog`、`ProjectManagerDialog`、`VaultManagerDialog`、`AccountDialog`、`AboutDialog` 等仍构造 FX 模型。
- `SwingDialogHost` 和 `SwingFormRenderer` 将 FX 控件模型转成 Swing 呈现，仍通过 FX 按钮事件、结果转换器及嵌套事件循环执行操作。
- `SwingPreferencesDialog`、`SwingSettingsWidgets`、`SwingAboutPage` 读取 FX 的 Tab、Node、控件与属性。
- `ThemeService`、`I18nService`、`MainViewModel` 使用 FX 属性及 ObservableList。
- `SwingFxPanel` 继承 JFXPanel，为第三方 FX 页面提供嵌入支持。`SwingNodeImeBridge` 提供反向嵌入时的输入法适配。
- `FxThread`、`Platform.runLater` 和 FX 属性监听在服务及 Swing 适配器中仍有实际调用，需要随模型迁移逐项替换。

### 终端、SFTP 与监控

- JediTerm 界面和 PTY 主体是 Swing/AWT 实现。
- `TerminalViewHandle.cwdProperty()` 暴露 FX StringProperty，`ShellTtyConnector` 用 Platform.runLater 发布目录及退出通知。
- `SwingSftpBrowser` 监听上述属性实现目录跟随。
- `SwingSessionView` 用 FX Tab/TabPane 组织会话和插件页面，`SwingServerMonitor` 监听 FX 监控快照属性。
- 移除时应保持目录跟随、连接关闭、面板生命周期和监听器注销行为。

### 插件 SDK 与示例

| 接口 | 当前 FX 耦合 | 所需调整 |
|---|---|---|
| `PluginView.createView()` | 返回 Node | Swing 页面工厂与生命周期契约 |
| `PluginContext` | openTab(Node)、主题和语言属性 | 保留 Swing 开页能力，改用无 FX 的读取/订阅接口 |
| `ProgramPluginContext` | 主题和语言属性 | 替换 FX 监听类型 |
| `JlShellProgramPlugin.settingsView()` | 返回 Node | 提供 Swing 设置页能力 |
| `ProjectCreationContribution` | 新建及管理页返回 Node | 迁移项目扩展表单 |
| `ProjectCreationContext` / `ProjectManagementContext` | ReadOnlyStringProperty | 替换项目字段观察接口 |

现有 `openSwingTab()` 已支持 Swing 插件页面，但 SDK 公共类型仍带有 FX。
字体读取与监听使用的 `FontAwareContext` / `PluginFonts` 可以继续复用其无 FX 设计。
SDK 移除 Node 和 FX 属性会改变公开方法签名，需要发布具有明确兼容边界的新 SDK，并迁移依赖这些签名的插件。
纯 Swing 插件也需要检查所实现接口及主题/语言监听用法。

`plugins/plugin-sysmon` 目前同时保留 SwingMonitorDashboard 和 FX MonitorDashboard，以及 FX MetricCard、TrendChart。
三个 demo 插件也保留 FX 页面代码。
旧 FX 插件需要完成 Swing 迁移才能在完全无 FX 的宿主中打开页面。
本轮未扫描外部私有插件源码。

## 3. 构建、运行时与脚本

- Release 的 jlink MODULES 显式包含 `javafx.controls,javafx.swing`，传递带入 base 和 graphics。
- Preview 还显式包含 fxml，并带 FX 内部菜单导出参数与反射烟测。
- Release 使用 Liberica `jdk+fx`，打包命令及启动器参数带 FX 的 add-modules。
- `slim_distribution.py --provided-javafx` 已从安装包的应用 JAR 剔除运行时提供的 FX 条目，因此完整安装包没有在 JAR 和运行时重复保存这部分 FX。
- 独立增量 JAR 仍携带 FX 类和资源。
- `DistributionSmoke` 当前强制要求模块化 FX，迁移时需修改其分发契约。
- `build-dist.sh` 的 FX jmods 路径、`run.sh` 的 javafx:run、`ui/run-preview.sh` 和 `run-local-test.sh` 的 FX 渲染选项都需清理。
- SDK 发布、示例插件构建、测试夹具及旧说明需要随新接口同步更新。

## 4. 已发布 0.2.2 的体积分析

从 OSS 下载原始 JAR 和 macOS ARM64 ZIP，SHA-256 与本次发布清单一致。
所有数字采用十进制 MB，1 MB = 1,000,000 字节。

| 对象 | 总大小 | 可识别的 JavaFX 数据 |
|---|---:|---:|
| 独立增量 JAR | 34.83 MB | 与本地 OpenJFX 21.0.11 JAR 条目交集的压缩数据 7.14 MB，共 5,392 条 |
| macOS ZIP | 66.11 MB | FX 运行时模块压缩数据 6.72 MB，另有 FX 原生库 ZIP 压缩数据约 0.33 MB |

运行时四个 FX 模块的 jimage 内部压缩数据分别为 base 634,323 字节、controls 2,283,460 字节、graphics 3,725,935 字节、swing 77,725 字节。
总计 6,721,443 字节，对应解压后模块内容约 15.45 MB。
这里使用 JDK 17 的 jimage 工具读取发布运行时映像，宿主默认 JDK 27 的工具不兼容该映像格式。

仅从这些可识别数据估算，独立 JAR 有望降至约 27 MB，macOS ZIP 有望降至约 59–60 MB。
JAR 的 ZIP 条目元数据、待删除宿主适配代码、模块索引和最终重压缩会影响结果。
上述为数据归属分析与估算，尚未生成可运行的无 FX 构建。Windows/Linux 完整包需在各自平台重新打包后实测。
独立 JAR 与完整安装包的收益分别计算。

发布 JAR 中没有发现 RichTextFX、ReactFX、Flowless、ControlsFX、Ikonli 的类条目，项目 POM 也没有这些依赖声明。
发布 macOS 运行时只有上述四个 FX 模块，未包含 fxml、web、media。Preview 配置与 Release 的模块范围不同。

## 5. 建议实施顺序

1. 定义无 FX 的宿主状态与读取/监听契约，迁移主题、语言、会话、目录及项目字段属性。
2. 将 Swing 工作台的会话、侧栏、设置、连接和项目表单逻辑改为直接使用 Swing 与普通 Java 模型。
3. 更新 SDK 的页面和项目扩展接口，迁移内置/示例插件，处理旧 FX 插件的版本兼容提示。
4. 将启动、退出、重启、文件选择器及桌面菜单生命周期迁移至 Swing/AWT，删除 FX 渲染器与桥接代码。
5. 删除 Maven FX 依赖、FX 资源和脚本参数，改用普通 JDK 构建无 FX 运行时。
6. 验证连接、终端输入/目录跟随、设置、插件、拖拽布局及更新回滚，实测三平台完整包和增量包大小。

运行时从含 FX 改为无 FX 时，发布应选完整安装包，并设置新的启动器兼容基线。
应用 JAR 更新不会删除旧运行时中的 FX 模块。
