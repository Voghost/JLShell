# JLShell SDK Maven Central 发布

JLShell 对外发布两个 Java 21 SDK，Maven 坐标与 Java 源码包名相互独立：

```text
net.oomn.jlshell:plugin-api
net.oomn.jlshell:program-api
```

源码仍使用 `com.jlshell.*` 包名。SDK 发布 POM 会在 CI 中扁平化，移除仅供仓库内部
构建使用的 `com.jlshell:jlshell-parent` 父 POM，并保留 Central 所需的许可证、开发者、
SCM 和依赖元数据。

## 一次性准备

1. 登录 <https://central.sonatype.com/>，申请并验证 `net.oomn` 命名空间。
2. 在 Central Portal 生成 User Token，保存其 username 与 password。
3. 创建用于发布签名的 GPG 密钥，并把公钥发布到可公开查询的 keyserver。
4. 在 `Voghost/JLShell` 的 Actions secrets 中配置：

```text
CENTRAL_USERNAME
CENTRAL_PASSWORD
MAVEN_GPG_PRIVATE_KEY
MAVEN_GPG_PASSPHRASE
```

`MAVEN_GPG_PRIVATE_KEY` 必须是 ASCII armored 私钥，可通过以下命令导出后完整复制到
GitHub secret，私钥和密码不得提交到仓库：

```bash
gpg --armor --export-secret-keys <KEY_ID>
```

## 发布步骤

手动运行 GitHub Actions 工作流 `Publish JLShell SDK to Maven Central`：

- `sourceRef`：默认 `main`，正式发布应使用已合入 `main` 的提交或标签。
- `sdkVersion`：不可覆盖的语义版本；连接前路由 SDK 对应下一版本 `1.4.0`，已公开的
  `1.0.0` 不得覆盖。
- `autoPublish`：首次发布保持 `false`。工作流会上传并等待 Central 校验，通过后由维护者
  在 Portal 中检查并手动 Publish。确认流程稳定后才可选择 `true` 自动公开。

工作流只发布 `plugin-api` 和 `program-api`，不会上传桌面端其他模块，也不会触发
JLShell 产品安装包发布。Maven Central 已公开的版本不可删除、覆盖或修改。

## 本地验证

常规 API 测试：

```bash
mvn -pl plugin-api,program-api -am test
```

没有中央仓库凭据和正式 GPG 密钥时，可以跳过签名与上传，在本地验证扁平化 POM、
sources JAR 和 javadoc JAR 的生成流程：

```bash
mvn -pl plugin-api,program-api -Pcentral-release \
  -DskipTests -Dgpg.skip=true package
```
