# SSH、文件浏览和插件首次打开优化

## 本轮需求与实现

- [x] SSH 连接时显示连接状态、目标地址和细进度条，替换单独的省略号；终端到达后移除加载页。
- [x] 本地目录树以“此电脑”为虚拟根，异步枚举系统盘符；当前 UNC 根会补入列表，保留当前目录展开与选中。
- [x] SFTP 上传使用 SSHJ 有界流水线，按协商包大小拆分，保留续传、取消和错误反馈。
- [x] 传输进度最多每 100 ms 更新一次，文件末尾仍更新；避免小数据块洪泛 EDT。
- [x] Swing 插件打开不再额外排回 FX 队列等待 `activate()` 返回；动态页面显式重新布局与绘制。
- [x] AWT 和 JavaFX 字体注册使用独立锁，避免终端首次注册字体阻塞另一套 UI。
- [x] 插件打开增加点击至 FX 调度、上下文准备、页面请求、EDT 工厂和首帧的阶段耗时。
- [x] 真实 Windows 第二盘符导航与原生插件首帧回归（Preview 38852722699，4 项新增回归全部执行通过）。

## 根因与边界

本地树原先只以当前路径的盘符作为树根，因此 C 盘目录中看不到其他盘符。盘符枚举和
目录读取现在都在后台执行；只在展开目标盘符时读取其中的目录。

上传原先调用同步 `RemoteFile.write`，每块数据等待一次远端确认。现在使用
`RemoteFileOutputStream(offset, 16)`，单块不超过协商包上限及 64 KiB；未确认数据量有界，
不把整个文件读入内存。全部 WRITE 确认并关闭远端句柄后才报告完成。移除了失败后
自动改用 `sftp.put()` 重传的回退，因为该路径会绕过续传、取消和进度契约。

Swing 插件仍经过 FX 会话模型。原先 `openSwingTab` 即使已在 FX 线程，也会再排一次
`Platform.runLater`：插件打开页面后执行的其他激活工作，也会拖延页面发布。现在在
FX 线程直接发布，Swing 工厂仍在 EDT 执行；从后台调用时仍安全地切回 FX。
Program 会话贡献和普通 Session 插件共享这条路径。

没有后台执行第三方 Swing 工厂或 JavaFX 控件创建，也没有预先激活插件、预建隐藏
工作台或为 Swing 插件强制初始化 JavaFX 字体/皮肤。旧 JavaFX 页面仍完整挂载 JFXPanel。

这不能保证任意私有插件都从 5 秒降到某个固定值：如果插件在请求页面前同步访问网络，
或者 Swing 工厂本身同步做耗时工作，仍需要插件调整。新增日志能定位具体等待位置，
无需上传公司内部源码。`fx-dispatch` 高说明 FX 排队，`swing-view-request` 与
`context-ready` 差距大说明激活阶段慢，`edt-factory-start` 高说明 EDT 排队，
`edt-factory-ready` 与 start 差距大说明页面工厂慢，`first-paint` 则覆盖布局绘制。

## 验证

- 本机 Java 21 完整 Maven reactor 测试通过。
- 应用级真实 UI 回归通过，包括旧 JavaFX 自定义编辑区/图标/列表、菜单、设置、弹窗和工具窗切换。
- 新增目录树回归覆盖多根展示、切换另一根后读取文件、远端目录不受本地切换影响，以及系统根枚举。
- 新增加载页回归确认终端组件到达后，加载页完全移除。
- 回环 Apache MINA SFTP 测试通过：跨包随机数据一致、非整块续传、相同长度/远端更长、空文件覆盖、取消清理、服务器拒绝写入、等待最终确认。
- 延迟代理实测（1 MiB、双向各注入 15 ms 延迟）：同步上传 1601.7 ms，流水线 308.9 ms，约 5.19 倍。该结果只用于展示网络等待瓶颈被消除，不代表所有网络都达到相同比例。
- Windows Preview 创建额外 `subst` 盘，真实盘符枚举、进入盘符和文件可见均通过；测试后移除映射。
- Windows 原生插件测试在 `openSwingTab` 后故意保持激活函数未返回，工厂执行与首帧仍能完成，点击至首帧实测 95.5 ms。该场景验证宿主不再等待激活返回，不代表公司插件的业务初始化耗时。测试只在 Windows 运行：macOS 的辅助功能/输入法桥接会同步跨 UI 线程，人工阻塞 FX 会干扰 AWT 原生事件循环。
- Windows 应用级 UI 回归共 20 项，全部通过，无跳过。
- 本机实际 fat JAR 打包通过，确认不含 Apache SSHD 或测试类；测试依赖未进入安装包。

本地 SFTP 性能验证命令：

```bash
mvn -pl sftp -am test -Dtest=SshjSftpServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Djlshell.sftp.benchmark=true
```

Apache MINA SSHD 仅为 test scope，参考其[官方发布页](https://mina.apache.org/sshd-project/downloads.html)。
它不会进入桌面安装包。本轮不改变 Plugin SDK 或网站更新契约，不创建公开版本。
