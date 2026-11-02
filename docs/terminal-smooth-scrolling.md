# 终端平滑滚动

终端普通滚轮使用约 140ms 的缓动；macOS 原生触控板直接响应像素输入，滚动条拖动使用 30ms 的时间线插值。其他平台的普通精细滚轮保留 20ms 插值。
触控板输入先按平台换算为像素，再转换为连续的终端行坐标；只保留小数仍不足以保证单位正确。
同方向连续输入累积目标；反向输入从当前可见位置改变方向，避免继续追赶旧目标。

实现参考本地 IDEA 源码：

- `platform/platform-api/src/com/intellij/ui/scroll/MouseWheelSmoothScroll.java`：滚动目标累积、反向处理、缓动时钟。
- `platform/platform-api/src/com/intellij/ui/components/JBScrollPane.java`、`platform/platform-api/src/com/intellij/ui/scroll/TouchScrollUtil.java`：区分原生触摸阶段与普通滚轮，触摸更新直接使用像素位移。
- `platform/platform-api/src/com/intellij/ui/components/JBScrollBar.java#getPreciseDelta`：macOS 普通 AWT 精细事件以 `10 * preciseWheelRotation` 还原逻辑像素，独立于字体大小和滚动行数。
- `platform/platform-api/src/com/intellij/ui/components/Interpolator.java`、`ScrollSource.java`、`JBScrollPane.java#getInitialDelay`：区分目标和当前呈现位置，按事件时间连接线性片段，以短周期补帧；按输入源选择插值延迟。这些路径受 IDEA 的设置和 Registry 开关控制，不表示所有 IDEA 运行配置都启用同一条路径。
- `platform/platform-impl/src/com/intellij/openapi/editor/impl/view/SimpleTextFragment.java`、`ComplexTextFragment.java`：按文本片段和字形向量绘制，避免逐字符重复调用绘图接口。
- `plugins/terminal/frontend/src/com/intellij/terminal/frontend/view/impl/TerminalOutputScrollingModelImpl.kt`：精细位移与整行定位区分、历史回看及到底后恢复跟随。

JLShell 继续使用 JediTerm 网格、ANSI 样式和滚动缓冲区，没有引入 IntelliJ 平台依赖。
过渡只影响绘制：两次受裁剪的网格绘制补齐露出的底部行，不修改文本或历史缓冲。2026-11-01 起在符合条件的历史滚动期间增加临时视口表面，复用已有像素；限制和释放策略见本文最后一节。
鼠标选择按相同像素偏移计算字符位置；点击时停止惯性，避免文字继续从指针下移开。
拖动滚动条、键盘定位、搜索跳转等外部滚动会中断旧动画。新日志触发的历史坐标补偿保留小数位置和缓动时钟，不作为手动定位处理。备用屏幕和远端鼠标上报继续走原始处理。
到达底部后仍由 JediTerm 跟随新输出；回看历史时不强制跳回底部。

动画只在过渡期间运行 7ms 的合并 Swing 定时器，隐藏/释放终端后停止。
原生触控板不启动应用层插值定时器，惯性由系统后续事件驱动。其他精细滚轮的短插值只用于填补事件之间的时间间隔，没有新增常驻轮询。

验证：`TerminalScrollAnimationTest` 检查缓动、连续输入、反向、小数累积、边界和取消；
`TerminalSmoothScrollingTest` 比较真实渲染的逐像素位移及底部补绘，检查选择位置、缓冲不变、手动滚动及备用屏幕回退；
`TerminalHighlightRenderingTest` 回归 ANSI、日志高亮、搜索和选中绘制。

## 2026-10-31 绘制性能复查

