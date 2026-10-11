# SOC-3d 后续局部控制与返回边界优化

2026-10-09 用户授权四类关键路径一起修改，然后按 GitHub push/pull 流程，在 Alan 并行执行单核/四核 LiteX→Vivado，cloud_chen 完整回归。功能失败时撤销本候选物理构建。基于主仓 `9555be20a183c43728944b39fe4131493b127c28`，CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be` 不变。

## 实现

- Sv39Tlb 基础页存储拆为最多 4 个 set bank × way 的无掩码本地存储；读 bank 与请求同沿锁存，响应仍下一拍。基础页、超页 PA 分别提前生成，由唯一命中独热选择；页面权限不授权候选 PA 的副作用。
- 解码 PMP 的高位比较使用 8-bit 分块和均衡字典序归约。独立旧算法、first-overlap 优先级、跨 128 B 块 carry、完整包含和权限逻辑保持。
- L1D PLRU 按常量 set 使用本地状态生成各 way 的 touch 候选；命中/安装的候选选择与最终写许可分开。保留同拍 kill、安装优先级、零位 direct-mapped PLRU，不改变命中/提交/更新拍数。
- L1IClient 的 demand/prefetch payload、error、valid 增加一拍寄存边界；支持相邻周期返回，协议接收和在途所有权仍按实际 RSPdown fire 更新，不取消已接受响应。I-cache flush 继续等待旧 miss 排空并禁止旧数据安装/交付；命中路径不增加拍数。
- Realigner firstWord/rawInst 宽 payload 按本地状态提前捕获，不由晚到 redirect/response qualification 驱动 CE；状态、有效性和 fault 元数据仍使用原授权，取消的候选不输出。
- PTW 在原 `sWait→sCheck` 接收边沿保存 leaf/pageFault 预解码；状态数、done/refill 拍数和 accessFault 优先级不变。

已同步 MMU 存储/PTW 说明、L1D PLRU 结构、集群 L1I 返回边界授权例外；frozen manifest 只更新两份实际修订合同的摘要。100 MHz、生产配置和既有数值 golden 不放宽。

## 验证覆盖与证据边界

新增/补充：PMP 各比较分块边界与跨块 access；TLB 全 bank/set/way 保留及 targeted SFENCE；L1D 各 tag bank 的 killed hit 不改变 victim；相邻 demand/prefetch 返回及 error/data 保持；真实 L1IClient+I-cache 在返回边沿/下一拍 flush 不安装旧 refill；realigner 跨字第二 parcel 与 redirect 同拍、无效输入变化、输出反压稳定。复用完整回归中的 TLB 权限/超页/PTW 异常、cache 原子/一致性/kill 和后端精确拍数断言。

本地只完成编辑、静态检查；编译/生成/仿真均按实时共享主机配置在远端执行。下方记录本轮准确源 SHA 与启动证据。

## 基线新结果

9555be2 单核已在 Alan UTC 07:04:49.644665–08:11:13.210637 完成，exit 0、monotonic wall 3983.565155 秒；最终 soc-timing-summary.rpt 的 WNS +0.117 ns、TNS 0、WHS +0.030 ns、THS 0，bitstream 已生成。仅为单核生产布线证据，该单核结果不含完整功能回归通过或上板证明。基线四核布局后 -1.457 ns，已于 UTC 08:36:31 取消（exit -15，monotonic wall 5502.177883 秒）；回归由最新候选替换。保留这些结果，不把中间路径排名或单核通过写成四核通过。


## d4ae2fc 执行记录（UTC 08:44 状态）

源版本 `d4ae2fc24df26f597d4811cc2bbb12b9e8ae47da`，CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be`。经本地 7897 代理 push，`git ls-remote` 核对分支 SHA；cloud_chen 和 Alan 均经反向代理 18897、HTTP/1.1 clone/pull、递归拉取子模块，4 个独立 checkout 均核对 517 个输入文件哈希并确认 clean。

| 工作 | 实际主机 / cwd | 实际开始 UTC | 当前边界 |
|---|---|---|---|
| 单核、四核 RTL 生成 | cloud_chen `/home/cloud_chen/work/flow-soc3d-local-d4ae2fc-gen/design` | 08:36:27.177839 | exit 0；08:37:00.745187 完成；monotonic wall 33.567054 秒 |
| 完整回归 | cloud_chen `/home/cloud_chen/work/flow-soc3d-local-d4ae2fc-reg` | 08:36:27 | 程序构建、Scala 编译通过；未过滤 `sbt -batch test`，之后执行完整 `pytest sim/litex`；进行中 |
| 必要模块子集 | cloud_chen `/home/cloud_chen/work/flow-soc3d-local-d4ae2fc-gen/design` | 08:39:39 | L1IClient、真实 I-cache flush、realigner、PMP、TLB 结构与 Sv39 MMU/PTW 测试已通过；L1DCacheSpec 进行中；总 gate 未完成 |
| 单核生产 LiteX→Vivado | Alan `/home/chen/FUN/flow-soc3d-local-d4ae2fc-tiny` | 08:41:44.328640 | 综合进行中；100 MHz；无超时 |
| 四核生产 LiteX→Vivado | Alan `/home/chen/FUN/flow-soc3d-local-d4ae2fc-small` | 08:41:44.328635 | 综合进行中；100 MHz；无超时 |

