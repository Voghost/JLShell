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
平台构建成功表示打包通过，Windows/Linux 的实际显示和交互仍需在对应系统测试。