原先的过渡仍逐字绘制，并在每个字符上创建裁剪对象；一次动画帧还有一个补绘边缘的裁剪遍历。
现在将安全的简单字符合并为字形片段，仍把每个字形放在原有网格坐标；带负左侧字距的字符、斜体和复杂字符继续走原路径。分数 DPI 与 LCD 抗锯齿同时启用时，Java2D 字符与向量栅格化可能不一致，也保留原字形路径；裁剪和临时对象优化仍然生效。
裁剪的矩形分支使用整数范围计算，边缘补绘跳过整段不相交的文字，减少临时对象和无效绘图调用。
每个终端的字形布局缓存限制为 128 个片段、合计 16384 个字形；关闭终端时释放。此阶段尚未加入视口像素复用，后续改进见最后一节。

基准为本机 JDK 21、1284×680、160 列×40 行日志、预热 20 帧后取 100 帧的直接绘制耗时：

| 指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 每帧中位数 | 16.647 ms | 4.840 ms |
| 每帧 95 分位 | 18.996 ms | 9.781 ms |

这不是 Windows 实机帧率或输入延迟测量。复测命令（不同平台可使用各自 JDK 21）：

```bash
mvn -pl terminal -am test -Dtest=TerminalScrollPerformanceTest -Dsurefire.failIfNoSpecifiedTests=false -Djlshell.terminal.benchmark=true
```

`TerminalGlyphGraphicsTest` 比较原始逐字绘制与新路径的像素，覆盖普通/粗体/斜体、1×/1.5×/2× 缩放、灰度/LCD 抗锯齿、整数/小数字体度量以及中英文混排。
原生触控板 begin/update/end 阶段按像素响应；普通精细滚动序列中短暂出现整数 rotation 时，也继续保持精细输入模式，避免突然切换为普通滚轮的长缓动。

## macOS 触控板输入与历史坐标修正

用户复测环境为 macOS＋触控板＋本地 `run-local-test.sh`，不是 Windows 滚轮或旧发布包。
对照 IDEA 与 OpenJDK 后发现，旧实现仍把普通 AWT 的 `rotation * scrollAmount` 当作终端行数，再乘行高。
OpenJDK 的 macOS 原生层将精细触控板像素乘以 0.1；IDEA 在 Java 层乘以 10 恢复逻辑像素。
旧实现因此会放大位移，且改变字体会改变触控板滚动距离。

当前路径：

- macOS 普通 UNIT 事件：`pixels = 10 * preciseWheelRotation`，`rows = pixels / cellHeight`。整数 rotation 同样直接响应，保留系统加速和惯性，不叠加应用层缓动或插值延迟。
- JBR 触摸 update 事件：`pixels = preciseWheelRotation * scrollAmount`，其单位已是像素，不重复乘 10，直接响应原生输入。
- 其他平台继续保留滚轮行数偏好；BLOCK 事件继续按页滚动。
- 小于一个逻辑像素的输入持续累积，只在呈现时取整，避免每个微小事件强制跳一像素或在整数行边界多跳一像素。

另一个跳动来自 JediTerm 的历史缓冲：新日志到达后会调整 scrollbar 坐标，旧监听器把它误当成手动定位并清掉小数偏移；补绘底行还会恢复绘制前的旧坐标。
现在用顶部 `TerminalLine` 的对象身份区分“同一内容的历史补偿”和真正跳转，补偿时同步平移连续位置及动画目标。
输出线程的行位移计数只用于换算尚未被 JediTerm 重绘消化的坐标，覆盖一帧内交错到达的触控板和输出事件；历史环形缓冲已满时仍适用。
补绘恢复的是主绘制消化历史补偿后的坐标；清屏、调整尺寸、锚点被淘汰时退出旧偏移。

权威来源：

