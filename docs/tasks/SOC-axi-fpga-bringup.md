# SOC：集群 AXI 直连 LiteX/LiteDRAM，冒烟仿真与 KCU105 构建（交给 codex 实现、跑和修）

起点：分支 `feat/v1-soc` 上包含本文件的提交（基于 `fe3d9b1`）。本地工作区 `/home/chen/flow-soc`；不要在 `/home/chen/leisure/flow` 主工作区切分支，那边有另一个任务（CLUSTER 第二轮回归）在用。

冻结合同：[`cluster-soc-rtl-spec.md`](../cluster-soc-rtl-spec.md)（已登记进 `tools/frozen.json`）。本任务由 codex **自己实现** RTL、LiteX 包装和测试，并负责跑和修；实现细节自行决定，在报告中写明。

背景：用户不做 OpenSBI/Linux 仿真，直接上板用 ILA 调；上板前只要分钟级冒烟仿真查连线。FASE、DMA/SD、PCIe 本轮不做。

## 1. 工作项（按顺序）

1. **Chisel 顶层与生成器**（spec §2）：`BreezeClusterAxi`、生成器参数与 marker；删除 Wishbone 桥/DMA Wishbone 客户端和 `BreezeClusterWishbone`；改写或删除引用它们的测试，并在报告中逐条列出。加一个 elaboration 测试：single、small 各自生成通过，debug 版引出 §4 信号、生产版不引出。
2. **挂死检测**（spec §4 H1–H3）：Chisel 实现，加小的 directed 测试（通道卡住、无退休、超时各触发一次；正常流量不触发；不影响握手）。阈值测试用小值参数。
3. **SoC 路由器**（spec §3 R1–R4）：可用 Migen/LiteX 写在 `litex_wrapper/flow/`，也可用 Chisel；加测试覆盖：两路交替读时 R 顺序与 AR 顺序一致、W 跟随 AW、DECERR 不挂死、错误 resp 原样返回。LiteX 侧测试放在 `sim/litex/test_*.py` 现有风格中。
4. **LiteX 包装**：`litex_wrapper/flow/core.py`、`fpga/kcu105/target.py`、`flow/ila.py`、`sim/litex/multicore_sim.py` 改为 AXI；删除 FASE/Dma 产品类与 `--with-fase`/DMA/SD/PCIe 在本目标中的入口（代码可删，旧版在 git 历史和 tag `breeze-old-linux-20261006`）。更新 `sim/litex/test_breeze_cpu_wrapper.py`、`test_breeze_ila.py` 等受影响的包装测试。
5. **冒烟仿真**（spec §5 S1–S3）：single、small。
6. **FPGA 构建**（spec §6）：Alan 上 Vivado，三个构建按顺序。
7. 更新 `fpga/kcu105/README.md`（新生成命令、AXI 路由、ILA 探针、挂死检测用法）和 `docs/v1-impl-plan.md` 第 3 节进度。

## 2. 运行与主机

仿真、sbt、RTL 生成：每次开始前重新读取 `docs/cross-project/simulation-host.md`，先预检 `cloud_chen`，不可用再 Alan；独立工作区和 evidence 目录（例如 `<workspace_root>/flow-soc-<日期>`、`<evidence_root>/soc-<SHA短>`），不要碰 CLUSTER 第二轮正在用的 `/home/cloud_chen/work/flow-cluster-20261007` 与其 evidence 目录，也不要停掉那边的后台队列。代码经 GitHub 推送 `feat/v1-soc` 同步。Vivado 在 Alan。

门槛（每个提交给出 SHA；名字以源码为准）：

```sh
python3 tools/frozen_check.py      # 必须 OK (8 files)
cd design
sbt "testOnly <本任务新增/改写的 suite>"          # 直接门槛
sbt "testOnly flow.memsys.L1DL2SystemSpec flow.cluster.ClusterIsaSpec"   # 集群未被改坏
sbt test                                           # 全量，与 CLUSTER 第二轮全量结果对比，不得新增失败
cd .. && python -m pytest sim/litex -q             # LiteX 包装测试（环境不具备的项写明原因）
```

全量 `sbt test` 跑一次即可（放在冒烟仿真前）；之后只改 LiteX/Vivado 侧时不必重跑全量，但改了任何 `design/` 代码就要重跑直接门槛和集群门槛。

## 3. 规则

1. 冻结文件（`tools/frozen.json` 所列，包括本 spec）不改；实现与 spec 冲突、或发现 spec 不可实现时，停止该项并在报告中写明冲突与建议，继续做不受影响的工作项。
2. 不改测试期望、断言、看门狗来凑通过；不删除失败用例（第 1 项明确要求删除的 Wishbone 相关用例除外，必须逐条列出）。修根因，不追求最小 diff；每个改动在报告中写明。
3. spec C1：核/L1D/L2/MMU 的 bug 单独提交修复并列出证据；不在 SoC 侧掩盖。CLUSTER 第二轮在主工作区可能也会修同一处，冲突时以 `feat/pcie-fase-20260920` 上的修复为准，本分支 merge 进来，不重复修。
4. 测试失败不是主机不可用；保留失败证据。
5. 不做形式化；不跑 OpenSBI/Linux；不烧板。
6. 提交信息写清楚做了什么；不要把 bitstream、Vivado 工程推进 git。

## 4. 停止条件

- 正常结束：第 6 项三个构建完成（时序不满足也算完成，按 spec §6 报告）。
- 提前停止：冻结合同冲突且阻塞后续全部工作项；或两台主机都不可用（写明原因）。其他问题记录后继续能做的项。

## 5. 报告

写入 `docs/tasks/SOC-axi-fpga-bringup-report.md`：

- 每个工作项：做了什么、关键设计选择（路由器结构、R1 的实现方式、挂死阈值、探针总位宽与采样深度）、文件列表；
- 删除/改写的测试逐条列出；
- 各门槛：SHA、实际主机、cwd、命令、通过数/总数、exit、日志路径；
- 冒烟仿真：两个 profile 的 UART 关键输出、memtest 结果、墙钟时间；
- Vivado：三个构建的 WNS/TNS/WHS、利用率、最差路径摘要、bitstream/`.ltx` 路径（Alan 上的绝对路径）；
- 发现的核/内存系统 bug 与修复；未完成或未运行的项和原因；
- 上板第一步建议（用户执行）：先烧哪个 bit、看哪些 UART 输出、ILA 先设什么触发。
