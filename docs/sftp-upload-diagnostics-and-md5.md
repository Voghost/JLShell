# SFTP 上传诊断与 MD5 校验

## 需求与状态

- [x] 检查真实上传实现，针对高延迟链路提高有界写入并发。
- [x] 增加上传分阶段日志，帮助定位本地、线程队列、通道建立及写入等待。
- [x] 本地、远程文件右键菜单增加“计算 MD5”；支持多选、复制结果、取消。
- [x] 使用应用真实 SFTP 服务进行延迟链路测试，校验上传内容。
- [ ] 在用户指定公网服务器测试：2026-10-18 已使用公网 IPv4 直连，系统路由经物理 Wi-Fi 网卡，SSH TCP 端口返回 Connection refused，未进入公钥认证。待服务器端口可达后补测。

## 上传调整

0.1.72 已使用 SSHJ 的异步写流水线，但固定深度为 16。单次 WRITE 负载遵守协商的 SSH 通道包限制，通常约 32 KiB；16 个请求的在途数据量约 0.5 MiB。高往返延迟下，发送端容易因等待 WRITE 确认而停顿。

默认深度改为 64，约 2 MiB 在途数据预算。仍按块读取文件，不将整个文件装入内存。保留包大小限制、续传偏移、取消与写入失败处理；所有 WRITE 确认完成且文件句柄关闭后才能通知上传完成。该调整能改善流水线限制，不能替代实际网络、远端磁盘和 CPU 的排查。

可通过 JVM 参数临时对照（范围 1–128，默认 64）：

```text
-Djlshell.sftp.uploadPipelineDepth=16
```

不改变下载实现，不增加传输线程池或依赖，不自动发布安装包。

## 受控测试结果

测试使用生产 `SshjSftpService`、SSHJ 客户端、MINA SFTP 服务端，以及双向延迟 TCP 代理。每次上传 8 MiB 不可压缩随机数据，完成后逐字节比对。每个候选先预热，再正序和逆序测量；不以吞吐阈值作为易抖动的单元测试断言。

2026-10-18，本机 macOS / JDK 21：

| 注入 RTT | 深度 | 正序用时 / 吞吐 | 逆序用时 / 吞吐 |
| --- | --- | --- | --- |
| 0 ms | 16 | 0.041 s / 196.31 MiB/s | 0.045 s / 176.17 MiB/s |
| 0 ms | 64 | 0.041 s / 196.55 MiB/s | 0.039 s / 207.12 MiB/s |
| 0 ms | 128 | 0.041 s / 196.30 MiB/s | 0.040 s / 200.45 MiB/s |
| 80 ms | 16 | 2.171 s / 3.69 MiB/s | 2.109 s / 3.79 MiB/s |
| 80 ms | 64 | 1.044 s / 7.66 MiB/s | 1.024 s / 7.81 MiB/s |
| 80 ms | 128 | 2.151 s / 3.72 MiB/s | 1.054 s / 7.59 MiB/s |

64 在两轮高延迟测试中稳定提升约 2 倍；128 没有稳定额外收益，因此默认选用 64。以上是受控协议测试，**不是用户公网服务器实测速度，也不是 Windows 性能承诺**。

复现：

```bash
mvn -pl sftp -am test \
  -Dtest=SshjSftpServiceTest#comparePipelineDepthsOverDelayedTransport \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djlshell.sftp.benchmark=true
```

## 诊断日志

关联 `traceId` 查看 `operation=sftp-upload`：

- `executor-queue`：提交到传输线程真正开始执行的等待。
- `sftp-channel-open`：打开 SFTP 子系统的耗时。
- `final-write-confirmations`：最后一批 WRITE 的确认等待。
- `total`：整个上传任务，包括通道关闭；区分 success / failed / cancelled。

每个文件结束另输出一条 `SFTP upload traceId=...` 汇总：

- `fileBytes` / `resumedBytes` / `queuedBytes`：文件大小、续传起点、实际提交写入的字节。
- `packetBytes` / `pendingWrites`：负载块大小与流水线深度。
- `localReadMs`：本地读取累计耗时。
- `writeMs`：写入调用累计耗时，包含加密、流控及等待服务器响应，不能单独视为网络 RTT。
- `listenerMs`：进度回调累计耗时。
- `finalAckMs` / `payloadMs` / `MiBps`：尾部确认等待、数据阶段总时间和成功任务的数据阶段平均速率。

`queuedBytes` 不代表已经确认或落盘的字节，失败任务速率为 0。汇总不输出完整路径、主机、用户名或凭据。日志随现有 `-Djlshell.performance.enabled=false` 开关关闭，不逐包刷日志。

## MD5 操作与实现

本地和远程文件列表选中文件，右键选择“计算 MD5”。文件夹禁用；多文件串行计算。窗口显示每项的 MD5 和状态，可复制已完成结果，格式为 `摘要  文件名`。本地和远程分别计算后可核对摘要；目前不自动配对或宣称两个文件一致。

- 本地：后台流式读取，检查计算前后的文件大小、修改时间与文件标识。
- 远程：独立 SSH exec 调用固定的 `sh -s`，将经过 POSIX 引号处理的脚本和路径作为标准输入发送；文件名不进入 exec 命令行。优先 md5sum、BSD md5、openssl，通过输入重定向只读文件。
- SFTP-only、没有 sh 或校验工具的服务器：回退到有界 SFTP 预读并流式计算。远程日志 `operation=sftp-md5` 的 `server-hash-used` / `sftp-read-fallback` 表示实际路径。
- 校验前后检查远程文件大小、修改时间；读取长度变化、错误与取消不会返回成功摘要。并发修改仍可能在时间戳精度内漏检，应在上传完成后对静止文件校验。
- 关闭结果窗口或浏览器会请求取消，迟到结果不再写入 UI。窗口有宿主 owner 且非模态，关闭不最小化主窗口。

## 公网实测入口

`SftpLiveBenchmarkTest` 默认跳过，显式开启后使用同一生产服务，对比 16/64 两种深度，并对上传结果计算远端 MD5。仅生成随机数据，上传到自己创建的 `/tmp/jlshell-sftp-probe-<UUID>` 目录，正常/连接仍可用时清理本次文件和目录。进程被杀或连接中断时可能遗留该临时目录，禁止清理其他目录。

```bash
mvn -pl sftp -am test \
  -Dtest=SftpLiveBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Djlshell.sftp.live=true \
  -Djlshell.sftp.live.host='<可直连公网IP>' \
  -Djlshell.sftp.live.port='<SSH端口>' \
  -Djlshell.sftp.live.user='<用户名>' \
  -Djlshell.sftp.live.knownHosts='<已核验的known_hosts文件>' \
  -Djlshell.sftp.live.key='<可用的SSH私钥路径>'
```

默认 16 MiB，可用 `jlshell.sftp.live.sizeMiB` 设置 1–128。严格校验 known_hosts；不会信任任意公网 host key。未指定 key 时使用 SSHJ 默认密钥路径；该测试不实现交互式密钥解锁或系统 SSH agent，不修改连接配置和代理设置。

## 验证范围

- 真实协议：上传包大小、续传、空文件、取消、写入拒绝、尾部 ACK，以及 MD5 的 SFTP-only 回退。
- 真实 SSH exec + shell：通过 stdin 执行原生 MD5，避免传回文件内容；特殊路径包含中文、引号、换行。
- 摘要：空文件、标准向量、读取长度变化、取消、严格解析和文件变化检测。
- 真实 Swing：本地/远程右键菜单、目录禁用、后台计算、复制结果、取消、迟到结果和 owner 关闭行为。
