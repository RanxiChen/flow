# SOC-3d 合并控制链候选

2026-10-09 用户授权四项一起实现，以尽快时序收敛为目标。基于主仓 `56c121eccc23e0f1f6854c32d1934e7b786f8337`，CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be` 保持。源目录 `/home/chen/leisure/flow`，分支 `feat/pcie-fase-20260920`。不开展逐项物理对照实验；旧作业已按后续用户指令取消，保留原输入与报告。

## 实现与拍数

- L1D tag 候选地址、way mask、payload 与各来源最终许可分开；install > probe > allocation > PS 独热授权，保持同拍 kill 和资源所有权。
- FpUnit 两项无 flow/pipe 输入 Queue，tag 在后端接受时分配；CVFPU ready 不进入后端 req.ready，commit 仍 EX+2，原始 CVFPU 最早 EX+1 fire。FP32/FP64 ADDMUL 原始计算均 5 拍，后端接受到结果 6 拍；输出仍直接仲裁。kill 保留已提交 queued 项，作废队头本地丢弃；killDrain 同时检查缓冲和 CVFPU 排空。
- tag 按 way 与最多 8 个 set 分组，局部读写译码；EX payload 按原 exAdvance 捕获，只 ex.valid 使用晚到 idLeave。FP→x0 flags ownership 用寄存计数非零，逐拍断言对照 metadata 表。
- time/stimecmp 用 8-bit 并行比较和均衡字典序树，仍为同拍组合 pending，不增加中断延迟。

本批按明确授权更新 backend-timing-contract T14/T16、结构例外与 backend-v1/l1d spec；没有修改整数、普通 Load/Store、WB/W2 提交拍数、MMU/一致性协议、100 MHz 时序约束。原数值 golden 和安全断言含义保留；旧 raw input 与 EX 同拍的检查改为明确一拍请求缓冲合同，不放宽为任意延迟。

## 定向功能覆盖

新增定向用例覆盖两项输入缓冲填满/排空、同拍提交/取消及 committed queued DIV 存活、全部 tag set/way 与替换脏行、每个 timer byte 边界/最高位/零/全一和写后重装。复用完整 FpUnit 子模块套件、后端拍数/异常/保持子集、缓存 kill/refill/probe/原子/非默认几何、真实多核一致性和短集群冒烟。最初计划仅相关子集；后续按用户明确授权执行完整回归，与物理构建并行。执行主机 cloud_chen 优先，编译/RTL 生成/仿真前重读共享主机配置。

当前为实现记录，功能与时序均未验证；证据与最终 SHA 将在门槛完成后补充。


## push/pull 与并行执行（2026-10-09）

用户随后明确授权：本地 push，Alan 与 cloud_chen 从 GitHub pull；完整回归与单核/四核 LiteX → Vivado 同时推进，仿真出问题则撤销本候选构建。旧作业不再保留运行。

- 四项 RTL 固定提交 `88079178c3a7715601e39731c14ef884b018893f`。首次完整回归在 14:52:35 启动后，新增 BackendTimingProbe 跨层直接读取 CVFPU IO，Chisel 报 operand not visible；后端套件未进入仿真，属于测试台 elaboration 错误，不是数值/spec/RTL 失配。失败记录保存于 cloud_chen `/home/cloud_chen/evidence/soc3d-combined-8807917/regression/`。该候选物理构建尚未启动，失败监控阻止启动，旧回归已停止。
- 修正仅测试探针连接，使用 BoringUtils 的合法层级 tap，未改变四项 RTL 或 golden。最新主仓 `9555be20a183c43728944b39fe4131493b127c28`，CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be`。两次 push 均通过本地 `127.0.0.1:7897`，Git HTTP/1.1；两台机器的 pull 均通过反向代理 `127.0.0.1:18897`，没有绕过代理或采用 bundle 同步最终候选。四个独立 checkout 都核对 513 项输入摘要、HEAD 与 CVFPU SHA，clean。
- cloud_chen 最新回归于北京时间 **14:58:45** 启动，完整 program build → `sbt -batch test` → `python -m pytest sim/litex -q`；生成使用独立 gen checkout，避免 sbt/输出目录互相阻塞。15:00 已出现后端 T01 通过，确认探针修复生效；当时 7 项通过、无失败，仅为中间日志计数，不是完整通过。
- cloud_chen 同 SHA `single/small gshare linux` 生产 RTL 生成 exit 0，UTC 06:58:45.717722–06:58:59.537654，真实 monotonic wall **13.819646 秒**。归档单核 SHA256 `f916138c0705ace4168b0e218523a933cab897cf47e39e69786e0209e6a713da`，四核 `3256570b6c6feea1886cda47e0fc25cf732923830436e37e19da981a62e63a78`；后续安装校验其 source manifest 与所有文件摘要。

