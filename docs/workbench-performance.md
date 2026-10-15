# 工作台启动、设置与内存优化

## 目标与已定位的问题

- [x] 缩短启动屏停留：Swing 模式不再构建不显示的 JavaFX 工作台，不预加载 FX Nerd Font 和隐藏 Stage 的图标。
- [x] 首次设置按需加载：只创建首个可见分类，其余分类首次访问时创建，整个设置会话保留模型和未保存的编辑值。
- [x] 设置搜索独立索引：搜索本地化文本，不依靠预先创建全部控件；分类名称优先于其他页面帮助文本中的提及。
- [x] 减少字体缓存：通用页使用名称进行字体分组，不逐个测量所有字体；字体下拉框测量布局时不加载物理字体，仅绘制可见行时生成字体预览。
- [x] 减少双重 UI：Swing 设置不再另外创建隐藏的 JavaFX Dialog/Stage；窗口关闭后清理已创建的 Swing 页面和 FX 属性监听。
- [x] 空闲内存归还：启动、重启、本地构建、Preview 和 Release 使用一致的 G1 参数，并用独立 JVM 验证临时分配释放后已提交堆自动缩小。

旧 JavaFX 插件依然完整嵌入 JFXPanel；首次显示插件时再加载其需要的 FX Nerd Font。Program 插件的启动与路由/权限注册顺序保持不变。

## 运行时参数

```text
-Xms32m
-Xmx512m
-XX:+UseG1GC
-XX:+ExplicitGCInvokesConcurrent
-XX:G1PeriodicGCInterval=30000
-XX:MinHeapFreeRatio=20
-XX:MaxHeapFreeRatio=40
```

保留最大堆容量供多会话和插件使用，小初始堆避免启动即占用过多空间；30 秒未发生收集时，G1 可启动并发周期并归还多余堆内存。不是每 30 秒执行 Full GC，也不在 UI 线程调用 GC。该行为及空闲堆比例语义见 [JDK 21 G1 文档](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html)。

最大 Java 堆不等于进程总内存：任务管理器还会计入代码缓存、类元数据、字体、线程和 Swing/JavaFX 的原生内存。不得将 `-Xmx` 当成进程 RSS 的承诺。

## 测量方法

使用 JDK 21，在桌面会话中运行：

```bash
mvn -pl app,launcher -am test
python3 .github/scripts/measure_workbench.py --output /tmp/jlshell-performance.log
```

探针使用临时空配置，启动实际 Swing 主窗口，连续打开并关闭三次通用设置，再观察 35 秒空闲期；不读取用户插件、SSH 配置，不建立远程连接，不通过强制 GC 修饰最终内存结果。输出各阶段耗时、已用/已提交 Java 堆、非堆、类数量；macOS/Linux 用 `ps` 采样 RSS，Windows 使用 `GetProcessMemoryInfo` 采样当前/峰值工作集及私有提交内存。Windows 的 `rss` 对应总工作集，`privateCommitted` 对应私有提交量，均不等于任务管理器默认显示的私有工作集；不能混用口径。需要 `JAVA_HOME` 指向 JDK 21。

启动耗时从独立探针的 main 到实际工作台窗口可见，覆盖工具包、服务和窗口创建；不包括安装器启动器进程派生和启动前 JAR 校验。因此它是可重复的同机对照，不能直接当成 Windows 从双击到首帧的绝对时间。

### 本机同条件对照

macOS ARM64、JDK 21，旧版为已发布 `0.1.70` 提交 `1004a05` 的独立源码快照；相同探针、临时空配置、三次通用设置开关、关闭后自然空闲 35 秒，均不调用 `System.gc()`。优化版使用上述新参数。

| 指标 | 0.1.70 | 优化后 |
| --- | ---: | ---: |
| 主窗口可见 | 3427 ms | 2489 ms |
| 首次通用设置 | 715 ms | 439 ms |
| 第三次设置显示时 RSS | 415 MiB | 338 MiB |
| 关闭设置后空闲 35 秒 RSS | 383 MiB | 193 MiB |
| 关闭设置后已加载类 | 11103 | 9307 |

这是同机单次对照，磁盘/系统缓存、字体、缩放、插件和操作系统调度均会影响绝对值；先前优化迭代中也观察到主窗口约 3.15 秒、首次设置约 389 毫秒。该表不作为 Windows 或多会话内存的承诺。RSS 变化也不能全部归因于 Java 堆回收；独立 JVM 的回收测试另行验证堆收缩机制。

## 回归覆盖

- `SwingPreferencesLoadingTest`：未访问分类不得初始化；搜索可发现尚未创建的页面；返回分类保留同一编辑器和未保存值。
- `SettingsUiSmokeTest`：真实设置的应用/确认/取消、搜索、历史导航、主题刷新、字体预览和导入表格。
- `WorkbenchUiSmokeTest`：JavaFX 导航兼容及原插件页面容器。
- `SwingPluginPageTest`、`SshWorkspaceUiSmokeTest`：完整旧 JavaFX 插件节点、原生 Swing 插件及会话。
- `RuntimeJvmOptionsTest`：独立 JVM 执行一次约 64 MiB 临时分配，在没有 `System.gc()` 的条件下检查已提交堆缩小；测试只将周期缩短为 250 ms。

多类 JavaFX UI 测试可使用 `-DreuseForks=false` 隔离工具包生命周期。Preview 的 Windows UI 步骤包含按需加载测试，并在独立空配置中运行性能探针，将日志保存到 Windows UI artifact。CI 空配置结果不代表用户的插件、多会话和屏幕缩放配置下的峰值。
