# 0.1.68 Beta 打包体积审计

日期：2026-10-15。目标：在保持 SSH、SFTP、终端、插件及跨平台启动能力的前提下缩小分发体积。

## 实测结果

本机 macOS ARM64、Java 21 / JavaFX 21.0.11，大小使用 MiB（1,048,576 字节）。下表应用 JAR 来自同一轮源码构建；运行时使用相同 JDK 和 JavaFX jmods 对照构建。

| 项目 | 优化前 | 优化后 | 说明 |
| --- | ---: | ---: | --- |
| 应用 fat JAR | 52.4 MiB | 33.2 MiB | 清理不支持的平台本地库、重复品牌资源，ZIP 最高压缩 |
| macOS ARM64 安装包内应用 JAR | 52.4 MiB | 22.4 MiB | 仅自身平台本地库，JavaFX 类及资源由内置模块提供 |
| 内置运行时（磁盘占用） | 67 MiB | 56 MiB | 收紧根模块、仅中英文 locale、jlink ZIP 9 |

整套 Windows ZIP/MSI、macOS ZIP、Linux tar.gz/deb/AppImage 的最终大小以 GitHub Actions 产物为准。不能将应用 JAR 大小当作完整安装包大小。跨平台 JAR 保留支持平台的 SQLite/JNA/PTY 等原生资源；JavaFX 图形本地库仍受构建平台影响，本版必须使用完整安装包。

## 已移除和优化的内容

1. 当前宿主没有 FXML 加载入口，删除 app/ui 对 `javafx-fxml` 的直接依赖。JavaFX controls/swing 仍有模型、过渡界面与插件契约依赖，保留。
2. SQLite、JNA、pty4j、FlatLaf 的本地库按 Windows x64、Linux x64、macOS x64/ARM64 筛选；安装包进一步只保留自身平台。未按包名前缀删除 Java 类。
3. 统一运行时品牌图片入口为 `icons/brand/jlshell.png`，剔除打包用 ICO/ICNS、历史重复大图，保留界面实际使用的小尺寸图标。
4. installer JAR 中不再重复携带由 jlink 提供的 JavaFX 类和本地库；所有启动入口显式解析 `javafx.controls,javafx.swing`，Windows 二次启动保留该参数。
5. jlink 通过依赖闭包补齐根模块依赖，移除无使用证据的额外根模块，保留 HTTP、编译 API、脚本、JFR、加密、DNS、ZIP 文件系统及反射所需模块。locale 只打包当前支持的中文和英文。
6. JAR 与运行时 ZIP 9 压缩；Linux tar.gz 使用 gzip 9、DEB 使用 xz 9，Windows ZIP 使用 7-Zip 最高压缩。

## 必须保留的依赖

- SSHJ / Bouncy Castle：SSH 密钥格式、签名、加密协商，不因静态调用少就裁剪算法。
- SQLite JDBC / JDBI / Hikari：数据库及动态映射、服务发现和反射。
- JediTerm / pty4j / JNA / Kotlin：终端、系统桥接和本地进程。
- JavaFX controls/swing：尚未完成移除的模型与插件 API 依赖；插件需要额外 FXML 时必须声明自身依赖。
- Nerd Font、终端配色与图标：终端主题和特殊字符显示的产品能力。
- `META-INF/services`、第三方 LICENSE/NOTICE：运行时服务注册和第三方授权信息。

不使用 shade `minimizeJar`。静态可达分析不能覆盖插件、JDBC、JNA、加密 Provider 和反射入口。

## 发布隔离与兼容

- 版本 `0.1.68`、`channel=beta`，GitHub Pre-release，`make_latest=false`。
- OSS 路径 `<release-prefix>/beta/0.1.68/`；Beta 不触发旧稳定版本清理。
- 网站通知使用现有 HMAC 签名 webhook，上传失败或网站通知失败会使 Action 失败。
- 本版改变运行时和启动参数，网站设置 `updateType=full`、`requiresFullInstaller=true`，禁止仅替换旧安装中的 JAR。
- macOS 包实际架构取 runner 的真实架构，网站不再把单架构包标为 `any`。
- 保持仓库发布分支规则，触发公开发布前确认代码与发布说明对应的提交来源。

## 验证

- Maven `mvn -pl app,launcher -am test` 及 package。
- Python 资源裁剪矩阵测试、发布说明与变更范围校验、工作流 YAML / shell / 内嵌 Python 语法检查。
- 使用精简运行时和精简 JAR 验证模块化 JavaFX、SQLite 内存建表、Bouncy Castle、JNA 与真实 PTY 子进程。
- 使用相同精简运行时和 JAR（不添加 Maven 依赖类路径）冷启动真实 Launcher，验证数据库初始化、窗口显示、FX/EDT 响应、托盘及正常关闭。
- CI 三平台分别执行自己的打包运行时 smoke，任一平台失败均不进入 Release/OSS 发布。
