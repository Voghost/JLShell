# JLShell 版本说明与历史下载规范

本文是 JLShell 客户端仓库、GitHub Actions、JLShellWebsite 后台和公开版本页面之间的统一契约。

## 单一信息源

每次发布前，在 JLShell 仓库创建：

```text
.github/release-notes/<version>.md
```

例如 `0.1.46` 使用 `.github/release-notes/0.1.46.md`。文件格式：

```markdown
# JLShell v0.1.46

> 用一句话描述该版本最重要的用户价值。

## 新功能

- 支持按工作区配置终端快捷键。

## 优化

- 优化大量连接时的列表渲染性能。

## 修复

- 修复网络切换后的自动重连问题。

## 兼容性

- 本版本要求启动器不低于 0.1.0。
```

约束：

- 一级标题必须包含本次语义版本号。
- `> ` 行是官网摘要，必须是单行、面向用户的自然语言。
- 正文至少包含一个二级标题和一个列表项。
- 推荐分类为“新功能、优化、修复、兼容性”，空分类删除。
- 不写 GitHub Actions run id、内部任务编号、无意义提交列表或“若干问题修复”。

## GitHub Actions 行为

JLShell `.github/workflows/release.yml` 必须按以下顺序执行：

1. 校验 `.github/release-notes/<version>.md`，不合规时在三平台打包前失败。
2. 生成 `release-metadata.json`，包含 `title`、`summary`、`releaseNotes`、`releaseUrl`。
3. 使用同一份 Markdown 生成 GitHub Release 正文。
4. 上传完整安装包与增量 JAR 到 OSS。
5. OSS 的 `releases/` 命名空间只保留语义版本最新的 3 个目录。
6. 通过 HMAC 签名回调把元数据和资产写入网站。

回调中 `releaseNotes` 必须是 Markdown 内容，不能再传 GitHub URL；GitHub URL 放在 `releaseUrl`。

## 网站与后台行为

- Action 写入的标题、摘要、更新说明和 GitHub Release 地址都可以在后台继续修改。
- 后台修改只影响网站数据库和公开页面，不反向修改 GitHub Release。
- 对相同版本重新运行 Action 会再次写入 Action 中的版本说明，因此人工修改应放在该版本最后一次 Action 回调之后。
- 公开版本列表按语义版本降序展示，并将 Markdown 安全解析为章节和列表，不执行 HTML。

## OSS 历史策略

公开接口针对每个渠道分别计算最近 3 个已发布版本：

- 最近 3 个版本：`directDownloadsAvailable=true`，返回可见 OSS 资产。
- 第 4 个及更早版本：`directDownloadsAvailable=false`、`assets=[]`，只返回发布信息和 `releaseUrl`。
- 历史页必须展示 GitHub 归档说明和“前往 GitHub 下载”入口，不得继续使用数据库中可能已失效的 OSS URL。
- `/api/v1/updates/latest` 和 `/api/v1/downloads/latest` 始终使用最新版本，不受历史页降级展示影响。

## 发布检查清单

1. 新增并审阅本版本 Markdown。
2. 确认版本号与工作流输入完全一致，不带 `v`。
3. 确认摘要没有构建信息，正文包含用户可感知变化。
4. 运行 Release 工作流并确认 GitHub Release、OSS、网站回调成功。
5. 在网站后台检查版本状态和资产可见性，必要时修改说明。
6. 验证历史页最近 3 个版本走 OSS，第 4 个版本跳转 GitHub。