- [IDEA JBScrollBar](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/ui/components/JBScrollBar.java)
- [IDEA TouchScrollUtil](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/ui/scroll/TouchScrollUtil.java)
- [OpenJDK 21 AWTView.m](https://github.com/openjdk/jdk21u/blob/master/src/java.desktop/macosx/native/libawt_lwawt/awt/AWTView.m)：`hasPreciseScrollingDeltas` 分支。
- [OpenJDK 21 CPlatformResponder](https://github.com/openjdk/jdk21u/blob/master/src/java.desktop/macosx/classes/sun/lwawt/macosx/CPlatformResponder.java)：普通 UNIT 事件的 scrollAmount 为 1。

新增 `TerminalWheelInputTest` 与 `TerminalTrackpadScrollingTest`，覆盖多个字体行高的单位换算、1 像素连续输入、整数 rotation、反向、小于像素的累计、历史增长及环形缓冲满后的输出交错、逐像素内容稳定和手动滚动。

此前“精细事件直接定位”版本的本机 JDK 21 真实可见 Swing 窗口测量，1284×680、连续模拟 macOS 触控板事件并同步追加日志，预热后 180 次输入：

| 指标 | 本次测量 |
| --- | ---: |
| 实际绘制次数 | 204 |
| 绘制耗时 P50 / P95 | 2.162 / 3.045 ms |
| 绘制起始间隔 P50 / P95 | 9.894 / 12.081 ms |
| 输入处理到下一次绘制开始 P95 | 0.197 ms |

这些值测量 Java 端窗口重绘调度，输入是模拟事件；不包含原生事件队列之前的等待、显示合成或屏幕呈现延迟，也不能代替实体触控板手感验收。
复测使用 `TerminalScrollPerformanceTest#measureVisibleMacTrackpadSequence`，需启用 `-Djlshell.terminal.benchmark=true` 并有可用图形桌面；不对 CI 时间设硬阈值。

本地启动可加 `--trace-scroll`：

```bash
./run-local-test.sh --no-build --trace-scroll
```

每次停止输入约 500ms 后输出一条 `TERMINAL_SCROLL`，包含事件数、逻辑像素总量、绘制数、事件/绘制间隔 P95、绘制耗时 P95、输入处理到绘制开始 P95、历史补偿和外部重置次数。
只记录数值，不记录终端文字、命令、连接地址或凭据；采样数组有固定上限，默认关闭诊断，没有新增常驻计时器。

## 输入之间补帧与滚动条拖动

用户进一步反馈“像从一个位置直接跳到另一个位置，拖动滚动条也不如 IDEA 跟手”。
此前修复了 macOS 单位和历史补偿，但精细事件仍然直接设置当前坐标，两个输入时刻之间没有任何插值；滚动条也仍将整数行写入 JediTerm 模型。
因此，绘制耗时低不能证明滚动连续，单个最终位置的像素对比也不能证明事件之间有中间帧。

当时对照 IDEA 的 `Interpolator`、`ScrollSource` 和 `JBScrollBar#setValue`，实现了以下版本；其中 macOS 原生触控板的固定延迟随后按下节反馈移除：

- 触控板目标与当前显示位置分离，按输入时间连接线性片段，初始延迟 20ms。连续输入沿同一条时间线推进，保持短延迟，避免每个事件重新启动长缓动导致越来越落后。
- `InterpolatingTerminalScrollBar` 保留 `BasicScrollBarUI` 的原生拖动、轨道和 thumb 命中逻辑；`valueIsAdjusting` 期间截取绝对目标，经 30ms 插值呈现为中间像素位置，屏幕不再立即跳到目标行。
- 拖动时 thumb 保持在指针选择的位置；参考 `JBScrollBar.Model` 的监听器过滤，`TerminalScrollBarUI` 不让终端的中间呈现坐标反过来拉走 thumb。松手后恢复正常模型布局通知。
- 原生触摸结束、滚动条松手只结束输入，保留最后一段到准确目标。反向从当前可见位置重新连接；终端点击、键盘、搜索、清屏或备用屏幕继续按相应定位语义处理。
- 定时器每 7ms 合并调度，绘制开始时再按当前时钟采样一次，避免使用排队前的旧位置。无需长动画的静止阶段停止定时器。
- 待呈现片段上限 64；新日志导致历史坐标变化时，同步平移尚未完成的片段，不重新开始计时，不影响原始终端文本或 ANSI 处理。

当时新增 `TerminalScrollInterpolationRenderingTest`：注入可控时钟，对过渡过程的真实终端图像逐像素比较。当前用例保留滚动条拖动中间位置及松手落点检查，原生触控板用例改为检查输入当前帧直接到位、反向直接响应，以及触摸停止后没有应用层追赶。
其中独立用例将真实 `MOUSE_PRESSED` / `MOUSE_DRAGGED` / `MOUSE_RELEASED` 分派给 `BasicScrollBarUI`，覆盖实际鼠标处理路径。
`TerminalScrollAnimationTest` 同时验证长达 1000 次输入不会累积长延迟、反向及历史补偿。

同一窗口测量方法在本轮得到：180 次输入、240 次绘制；绘制间隔 P50/P95 为 7.267/8.924ms，绘制耗时 P50/P95 为 2.085/3.432ms。
这是带持续日志输出的模拟输入测量；实体触控板及最终屏幕呈现的手感仍需复测，不能仅凭这些统计承诺与 IDEA 完全一致。

## macOS 触控板跟手性修正

用户复测反馈：加入插值后略平滑，但 macOS 触控板不跟手。固定 20ms 的目标队列让画面始终落后于手指；帧间隔更稳定不能抵消这种额外延迟。

当前按输入来源区分响应：

- macOS UNIT 事件、JBR 原生触摸 update 走 `directWheel`，在处理当前事件时立即累计到连续像素位置并请求重绘；系统后续惯性事件照常处理，不自行生成追赶尾巴。
- 原生触摸接管尚未完成的滚动条或滚轮动画时，清空旧目标队列，防止反向输入后继续追赶此前的目标。
- 保留像素位移、子像素累计、历史坐标补偿、字形绘制优化；滚动条拖动仍使用 30ms 插值，其他普通精细滚轮仍使用 20ms 插值。
- `TerminalWheelInputTest` 验证输入来源选择，`TerminalScrollInterpolationRenderingTest` 检查原生触摸的当前帧位置、反向与停止；`TerminalScrollAnimationTest` 验证原生输入接管后旧动画不会恢复。

Windows 路径还参考 `platform/platform-api/src/com/intellij/util/animation/JBAnimatorHelper.java`：
仅在过渡动画运行期间申请 1ms 计时精度，多个终端重叠动画共享一次申请，最后一个动画停止、隐藏、取消或关闭时释放。
非 Windows 不加载 `winmm`；绑定失败或申请失败时退回普通计时器。可用 `-Djlshell.terminal.disablePrecisionTimer=true` 禁用此项。
复用桌面包已有的 JNA 5.17，不新增另一套原生库。`AnimationTimerPrecisionTest` 使用替身检查成对释放、重复请求、多动画重叠及失败回退；原生 Windows 调用尚未在本机实测。

## 2026-11-01 大视口、Retina 与绘制复用

本次重新检查了本机 IDEA 源码 `/Users/edgarliu/Workspaces/intellij-community-master`，并比较 JediTerm 3.61 / 3.76 的 `TerminalPanel` 源码：

- 本次对照时 JLShell 使用 3.61；这份 IDEA 源码的 `lib/BUILD.bazel` 引用 core/ui 3.76。这是源码快照的版本，不能据此认定用户安装的 IDEA 版本。之后 JLShell 已将 core/ui 同步升级至 3.76。
- 经典终端 `platform/execution-impl/src/com/intellij/terminal/JBTerminalPanel.java` 仍继承 JediTerm 面板；3.76 中经典面板依然按字符网格绘制、使用默认重绘定时器，没有自动获得新版编辑器的滚动实现。
- 新终端 `TerminalOutputScrollingModelImpl.kt` 使用 EditorImpl 的像素滚动模型。共享终端核心依赖不等于共享 UI 绘制链路。
- `platform/platform-api/src/com/intellij/ui/components/JBViewport.java` 明确说明 blit 复用已绘制区域，只重绘新露出的区域；`SimpleTextFragment.java` 则体现文本分片绘制。JLShell 借鉴这些机制，不引入整个 IntelliJ 平台。

用户真实日志中曾出现绘制 P95 20–32ms；此前 1284×680 的小窗口测试不足以代表大窗口体验。当前改为同时测量密集中英文日志、200 列×60 行、2× Retina，以及真实可见的 AWT 窗口。

本轮修改：

1. `TerminalViewportCache` 在连续历史滚动时复用上一帧像素，移动旧画面，只补绘新露出的条带。真实屏幕使用兼容的 `VolatileImage`，避免每帧从 CPU 位图上传整个 Retina 画面；离屏使用 BufferedImage。完整重绘时不套额外裁剪代理，避免逐字符裁剪带来的长帧。
2. 内容更新、字体/主题变化、尺寸变化使旧表面失效；有选择、查找结果、备用屏幕或待处理历史坐标变化时回到原绘制路径。表面丢失后重新绘制，不展示过期图像。
3. 每个终端至多一张临时表面，按设备像素计最多 32MiB，停止使用约 250ms 后通过单次 Swing Timer 释放，隐藏/关闭也释放。超大表面、非整数 DPI 或不兼容变换走原绘制；没有后台常驻轮询。可用 JVM 参数 `-Djlshell.terminal.disableScrollBlit=true` 做对照。
4. 修复视口高度不是行高整数倍时的底部补绘，避免跨整数行时出现跳动空带。新增回归曾在反向滚动和调整窗口高度后捕获此问题。
5. ASCII 绕过中文 Unicode 分类；字体回退结果使用最多 512 项的缓存，字体配置变化时清空，减少中文日志逐字符反复解析字体。
6. 保留 macOS 触控板直接响应，不增加手指输入到画面的插值等待。`--trace-scroll` 额外记录事件入队时间差、视口尺寸、DPI、复用/完整绘制次数和临时表面大小，不记录终端内容。

本机 JDK 21 测量结果：

| 场景 | 对照 | 本轮实现 |
| --- | ---: | ---: |
| 密集日志 1604×1020、2× 离屏，绘制 P50 | 19.637ms（修改前） | 7.029ms |
| 同上，绘制 P95 | 22.331ms（修改前） | 9.066ms |
| 可见大窗口、模拟触控板且持续输出，绘制 P50 | 8.390ms（禁用 blit） | 2.666ms |
| 同上，绘制 P95 | 10.502ms（禁用 blit） | 11.636ms |
| 同上，绘制间隔 P95 | 12.046ms | 12.112ms |

升级至 JediTerm 3.76 后，同一大窗口模拟测试得到绘制 P50/P95 2.774/11.780ms、绘制间隔 P95 12.008ms；该数据随本机负载波动，不能代替实体触控板验收。

可见窗口受到本机可用屏幕高度限制，对照为 1604×957，本轮为 1604×959。持续日志输出会使表面失效并触发完整绘制，因此中位数改善明显，但 P95 本轮未改善，不能宣称所有长帧已消除。以上均为 Java 端绘制/调度，输入为模拟事件，不等同于实体触控板到最终显示的延迟。

验证共 42 项终端相关测试通过，包括 1×/2× 像素一致性、上下滚动、输出期间滚动、非整行高度、尺寸变化、原生触控板直接定位、拖动滚动条、ANSI/日志高亮/选择/查找、原生图形表面缩放及空闲释放。

```bash
mvn -pl terminal -am test \
  -Dtest='Terminal*Test,RefreshableTerminalPanelImeTest,AnimationTimerPrecisionTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djlshell.terminal.benchmark=true \
  -Djlshell.terminal.benchmark.columns=200 \
  -Djlshell.terminal.benchmark.rows=60 \
  -Djlshell.terminal.benchmark.scale=2 \
  -Djlshell.terminal.benchmark.dense=true
```
