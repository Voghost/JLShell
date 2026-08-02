# JLShell 插件开发与发布规范

本文说明如何制作能够被 JLShell 本地加载并通过插件商店安装的插件。客户端商店协议见
[`docs/client-plugin-store-api.md`](../docs/client-plugin-store-api.md)。示例插件均使用 Java 21、Maven、
Java `ServiceLoader`，并输出带 UTF-8 JSON 静态清单的 fat JAR。完整的机器可读包格式以
[`docs/plugin-package-spec.md`](../docs/plugin-package-spec.md) 为准。

## 1. 选择插件作用域

| 作用域 | SPI | 安装目录 | 运行时能力 | 更新要求 |
|---|---|---|---|---|
| `PROGRAM` | `JlShellProgramPlugin` | `~/.jlshell/program-plugins/<plugin-id>/<插件名>.jar` | 全局能力、设置页、存储；SDK 1.1.0 起可贡献 SSH 会话入口 | 安装或升级后重启 JLShell |
| `SESSION` | `JlShellPlugin` | `~/.jlshell/plugins/<plugin-id>/<插件名>.jar` | 当前会话 SSH/SFTP、标签页、会话能力、存储、主题、语言、通知 | 替换前必须先停用相关会话实例 |

程序级插件本体不能假定存在 SSH 连接。SDK 1.1.0 起，单一 Program 插件可通过
`ProgramPluginContext.sessionIntegration()` 注册会话贡献，由宿主为每个活动实例提供
受控的 `PluginContext/SshSessionContext`，无需再发布一个 Session 插件 JAR。会话级
插件如不依赖 SSH，可以让 `requiresSshSession()` 返回 `false`。

## 2. 包身份必须完全一致

一个商店插件有六处身份声明，发布前必须逐项一致：

| 信息来源 | 必须匹配的值 |
|---|---|
| 插件实现 | `id()`、`version()` |
| JSON 静态清单 | `META-INF/jlshell-plugin.json` 中的 `id`、`version`、`scope`、`entrypoint` 和兼容范围 |
| JAR 主清单 | `JLShell-Plugin-Id`、`JLShell-Plugin-Version`、`JLShell-Plugin-Scope` |
| ServiceLoader 文件 | 实际 SPI 实现类全限定名 |
| 商店插件记录 | `pluginId`、`scope` |
| 商店版本记录 | `version`、`entrypoint`、兼容范围、文件大小、SHA-256 |

客户端会在下载后严格比较商店返回值、JSON 静态清单、JAR 主清单、ServiceLoader 声明和入口类。
缺少 JSON 文件、编码不是 UTF-8 或任一字段不一致都会拒绝安装；从本地目录加载插件时也执行相同
的 JSON、主清单、SPI 和入口类检查。

命名约束：

- 插件 ID 必须是最长 128 字符的小写反向域名，例如 `com.example.terminal-tools`；ID 发布后不要修改。
- 插件版本使用 SemVer，例如 `1.2.0`，不要使用任意文本或按字符串比较版本。
- 作用域只能是大写的 `PROGRAM` 或 `SESSION`。
- `entrypoint` 必须是 ServiceLoader 文件中声明的实现类，例如
  `com.example.terminal.TerminalToolsPlugin`。

## 3. 实现插件 SPI

### Session 插件

```java
package com.example.terminal;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginContext;

public final class TerminalToolsPlugin implements JlShellPlugin {
    @Override public String id() { return "com.example.terminal-tools"; }
    @Override public String displayName() { return "Terminal Tools"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String author() { return "Example Team"; }
    @Override public String minHostVersionInclusive() { return "0.1.0"; }
    @Override public String maxHostVersionInclusive() { return "0.1.999"; }
    @Override public String description() { return "Utilities for the active SSH session."; }
    @Override public boolean requiresSshSession() { return true; }

    @Override public void activate(PluginContext context) {
        // 注册能力、打开标签页或读取插件私有存储。
    }

    @Override public void deactivate() {
        // 取消监听、停止任务并释放资源；该方法必须可重复安全调用。
    }
}
```

ServiceLoader 文件：

```text
src/main/resources/META-INF/services/com.jlshell.plugin.api.JlShellPlugin
```