最新 cloud 源 `/home/cloud_chen/work/flow-soc3d-combined-9555be2-{gen,reg}`、证据 `/home/cloud_chen/evidence/soc3d-combined-9555be2`；Alan 源 `/home/chen/FUN/flow-soc3d-combined-9555be2-{tiny,small}`、证据 `/home/chen/FUN/flow-runs/soc3d-combined-9555be2`。本地 [retry-9555be2](../../records/soc3d-combined-20261009/retry-9555be2/) 保存版本、513 项 manifest、实际命令、主机配置快照、RTL 归档及运行元数据。SHA 中没有执行后的报告更新，报告本身不作为已测 RTL 输入。

## 旧作业取消与 wall time

北京时间 **14:49:13** 按用户要求，只对本任务记录匹配的 PID/start_ticks/PGID 发送 SIGTERM，未使用全局 kill，也未修改旧输入/报告。三项均验证已退出，stage wrapper 保存真实 monotonic wall 与 exit -15：

| 旧构建 | 起止北京时间 | wall 秒 |
| --- | --- | --- |
| `30f4b0e` 单核 | 13:05:37.019638–14:49:13.856904 | 6216.836939 |
| `30f4b0e` 四核 | 13:05:37.024474–14:49:13.857893 | 6216.833076 |
| `56c121e` FP32 单核 | 14:11:36.138621–14:49:13.861881 | 2257.723051 |

旧 `30f4b0e` reboot 后完整回归于 14:49:13.748220 取消，保留失败/通过日志与 user-cancellation.json；UTC 起止差 3463.310154 秒，不冒称原 driver 已输出 monotonic 终态或完整回归结果。更早 `d276f8b` 已有终态，不重复信号取消。没有新的最终 routed timing 或上板结果。

## 最新候选启动状态（15:05 北京时间）

Alan 同时启动两路生产 LiteX → Vivado，单核 `breeze-tiny` 和四核 `breeze`，均为 100 MHz、KCU105、Vivado 2022.2、maxThreads 4，无 wall-time 超时限制。归档的所有文件与 manifest 摘要校验通过。四核 stage UTC 07:04:49.644032、单核 07:04:49.644665 开始；15:05:14 两个按 PID/start_ticks 匹配的进程都存活，日志已进入综合，无 ERROR，尚无布局/布线报告。启动元数据保存在本地 `retry-9555be2/alan-launched.json` 以及远端 `launched.json`、`soc-{tiny,small}-job/{command,process}.json`，终态 wrapper 将记录真实 monotonic wall time。

cloud_chen 于 15:05:12 已完成 21 个测试用例，无 FAILED 或 failure.json。T14（后端 EX 接受、CVFPU 下一拍发射）及 T15（直接结果输出）通过；完整回归仍在运行，不能据此声称所有功能验证通过。失败监控每 30 秒检查回归；一旦发现失败，按 SHA/PID/start_ticks/PGID 只撤销本候选两路物理构建，保留日志。

## 15:55 状态快照（同 SHA 9555be2）

Alan 两路仍存活。单核已进入 route Phase 4.1，布局后 WNS -0.098 ns，物理优化后 +0.014 ns；布线中间估算 WNS +0.320 ns、TNS 0、WHS -0.112 ns、THS -4.368 ns。这些是中间结果，hold 尚有违约，不能认定最终时序闭合。四核在物理优化 Phase 17 Rewire，最近估算 WNS -1.738 ns、TNS -26706.137 ns，未到最终布线；两个构建各已运行约 50 分钟，无 ERROR，没有 bitstream。

cloud_chen 完整 Scala 回归仍在运行，截至下载快照已出现 277 个完成用例、失败标记 0 个；完整回归未结束。最新日志包含完整整数 ISA 项目的 PASS 输出以及 CSR、前端恢复、L1D permission/context 测试。日志快照和计数在本地 retry-9555be2/scala-full-snapshot-1555.log 与 regression-status-1555.json。

## 详细路径提取

同 SHA 的布局后检查点详细分析见 [SOC-3d-9555be2-path-analysis.md](SOC-3d-9555be2-path-analysis.md)。报告进程 exit 0、wall 226.261672 秒，只读检查点副本；四核已完成布局/物理优化 WNS -1.457 ns，残留路径集中在 TLB→PMP、L1D→后端 kill→PLRU、L2→前端与 PTW→TLB refill 控制。单核布局 hold -0.089 ns 在 CVFPU DIV/SQRT 直连寄存器间；尚不能指认为布线中间 -0.112 ns 那条路径。原构建继续，未改 RTL/约束。
