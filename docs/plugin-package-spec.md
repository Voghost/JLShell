# JLShell 插件包规范 v1

本文定义 JLShellWebsite 可接收的插件包格式，也是 JLShell 客户端安装和加载插件的静态契约。网站只读取 JAR 条目，不加载入口类，也不执行插件代码。

## 基本约束

- 插件包必须是可独立分发的 fat JAR。
- 一个包只能属于 `PROGRAM` 或 `SESSION` 一种作用域，插件创建后不可改变作用域。
- 插件 ID 是全局唯一、不可变的小写反向域名，例如 `com.example.terminal-tools`。
- 版本必须符合 SemVer 2.0，已上传的同名版本不得覆盖。
- 包内不得包含 JLShell Plugin API、JavaFX 或 SLF4J 的宿主类。
- 默认最大包体积为 50 MB，实际限制以服务端配置为准。

## 静态清单

每个 JAR 必须包含 UTF-8 JSON 文件：

```text
META-INF/jlshell-plugin.json
```

完整示例：

```json
{
  "schemaVersion": 1,
  "id": "com.example.terminal-tools",
  "version": "1.2.0",
  "scope": "SESSION",
  "entrypoint": "com.example.terminal.TerminalToolsPlugin",
  "defaultLocale": "zh-CN",
  "displayName": "终端工具箱",
  "description": "为当前连接提供常用终端操作。",
  "translations": {
    "en": {
      "displayName": "Terminal Tools",
      "description": "Common terminal actions for the active session."
    }
  },
  "author": "Example Team",
  "license": "Apache-2.0",
  "website": "https://example.com/terminal-tools",
  "repository": "https://github.com/example/terminal-tools",
  "minHostVersion": "0.2.0",
  "maxHostVersion": "1.0.0"
}
```

字段说明：

| 字段 | 必填 | 规则 |
| --- | --- | --- |
| `schemaVersion` | 是 | 首版固定为 `1` |
| `id` | 是 | 小写反向域名，最长 128 字符 |
| `version` | 是 | SemVer 2.0 |
| `scope` | 是 | `PROGRAM` 或 `SESSION` |
| `entrypoint` | 是 | 完整 Java 类名 |
| `defaultLocale` | 建议 | 默认 `zh-CN` |
| `displayName` | 是 | 默认最长 100 字符 |
| `description` | 是 | 插件公开介绍 |
| `translations` | 否 | locale 到名称/介绍的映射 |
| `author` | 是 | 作者或组织 |
| `license` | 否 | 建议使用 SPDX 标识 |
| `website` | 否 | HTTPS 官网地址 |
| `repository` | 否 | HTTPS 源码仓库地址 |
| `minHostVersion` | 否 | 最低兼容 JLShell SemVer |
| `maxHostVersion` | 否 | 最高兼容 JLShell SemVer |

清单 `id`、`version` 和 `scope` 必须与网站中创建的插件及版本声明一致。

## SPI 文件

Session 级插件必须提供：

```text
META-INF/services/com.jlshell.plugin.api.JlShellPlugin
```

程序级插件必须提供：

```text
META-INF/services/com.jlshell.plugin.api.JlShellProgramPlugin
```

SPI 文件只能声明一个非注释入口，并且必须与清单 `entrypoint` 完全一致：

```text
com.example.terminal.TerminalToolsPlugin
```

对应 `.class` 必须存在于 JAR 中。一个 JAR 同时包含两种 SPI 文件会被拒绝。

## 服务端静态校验

上传完成后，服务端至少检查：

- 声明大小、实际大小和 SHA-256。
- JAR 是否可读取、清单是否存在及 JSON 是否有效。
- ID、SemVer、作用域、入口类和兼容范围。
- SPI 文件、入口类及清单是否一致。
- 绝对路径、`..`、反斜杠路径、重复条目。
- 条目数量和解压后总体积，避免 ZIP Bomb。
- 是否错误打包 `com/jlshell/plugin/api/`、`javafx/`、`org/slf4j/`。

校验通过只代表结构符合规范，不代表代码可信。首版不执行恶意软件扫描，也不会运行插件测试。

## 构建建议

插件应把自身第三方依赖打入 fat JAR，但将下列依赖设为 `provided` 或等价范围：

- JLShell Plugin API
- JavaFX
- SLF4J API

发布前应在 CI 中验证 JAR 内容、计算 SHA-256，并保留与网站返回值一致的校验值。

## 版本与兼容

- 同一版本永久不可覆盖；修复后发布新的 SemVer。
- 网站升级接口只从已审批、未封禁、兼容当前宿主版本的版本中选择最高 SemVer。
- `maxHostVersion` 为空表示不设上限，`minHostVersion` 为空表示不设下限。
- 已审批版本会保留用于旧客户端，但管理员可因安全原因封禁下载。
