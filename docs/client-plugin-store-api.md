# JLShell 客户端插件商店 API

本文供 JLShell 客户端实现插件搜索、详情、安装和升级。公开插件 API 无需账号 Token；服务端仅返回已上架插件和已审批、未封禁版本。

插件开发者需要遵循的 SPI、ServiceLoader、Maven 打包、JAR 清单和发布前校验规范见
[`plugins/README.md`](../plugins/README.md)。

示例基地址：

```text
https://jlshell.oomn.net
```

## 搜索插件

```http
GET /api/v1/plugins?query=terminal&scope=SESSION&hostVersion=0.2.0&locale=zh-CN&page=0&size=20&sort=updated
```

参数：

- `query`：匹配插件 ID、名称或介绍。
- `scope`：可选，`PROGRAM` 或 `SESSION`。
- `hostVersion`：可选，只保留存在兼容版本的插件。请求前必须规范为 SemVer 2.0；当前 Maven 版本中的 `.RELEASE`、`.FINAL`、`.GA` 或 `-SNAPSHOT` 后缀应先移除。
- `locale`：返回名称和介绍使用的语言，默认 `zh-CN`。
- `page`：从 0 开始。
- `size`：分页大小。
- `sort`：`updated`、`downloads` 或 `name`。

响应采用 Spring Page 结构，主要字段如下：

```json
{
  "content": [
    {
      "pluginId": "com.example.terminal-tools",
      "scope": "SESSION",
      "listingStatus": "LISTED",
      "displayName": "终端工具箱",
      "description": "为当前连接提供常用终端操作。",
      "author": "Example Team",
      "iconUrl": "/api/v1/plugins/com.example.terminal-tools/icon",
      "latestVersion": "1.2.0",
      "minHostVersion": "0.2.0",
      "maxHostVersion": "1.0.0",
      "downloads": 120,
      "updatedAt": "2026-07-14T12:00:00Z"
    }
  ],
  "number": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

## 插件详情与版本

```http
GET /api/v1/plugins/{pluginId}?locale=zh-CN&hostVersion=0.2.0
GET /api/v1/plugins/{pluginId}/versions
```

详情响应包含 `plugin` 和 `versions`。版本关键字段：

```json
{
  "version": "1.2.0",
  "entrypoint": "com.example.terminal.TerminalToolsPlugin",
  "minHostVersion": "0.2.0",
  "maxHostVersion": "1.0.0",
  "releaseNotes": "修复会话关闭后的资源释放。",
  "sha256": "64位小写十六进制",
  "size": 1048576,
  "status": "APPROVED",
  "downloads": 42,
  "publishedAt": "2026-07-14T12:00:00Z"
}
```

客户端只应展示和安装 `APPROVED` 版本。

## 检查升级

```http
GET /api/v1/plugins/{pluginId}/updates/latest?current=1.1.0&hostVersion=0.2.0
```

有更新：

```json
{
  "updateAvailable": true,
  "pluginId": "com.example.terminal-tools",
  "scope": "SESSION",
  "currentVersion": "1.1.0",
  "latest": {
    "version": "1.2.0",
    "sha256": "...",
    "size": 1048576,
    "status": "APPROVED"
  },
  "downloadUrl": "/api/v1/plugins/com.example.terminal-tools/versions/1.2.0/download"
}
```

无兼容更新时 `updateAvailable=false`，`latest` 和 `downloadUrl` 为空。版本选择遵循 SemVer，不得由客户端按字符串排序。

## 下载

```http
GET /api/v1/plugins/{pluginId}/versions/{version}/download
```

OSS 模式返回 `302` 到短时签名 URL，本地开发模式可直接返回文件。HTTP 客户端必须允许跟随重定向；不要持久化签名 URL。

下载流程：

1. 下载到目标目录中的临时文件。
2. 流式计算实际大小和 SHA-256。
3. 与版本 API 返回的 `size`、`sha256` 严格比较。
4. 校验 JAR 清单的 ID、版本和作用域。
5. 校验成功后在同一文件系统内原子移动到正式文件名。
6. 保留上一个可工作的版本用于回滚。

## 安装目录

- `PROGRAM`：`~/.jlshell/program-plugins/`
- `SESSION`：`~/.jlshell/plugins/`

建议每个插件独立目录，并使用 `<plugin-id>-<version>.jar` 作为文件名，不统一重命名为 `plugin.jar`。
客户端继续兼容直接放在插件根目录下的旧式平铺 JAR，以及独立目录内已有的 `plugin.jar`。上一个版本按
原文件名保存到 `.previous/` 子目录，不参与扫描。程序级插件更新完成后提示重启 JLShell；Session 级
插件必须先安全停用相关 Session 插件实例，再替换文件。

## 错误处理

- `400`：参数、版本或包声明无效。
- `404`：插件/版本不存在，或当前不可公开访问。
- `409`：版本已存在、状态不允许当前操作。
- `410`：版本已被安全封禁，不应继续重试下载。
- `429`：请求过于频繁，应指数退避。
- `5xx`：服务或 OSS 临时故障，可有限次数重试。

升级检查失败不应阻止客户端启动。下载校验失败必须删除临时文件，不得覆盖当前可用版本。

## 客户端实现清单

- 使用本机 JLShell 版本传递 `hostVersion`。
- 搜索和升级都尊重插件 `scope`。
- 跟随下载 302，但不记录签名 URL。
- 强制校验大小、SHA-256、静态清单。
- 使用临时文件和原子替换。
- 保存回滚版本和安装元数据。
- 对程序级插件提示重启，对 Session 级插件先停用后更新。

## JAR 清单约定

客户端下载的 JAR 必须包含 UTF-8 的 `META-INF/jlshell-plugin.json`，完整字段和校验规则见
[`plugin-package-spec.md`](plugin-package-spec.md)。JSON 中的 ID、版本、作用域、入口类和兼容范围必须与
API 返回值完全一致。

JAR 还需要在主清单中提供以下静态字段，且必须与 API 返回的插件 ID、版本和作用域完全一致：

```text
JLShell-Plugin-Id: com.example.terminal-tools
JLShell-Plugin-Version: 1.2.0
JLShell-Plugin-Scope: SESSION
```
