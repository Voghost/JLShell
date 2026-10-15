# UI Preview 测试包

`preview/ui-refactor` 保留 Swing UI 重构和独立的 Preview Actions。
推送到 `preview/**` 自动构建，也可在 Preview 工作流中手动选择该分支。
正式 Release 工作流不变；Preview 不创建标签或 GitHub Release，不上传 OSS，
不通知 JLShellWebsite，不合并到 `develop` 或 `main`。

## 下载和安装

在对应 Actions 运行的 Artifacts 中下载测试包，产物保留 14 天：

- macOS：ARM64、Intel x64 的独立 ZIP，解压后打开 `JLShellPreview.app`。
- Windows x64：便携 ZIP 和 MSI，应用及安装菜单使用独立名称 `JLShellPreview`。
- Linux x64：tar.gz、deb、AppImage；deb 使用独立包名 `jlshell-preview`。
- `preview-info-*`：版本、源码提交和构建地址。

版本采用 `0.1.67-preview.<运行序号>.<重试序号>`。
原生安装器内部使用纯数字 `1.0.<运行序号>`，满足 jpackage/MSI 的版本限制。
macOS 使用独立 Bundle ID `com.jlshell.preview`，Windows 使用独立 MSI 升级标识。
macOS 测试包使用临时签名，系统提示时可在系统设置中选择“仍要打开”。

## 配置和插件

测试包启动器通过仅在 CI 打包时生成的 `META-INF/jlshell-preview.properties`
启用隔离环境：数据库、连接、设置和插件存放在 `~/.jlshell-preview/.jlshell/`。
Preview 默认启用 Swing UI，关闭启动时的自动更新检查。
正常源码构建没有该标记，继续使用原来的配置位置和启动方式。

插件 SDK、加载机制及业务接口保持不变。测试已有插件时，将所需插件 JAR
复制到 Preview 对应的插件目录；正式版的插件目录不会自动复制。
Linux JVM 参数覆盖文件使用 `~/.config/jlshell-preview/JLShell.vmoptions`。

工作流先运行应用及启动器依赖模块的测试，再并行生成各平台安装包。
macOS 还使用安装包实际的 JVM 参数检查 JavaFX 原生菜单桥接的模块访问权限。
平台构建成功表示打包通过，Windows/Linux 的实际显示和交互仍需在对应系统测试。

## 运行期间的类加载保护（2026-10-14）

启动器不再在应用 `main()` 返回时关闭 URLClassLoader：AWT/JavaFX 事件线程可能仍需延迟加载类与资源。ApplicationRuntime 将类加载器保留至 JVM 退出；启动失败则立即释放。

应用归档启动前复制到更新目录下 `runtime/runtime-<pid>-<random>/app.jar`，校验复制前源文件与副本的 SHA-256 一致，然后只从副本加载。这样重新构建 Preview 或替换安装目录的源 JAR 不会破坏当前进程。正常退出清理副本；崩溃或 halt 留下的副本在下一次启动时按进程存活状态回收，仍在运行的进程不受影响。

`jlshell.active.jar` 继续指向选择的原始 JAR，保持更新启动确认和重启语义；新增 `jlshell.runtime.jar` 仅用于记录当前实际加载的副本。启动诊断同时记录两者。

全局异常报告先写 stderr，再尝试日志框架；若日志框架本身发生 LinkageError，保留原始堆栈并使用 stderr 输出二次故障，不让 UncaughtExceptionHandler 再抛出异常。

验证覆盖入口返回后的延迟类/资源加载、替换原归档、运行期缓存回收和日志实现缺类时的后备输出。此修复涉及 launcher，使用新的 Preview 或全量安装包启动后生效，单独替换应用 JAR 无法更新已安装的旧启动器。

源码 ui/run-preview.sh 默认增加 with-plugins reactor profile，构建原生系统监控插件并通过预览专用优先目录加载。每次启动复制到独立目录，不覆盖已安装或运行中的插件 JAR。旧第三方自绘插件继续按需兼容；详见 plugin-swing-views.md。
