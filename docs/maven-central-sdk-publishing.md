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
- `sdkVersion`：不可覆盖的语义版本；本次 Swing 插件视图扩展使用 `1.6.0`，已公开的
  `1.0.0` 至 `1.5.0` 不得覆盖。
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


## SDK 1.6.0：Swing 插件视图

本次仅提取已在 0.1.68 Beta 验证的公共 API，桌面 Beta 的 UI 实现仍保留在 beta 分支。
同时发布 plugin-api 和 program-api 1.6.0，保持两者依赖版本一致；program-api 本身没有新增接口。

PluginContext 新增两个 default 方法：

- `supportsSwingViews()`：上下文未实现原生 Swing 视图时返回 false。
- `openSwingTab(String, Supplier<JComponent>)`：支持它的宿主在 EDT 调用组件工厂；默认实现抛出 UnsupportedOperationException，且不执行工厂。

新增 default 方法保持现有插件和 PluginContext 实现的兼容性。插件调用 1.6.0 新方法时，应将
最低宿主版本设为 0.1.68，并在调用 openSwingTab 前检查 supportsSwingViews。
早于 1.6.0 的宿主 SDK 根本没有这些方法，不能靠调用新方法来探测该旧宿主的能力。

Maven 坐标：

```xml
<dependency>
    <groupId>net.oomn.jlshell</groupId>
    <artifactId>plugin-api</artifactId>
    <version>1.6.0</version>
    <scope>provided</scope>
</dependency>
```

显式声明 program-api 时同样使用 1.6.0、provided。SDK 发布与桌面安装包发版独立，
本次不触发桌面 Release，也不修改已发布的 v0.1.68 标签。
