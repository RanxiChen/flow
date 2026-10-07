# CLUSTER 续：SFENCE 排空裁定与 trigger CSR（交给 codex 跑和修）

起点：包含本文件的提交。接续 [`CLUSTER-sim-tests.md`](CLUSTER-sim-tests.md) 首轮报告（`76bb1e7`）的 R1、R2。本提交的 RTL 与测试由 Claude 编写，本地未编译、未运行 sbt。执行主机按 `docs/cross-project/simulation-host.md` 重新预检（先 `cloud_chen`，不可用再 Alan），报告记录实际主机。

## 1. 裁定

### R1：SFENCE 与 iTLB 查询 S1 同拍

根因：MMU `idle` 未包含查询 S1。SFENCE 首次到 WB 的拍 T，后端同时拉前端 kill 并看到 `drained && mmuIdle`，`sfence.valid` 在 T 发出，而 T−1 拍收下的取指查询仍在 iTLB S1，触发 `sfence requires a drained TLB`。被 kill 的 S1 目前没有副作用，但这依赖 kill 与 sfence 严格同拍等巧合；不变式“作废那一拍 TLB 内无在途查询”写进 `idle` 定义。

- 冻结 [`breeze-mmu-rtl-spec.md`](../breeze-mmu-rtl-spec.md) §4.4 修订：`idle` 增加“两侧查询 S1 为空”。冻结 hash 已由 Claude 重新登记（`tools/frozen.json`）。
- 后端 T20 条文与测试不变；“第一个 `drained && mmu.idle` 拍”按新 idle 计算。效果：上述场景 sfence 晚 1 拍（T+1），kill 在 T 已清掉 S1、`block` 挡住新请求。
- 不采用：后端在 kill 后加固定等待拍（把 TLB 流水深度泄露进 T20）；放松 TLB 断言。

### R2：`rv64mi-p-breakpoint` 访问 `tselect`

不实现 trigger。`tselect`/`tdata1`/`tdata2` 仅 M 态可访问、读 0、写忽略；`tdata1.type == 0` 即“无 trigger”，程序自行跳到 pass。依据 Debug spec trigger 模块可选，记录于 [`m-mode-implementation.md`](../m-mode-implementation.md) Debug Trigger CSRs。不加入 `expectedUnsupported`：`breakpoint` 必须 tohost=1。

## 2. 本提交的改动

| 文件 | 改动 |
| --- | --- |
| `design/src/main/scala/mmu/sv39/Sv39Tlb.scala` | `io.idle` 加 `&& !s1Valid` |
| `design/src/main/scala/core/common.scala` | `CSRMAP.tselect/tdata1/tdata2` = 0x7a0/0x7a1/0x7a2 |
| `design/src/main/scala/core/RegFile.scala` | `otherReads` 加三地址共享常 0 项；写口无对应 `is`，写忽略 |
| `design/src/test/scala/mmu/sv39/Sv39MmuSpec.scala` | T18：I/D 各一次，请求在 S1 时 `idle == 0`，同拍 kill 抑制 resp 且 idle 仍 0，下一拍 idle 1 |
| `design/src/test/scala/core/RegFileCsrFileSpec.scala` | 三个 trigger CSR：M 态 RW 不非法、读 0、提交写全 1 后仍读 0 |
| `docs/breeze-mmu-rtl-spec.md`（冻结）、`docs/breeze-mmu-validation.md`、`docs/m-mode-implementation.md`、`tools/frozen.json` | 上述裁定与 T18 |

## 3. 运行

```sh
python3 tools/frozen_check.py                 # 必须 OK (7 files)
cd design
sbt "testOnly flow.mmu.sv39.Sv39MmuSpec flow.core.CSRFileSpec flow.core.RegFileSpec flow.backend.BackendContractSpec"
sbt "testOnly flow.config.BreezeCoreConfigSpec flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
sbt "testOnly flow.memsys.L1DL2SystemSpec"
sbt "testOnly flow.cluster.ClusterIsaSpec"
sbt "testOnly flow.cluster.ClusterProgramSpec"
sbt test
```

ELF 构建同 [`CLUSTER-sim-tests.md`](CLUSTER-sim-tests.md) §3；suite 的包名以源码为准，名字不对直接改命令。第一条是本次改动的直接门槛；之后顺序与停止条件同原任务：ISA 全过才跑 `ClusterProgramSpec`，再跑全量。

期望：`rv64mi-p-illegal`、`rv64si-p-dirty`、`rv64si-p-icache-alias`、`rv64mi-p-breakpoint` 转为 PASS，首轮已通过的程序不回退。

## 4. 规则

同 [`CLUSTER-sim-tests.md`](CLUSTER-sim-tests.md) §5，包括冻结文件不改、触及冻结契约即停下报告。补充：

1. 若 R1 程序在新 idle 下仍断言或出现 SFENCE 后停顿（看门狗），先确认 kill 后 S1 是否在 T+1 清空、`block` 是否挡住 `FetchTlbClient` 新请求，再给最小复现；不得把断言改弱。
2. `ClusterProgramSpec` 和全量回归首次运行，预期仍会暴露新问题，按原任务 §5 修与报告。

## 5. 报告

追加到 [`CLUSTER-sim-tests-report.md`](CLUSTER-sim-tests-report.md) 新节“第二轮”：被测 SHA、实际主机、各命令通过数/总数与 exit、R1/R2 四个程序结果与周期数、新发现问题的根因与修复、仍失败或未运行的项。首轮内容保留。
