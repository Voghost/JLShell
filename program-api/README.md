# Program API SPI

`program-api` 是 JLShell 对外 JSON-RPC 宿主方法的纯契约模块。它只依赖 Gson，**不依赖** `core`、`api-server`、JavaFX 或具体 SSH 实现，因此外部程序插件可以针对稳定接口编译。

首个公开 SDK 版本为 `1.0.0`，从 JLShell 私有 GitHub Packages 获取：

```xml
<dependency>
    <groupId>com.jlshell</groupId>
    <artifactId>program-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

需要实现 JLShell Program 插件生命周期时，同时以 `provided` 方式依赖
`com.jlshell:plugin-api:1.0.0`。这两个 SDK 均由宿主提供，不得打入插件 fat JAR。

## 架构与职责

```text
program-api                 定义 SPI、会话抽象和方法注册表
        ↑                           ↓
app                         api-server
core → CoreProgramSessionService    将已注册方法暴露为 /rpc
```

- `ProgramApiProvider`：注册 JSON-RPC 方法的 SPI。
- `ProgramApiContext`：提供方法注册表、稳定的会话操作、API Token 和后台执行器。
- `ProgramSessionService`：连接、断开、查询会话和执行命令；不会暴露 `core` 的类型。
- `ProgramApiRegistry`：方法名到异步处理器的映射。方法名不能为空且全局唯一；重复注册会抛出异常。
- `ProgramSession`、`ProgramCommandResult`：跨模块使用的稳定数据模型。

`app` 提供 core 适配器和内置 provider；`api-server` 只消费 `ProgramApiRegistry`。API Server 创建前必须完成 provider 注册，运行中的新方法不会热加载。

## 内置 Provider

应用内置实现为 `com.jlshell.app.api.DefaultProgramApiProvider`，通过以下文件由 `ServiceLoader` 发现：

```text
app/src/main/resources/META-INF/services/com.jlshell.program.api.ProgramApiProvider
```

它注册 `session.*`、`command.run`、`api.token` 和 `api.methods` 等系统方法。系统 API 的文档目录在 `ProgramApiCatalog`；插件自定义方法不应使用这些保留名称。

## 外部程序插件扩展 API

外部 JAR 应同时实现 `JlShellProgramPlugin` 和 `ProgramApiProvider`。程序插件加载器以 `JlShellProgramPlugin` 发现 JAR，并在普通插件激活后调用其 `ProgramApiProvider.activate(...)`；因此**只需要**登记 `JlShellProgramPlugin` 的服务文件。

```java
public final class ToolsPlugin implements JlShellProgramPlugin, ProgramApiProvider {
    @Override public String id() { return "com.example.tools"; }
    @Override public String displayName() { return "Tools"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String description() { return "Example Program API provider"; }

    @Override
    public void activate(ProgramPluginContext context) {
        // 可选：注册设置页、全局 capability 等程序插件功能。
    }

    @Override
    public void activate(ProgramApiContext context) {
        context.registry().register("tools.echo", params ->
                CompletableFuture.completedFuture(params == null ? JsonNull.INSTANCE : params));
    }

    @Override public void deactivate() { }
}
```

服务文件为：

```text
src/main/resources/META-INF/services/com.jlshell.plugin.api.JlShellProgramPlugin
```

内容为实现类全限定名：

```text
com.example.tools.ToolsPlugin
```

方法名应使用插件专属前缀，例如 `tools.echo` 或 `com.example.tools.echo`。不要注册 `session.*`、`command.run`、`api.token`、`api.methods`、`capability.list`、`capability.invoke` 等宿主保留方法。

## 打包与安全要求

- 将 JAR 放到 `~/.jlshell/program-plugins/` 或应用安装目录旁的 `program-plugins/`，然后重启应用。
- 编译依赖 `program-api` 与 `plugin-api`，但 fat JAR **不得**打入这两个 API 模块；它们必须由宿主 ClassLoader 提供，否则 SPI 类型不匹配。
- Maven Shade 示例：

```xml
<artifactSet>
  <excludes>
    <exclude>com.jlshell:plugin-api</exclude>
    <exclude>com.jlshell:program-api</exclude>
  </excludes>
</artifactSet>
```

- Provider 拥有连接、命令执行和 API Token 等高权限能力，只安装可信 JAR，且必须自行校验参数、超时和访问范围。
- 所有阻塞工作使用 `CompletableFuture` 或 `context.executor()`；不得阻塞 JavaFX 线程或 HTTP 请求线程。

可运行示例见 [`plugins/plugin-program-demo`](../plugins/plugin-program-demo)。它额外注册 `demo.host.info` 与 `demo.echo`。