单核/四核分别使用经 SHA 和逐文件哈希核对的 117 / 123 个 manifest 输入。首次启动于 08:38:23 因 `alan-env.sh` 缺少执行权限而在 Popen 前失败，保存在 `soc-*-job-failed-start`；修正权限后上述实际作业开始，不将首次失败启动算作 Vivado wall time。物理 wrapper 在结束后写入 exit、UTC 起止与 monotonic wall time。

执行证据：本地 `records/soc3d-local-control-20261009/`；cloud_chen `/home/cloud_chen/evidence/soc3d-local-d4ae2fc/`；Alan `/home/chen/FUN/flow-runs/soc3d-local-d4ae2fc/`。`versions.json`、`tested-inputs.json`、各 checkout `*-source-check.json`、`launched.json`、stage `command.json/process.json/result.json` 绑定准确版本、命令、cwd、PID/PGID/start_ticks 与耗时。

完整回归及子集的失败监控在 Alan 执行，经仅 loopback 监听的 SSH 转发读取 cloud_chen 状态；已收到匹配 d4ae2fc 的实时 healthy 状态。出现失败时记录 `candidate-rejected.json`，只有源 SHA、PID、start_ticks、PGID 匹配的本轮两路物理作业会被 SIGTERM。网络失联记录连接错误并重试，不当作通过。未复制私钥，未开放公网监听。

旧 cloud 回归已于 UTC 08:36:09 替换，日志和结果保留；仅清理已取消的本任务旧编译 `.o/.d/.gch`，有 cancellation、空活跃进程检查及逐文件清单，保留源文件、可执行文件、波形、日志和结果。新任务启动时 cloud 可用磁盘 23 GiB。未改数值 golden、断言、随机规模或时序约束；当前尚无 d4ae2fc 最终布线/bitstream/上板结论。

另对已取消的 9555be2 仿真文本记录（含生成的 execution-script）逐文件 zstd 无损压缩，先比较原文件与解压流 SHA-256，再释放原 `.txt` 占用；`compressed-old-logs-manifest.json` 保存原路径、压缩路径、字节数、时间、权限和验证摘要，内容可完整恢复，不压缩活跃回归目录。


## UTC 09:16 / 北京时间 17:16 更新

- d4ae2fc 单核生产 LiteX→Vivado 已完成，exit 0。UTC 08:41:44.328640–09:16:00.353884，monotonic wall **2056.024936 秒（34 分 16 秒）**。最终 `soc-timing-summary.rpt`：WNS **+0.067 ns**、TNS 0、setup failing endpoints 0；WHS **+0.030 ns**、THS 0、hold failing endpoints 0。bitstream 16,007,026 bytes 已生成；未上板，完整功能回归仍未完成。
- 必要子集 **7 suites / 83 tests 全通过**：failed/canceled/ignored/pending 均为 0，exit 0；wall **774.506366 秒（12 分 54.5 秒）**，北京时间 16:52:33 完成。包括新 L1D killed-hit PLRU victim 测试，以及 L1I 返回/flush、realigner、PMP 和 TLB/PTW。
- 四核综合已通过，仍在 placement 的 `Phase 4.1.1.2 Post Placement Timing Optimization`；日志此前唯一的 `Estimated Timing Summary` 为 WNS **-4.320 ns**、TNS **-69303.373 ns**，这是早期布局估计，尚无布局完成/最终布线报告或 bitstream。作业身份核对为 live，日志未见 ERROR。
- 完整回归仍在运行，已到 `L2HomeSpec`；双核/四核随机争用测试的 monitor、oracle、最终内存一致性通过；截至 UTC 09:16 未见 FAILED、Compilation failed 或 failure.json，不能写成完整回归通过。
- 失败监控 UTC 09:15:46 仍收到 d4ae2fc healthy 状态。旧仿真文本压缩完成，77 文件原始 8,890,324,322 bytes → 1,162,245,921 bytes，解压哈希核对通过；新回归当前磁盘可用约 13 GiB。

最终单核报告、最差路径与退出元数据已收集到本地 `records/soc3d-local-control-20261009/soc-timing-summary.rpt`、`soc-worst-10.rpt`、`result.json`；子集结果为 `targeted-result.json`。