文件内容只有实现类全限定名：

```text
com.example.terminal.TerminalToolsPlugin
```

### Program 插件

程序级插件实现 `JlShellProgramPlugin`，ServiceLoader 文件为：

```text
src/main/resources/META-INF/services/com.jlshell.plugin.api.JlShellProgramPlugin
```

如果插件还实现 `ProgramApiProvider` 以公开 JSON-RPC 方法，仍沿用同一个
`JlShellProgramPlugin` ServiceLoader 文件；宿主会在加载程序插件时激活 provider。

## 4. 添加 JSON 静态清单

每个插件模块都必须创建：

```text
src/main/resources/META-INF/jlshell-plugin.json
```

文件必须是 UTF-8 编码。最小可用示例：

```json
{
  "schemaVersion": 1,
  "id": "com.example.terminal-tools",
  "version": "1.0.0",
  "scope": "SESSION",
  "entrypoint": "com.example.terminal.TerminalToolsPlugin",
  "defaultLocale": "zh-CN",
  "displayName": "终端工具箱",
  "description": "为当前连接提供常用终端操作。",
  "author": "Example Team",
  "license": "Apache-2.0",
  "repository": "https://github.com/example/terminal-tools",
  "minHostVersion": "0.1.0",
  "maxHostVersion": "0.1.999"
}
```

其中 `schemaVersion` 首版固定为 `1`；`id` 必须是最长 128 字符的小写反向域名；`version` 和兼容
范围必须是 SemVer；`scope` 只能是 `PROGRAM` 或 `SESSION`。`displayName`、`description`、`author`
均为必填字段。多语言名称和介绍可通过 `translations` 提供。完整字段、URL 和安全约束见
[`docs/plugin-package-spec.md`](../docs/plugin-package-spec.md)。

## 5. Maven 构建规范

仓库内示例插件继承 [`plugins/pom.xml`](pom.xml)，每个模块只需声明以下属性：

```xml
<properties>
    <jlshell.plugin.id>com.example.terminal-tools</jlshell.plugin.id>
    <jlshell.plugin.version>1.0.0</jlshell.plugin.version>
    <jlshell.plugin.scope>SESSION</jlshell.plugin.scope>
</properties>
```

父 POM 会同时为普通 JAR 和 `*-fat.jar` 写入：

```text
JLShell-Plugin-Id: com.example.terminal-tools
JLShell-Plugin-Version: 1.0.0
JLShell-Plugin-Scope: SESSION
```

独立仓库应复制父 POM 中 `maven-jar-plugin` 与 `maven-shade-plugin` 的配置。Shade 构建必须：

- 使用 `ServicesResourceTransformer` 保留/合并 ServiceLoader 文件；
- 使用 `ManifestResourceTransformer` 写入上述三个静态字段；
- 将 `plugin-api`、`program-api`、JavaFX、Gson、SLF4J/Logback 视为宿主提供依赖，不打入 fat JAR；
- 将插件自行引入、宿主未提供的运行库打入 fat JAR；
- 不把 `com.jlshell.*` 宿主实现类复制进插件包，避免类加载冲突。

插件 Java 代码中的 `version()` 必须与 `jlshell.plugin.version` 完全一致。项目自身的 Maven
版本可以带 `RELEASE` 后缀，但商店版本和 JAR 清单应使用规范 SemVer。

## 6. 兼容性与运行规范

- 必须声明 `minHostVersionInclusive()`；建议同时声明 `maxHostVersionInclusive()`。
- 兼容范围应与商店版本记录的 `minHostVersion`、`maxHostVersion` 保持一致。
- JavaFX 节点更新必须在 JavaFX Application Thread 执行；SSH、SFTP、数据库和网络操作不得阻塞 UI 线程。
- `activate()` 中注册的监听器、定时任务和能力，应在 `deactivate()` 中安全释放。
- 只通过 `context.storage()` 保存插件数据；存储会按插件 ID 隔离。不要绕过命名空间读写其他插件数据。
- 不得把 Token、密码、私钥或用户数据写入日志、JAR、商店元数据或源码。
- Capability 名称在单个插件内必须唯一；输入参数应提供 JSON Schema，异步错误应通过失败的
  `CompletableFuture` 返回。
