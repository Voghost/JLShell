# Action 发布回调

JLShellWebsite 提供 Action 发布入口：

```text
POST /api/v1/actions/releases
```

该接口用于 CI Action 把发布结果推送到网站后台。网站会创建或更新对应版本，并把资产加入后台。Action 推送进来的资产默认隐藏，除非显式传 `visible: true`；管理员可以在后台逐个展示或隐藏资产。

## 安全签名

服务端配置共享密钥：

```env
JLSHELL_ACTION_WEBHOOK_SECRET=replace-with-action-shared-secret
JLSHELL_ACTION_SIGNATURE_TOLERANCE_SECONDS=300
```

请求头：

```text
X-JLShell-Timestamp: <unix seconds>
X-JLShell-Signature: sha256=<hex hmac>
```

签名内容：

```text
<timestamp>.<raw-json-body>
```

算法：

```text
HMAC-SHA256(secret, timestamp + "." + rawBody)
```

Shell 示例：

```bash
body='{"version":"0.1.35","title":"JLShell v0.1.35","assets":[]}'
ts="$(date +%s)"
sig="sha256=$(printf '%s.%s' "$ts" "$body" | openssl dgst -sha256 -hmac "$JLSHELL_ACTION_WEBHOOK_SECRET" -hex | awk '{print $2}')"

curl -X POST "https://jlshell.oomn.net/api/v1/actions/releases" \
  -H "Content-Type: application/json" \
  -H "X-JLShell-Timestamp: $ts" \
  -H "X-JLShell-Signature: $sig" \
  --data "$body"
```

## Payload

```json
{
  "version": "0.1.35",
  "channel": "stable",
  "updateType": "jar",
  "title": "JLShell v0.1.35",
  "summary": "新增终端快捷键配置并修复断线重连问题",
  "releaseNotes": "## 新功能\n- 支持按工作区配置终端快捷键\n\n## 修复\n- 修复网络切换后的重连问题",
  "releaseUrl": "https://github.com/Voghost/JLShell/releases/tag/v0.1.35",
  "mandatory": false,
  "requiresFullInstaller": false,
  "minLauncherVersion": "0.1.0",
  "publish": false,
  "assets": [
    {
      "os": "windows",
      "arch": "x64",
      "packageType": "msi",
      "fileName": "JLShell-0.1.35-win-x64.msi",
      "url": "https://oss.example.com/jlshell/releases/0.1.35/JLShell-0.1.35-win-x64.msi",
      "size": 12345678,
      "sha256": "hex-sha256",
      "visible": false
    }
  ]
}
```

字段说明：

- `publish=false`：创建为草稿，不展示版本。
- `publish=true`：直接发布版本，但隐藏资产仍不会出现在公开下载和更新 API 中。
- `assets[].visible=false`：资产进入后台但不公开展示。
- `assets[].url`：当前用于公开下载 URL。
- `assets[].downloadUrl`：预留给后续“网站主动下载 Action artifact 并上传 OSS”的流程；当前若 `url` 为空，会先使用 `downloadUrl` 入库。
- `summary`：面向用户的一行摘要，不允许写构建编号或流水线状态。
- `releaseNotes`：Markdown 正文，使用二级标题和列表维护新功能、优化、修复及兼容性变化。
- `releaseUrl`：对应 GitHub Release 页面。OSS 只保留最近 3 个版本，旧版本会使用此地址引导下载。

## 版本说明来源

JLShell 仓库以 `.github/release-notes/<version>.md` 为唯一发布说明来源。Release 工作流会在打包前校验该文件，并同时用于 GitHub Release 正文和本接口 Payload。详细约束见：

```text
docs/release-notes-contract.md
```

## OSS 上传

当前版本完成了 Action 加密通信、版本入库、资产显隐控制。服务端主动下载 Action artifact 并上传阿里云 OSS 的 SDK 流程尚未启用；现阶段由 Action 先上传 OSS，再把最终公开 URL 通过 `assets[].url` 回调给网站。

公开版本 API 只会为按语义版本降序排列的最近 3 个已发布版本返回 OSS 资产。更早版本仍保留标题、摘要、更新说明和发布时间，但 `assets=[]`、`directDownloadsAvailable=false`，前端改用 `releaseUrl` 引导到 GitHub。

后续实现 OSS 上传逻辑请参考：

```text
docs/action-oss-upload-implementation.md
```
