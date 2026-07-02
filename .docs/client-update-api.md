# JLShell 客户端更新接口

本文档给客户端 agent 使用。官网后端只提供更新元数据，实际文件通过 OSS/CDN 下载。

## 接口

```text
GET /api/v1/updates/latest
```

示例：

```text
GET https://jlshell.oomn.net/api/v1/updates/latest?channel=stable&current=0.1.41&os=windows&arch=x64
```

查询参数：

| 参数 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `channel` | 否 | `stable` | 发布通道，支持 `stable`、`beta` |
| `current` | 否 | `0.0.0` | 当前客户端版本 |
| `os` | 否 | `windows` | 客户端系统，建议传 `windows`、`mac`、`linux` |
| `arch` | 否 | `x64` | 架构，建议传 `x64`、`arm64` |

## 响应

```json
{
  "updateAvailable": true,
  "latestVersion": "0.1.42",
  "channel": "stable",
  "updateType": "jar",
  "requiresFullInstaller": false,
  "minLauncherVersion": "0.1.0",
  "releaseNotesUrl": "https://jlshell.oomn.net/releases/0.1.42",
  "asset": {
    "id": "uuid",
    "os": "windows",
    "arch": "any",
    "packageType": "jar",
    "fileName": "jlshell-app-0.1.42.jar",
    "url": "https://cdn.example.com/jlshell/releases/0.1.42/jlshell-app-0.1.42.jar",
    "size": 47710208,
    "sha256": "hex-sha256",
    "visible": true
  },
  "fallbackInstaller": {
    "id": "uuid",
    "os": "windows",
    "arch": "x64",
    "packageType": "msi",
    "fileName": "JLShell-0.1.42-win-x64.msi",
    "url": "https://cdn.example.com/jlshell/releases/0.1.42/JLShell-0.1.42-win-x64.msi",
    "size": 123456789,
    "sha256": "hex-sha256",
    "visible": true
  }
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `updateAvailable` | 服务端比较 `latestVersion` 与 `current` 后给出的结果 |
| `latestVersion` | 当前通道可见的最新版本 |
| `updateType` | 常规为 `jar`，表示优先使用 jar 增量更新 |
| `requiresFullInstaller` | `true` 时必须下载全量安装包，不能只替换 jar |
| `minLauncherVersion` | jar 增量更新所需的最低 launcher 版本 |
| `asset` | jar 增量包。仅客户端更新流程使用，官网“下载”页不会展示 jar |
| `fallbackInstaller` | 全量安装包或压缩包。jar 不适用、校验失败、launcher 太旧或 `requiresFullInstaller=true` 时使用 |

## 客户端决策建议

1. 请求更新接口，带上当前版本、系统和架构。
2. 如果 `updateAvailable=false`，不提示更新。
3. 如果 `requiresFullInstaller=true`，下载 `fallbackInstaller`。
4. 如果当前 launcher 版本低于 `minLauncherVersion`，下载 `fallbackInstaller`。
5. 否则优先下载 `asset` 并进行 jar 增量更新。
6. 如果 `asset` 为空或下载/校验失败，退回 `fallbackInstaller`。
7. 下载完成后必须按 `sha256` 校验文件，不通过则删除文件并报错。

## 平台匹配

服务端会按 `os`、`arch` 查找可见资产：

- `arch=any` 可以匹配任意架构。
- Windows 全量包优先级：`msi`、`zip`。
- macOS 全量包优先级：`zip`。
- Linux 全量包优先级：`deb`、`appimage`、`tar.gz`。

## 官网下载接口

官网页面使用：

```text
GET /api/v1/downloads/latest?channel=stable&os=windows&arch=x64
```

该接口只返回全量包，不返回 `jar`。如果某个平台没有可展示的全量包，会返回 `404`，前端显示“等待发布”。