- 本地化名称和描述可覆盖 `displayName(Locale)`、`description(Locale)`，资源应随 JAR 一起打包。

## 7. 构建与发布前校验

构建全部示例插件：

```bash
mvn -f plugins/pom.xml clean package
```

商店上传应选择 `target/*-fat.jar`。首先确认 JSON 静态清单存在且内容正确：

```bash
unzip -p target/my-plugin-1.0.0-fat.jar META-INF/jlshell-plugin.json
```

再检查主清单：

```bash
unzip -p target/my-plugin-1.0.0-fat.jar META-INF/MANIFEST.MF
```

检查 ServiceLoader 文件：

```bash
unzip -p target/my-plugin-1.0.0-fat.jar \
  META-INF/services/com.jlshell.plugin.api.JlShellPlugin
```

程序级插件将最后一段替换为 `com.jlshell.plugin.api.JlShellProgramPlugin`。再计算商店版本记录需要的
文件大小与 SHA-256：

```bash
wc -c < target/my-plugin-1.0.0-fat.jar
shasum -a 256 target/my-plugin-1.0.0-fat.jar
```

发布检查清单：

1. JAR 包含 UTF-8 的 `META-INF/jlshell-plugin.json`，且 `schemaVersion` 为 `1`。
2. `id()`、JSON `id`、主清单 `Plugin-Id`、商店 `pluginId` 完全一致。
3. `version()`、JSON `version`、主清单 `Plugin-Version`、商店版本完全一致。
4. JSON、主清单和商店作用域完全一致。
5. JSON 与商店 `entrypoint`、ServiceLoader 实现类一致，且对应 `.class` 存在于 JAR。
6. JSON 与商店兼容范围完全一致。
7. 商店记录的大小和 64 位小写 SHA-256 来自最终上传的同一个 fat JAR。
8. 版本状态通过审批成为 `APPROVED`；客户端不会安装其他状态。
9. 在兼容的 JLShell 版本中完成加载、启用、停用、升级和失败回滚测试。

## 8. 本地安装与商店安装

本地开发可运行：

```bash
./plugins/build-and-install.sh install
```

Windows：

```bat
plugins\build-and-install.bat install
```

商店安装推荐使用独立目录，同时保留可识别的插件文件名：

```text
~/.jlshell/plugins/<plugin-id>/<plugin-id>-<version>.jar
~/.jlshell/program-plugins/<plugin-id>/<plugin-id>-<version>.jar
```

本地开发安装脚本保留 Maven 产物原名，例如 `plugin-demo-0.1.0.RELEASE-fat.jar`。为了兼容旧版本，
客户端仍会扫描插件根目录下的平铺 JAR，也会识别独立目录中的旧名称 `plugin.jar`。目录结构不影响
ServiceLoader；SPI 文件仍位于各 JAR 内部。

客户端同时保存 `install.json`，并将可回滚版本按原文件名放入 `.previous/` 子目录；备份不会参与插件
扫描。商店下载流程会
验证审批状态、文件大小、SHA-256、JSON 静态清单、JAR 主清单、SPI 与入口类，再原子替换当前版本。

## 9. 仓库示例插件

| 模块 | 插件 ID | 作用域 | Entrypoint |
|---|---|---|---|
| `plugin-program-demo` | `com.jlshell.demo.program-host-tools` | `PROGRAM` | `com.jlshell.demo.program.HostToolsProgramPlugin` |
| `plugin-session-demo` | `com.jlshell.demo.session-tools` | `SESSION` | `com.jlshell.demo.session.SessionToolsPlugin` |
| `plugin-demo` | `com.jlshell.demo.script-snippets` | `SESSION` | `com.jlshell.demo.ScriptSnippetsPlugin` |
| `plugin-sysmon` | `com.jlshell.sysmon` | `SESSION` | `com.jlshell.sysmon.SystemMonitorPlugin` |

程序级与会话级 Capability 调用示例、外部 JSON-RPC API 说明见
[`docs/plugin-and-external-api-guide.md`](../docs/plugin-and-external-api-guide.md)。
