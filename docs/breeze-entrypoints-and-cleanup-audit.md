# Breeze 源码入口与第一轮清理

核对日期：2026-10-19。目标：同一套源码保留纯标量和 RVV 配置，明确生成入口、硬件顶层和模块边界，再逐批删除无引用实现。

## 1. 唯一工作区与整合前记录

当前唯一开发工作区为 `/home/chen/leisure/flow`，使用 `main`。当前公开配置为纯标量 `single` / `small` 和 `single-zve32f`，固定使用 GShare / Linux。旧 RVV 仅保留实现与模块测试，不再提供配置入口。清理代码对应提交 `c8eb0e152d91e3e16262b856ef5dfd647e38319d`，已 push 到 `origin/main`。

下表是整合前的核对记录，表内其他工作区已归档并移除：

| 工作区 | 分支与 HEAD | 整核使用的前端 | 定位 |
| --- | --- | --- | --- |
| `/home/chen/leisure/flow` | `timing/soc3e-pipeline` / `2051dd0bb9a0c701bb95fd3f1e755ae175023093` | `BreezeFrontend` | 较早的标量整核；新前端另有独立顶层 |
| `/home/chen/leisure/flow-be2` | `refactor/be2-backend-decoupling` / `99c7e8d9adf324de3e1e35a990832029674e2c3e` | `BreezeFrontendPipeline` | BE2、新前端整核集成及早期 RVV 路径 |
| `/home/chen/leisure/flow/.worktrees/zve32f-single` | `feat/zve32f-single-20261013` / `452937a42ecae8f8592ebc3482feec93de31ca20` | `BreezeFrontendPipeline` | 本次清理基线，保留标量、早期 RVV 和 Zve32f 配置 |

已用 `git merge-base --is-ancestor` 核对：`2051dd0` 是 `99c7e8d` 的祖先，后者是 `452937a` 的祖先。整合前主目录不等于最新实现；现已将保留版本收敛到主目录的 `main`，其他工作区已备份移除。

第一轮清理最初在第三行工作区进行，随后提交并迁回主目录。该工作区原有的 8 个未跟踪 GEMV/Window 文件均已按内容散列迁回；原主目录的 Linux 改动与其他未跟踪资料也保留。

## 2. 顶层与生成入口

统一生成入口是 [GenerateBreezeCluster.scala](../design/src/main/scala/top/GenerateBreezeCluster.scala) 中的 `flow.top.GenerateBreezeCluster`。

| profile | 用途 | 生成的 Chisel/RTL 顶层 | 内部系统连接模块 |
| --- | --- | --- | --- |
| `single` | 单核纯标量 | `BreezeClusterAxi` | `BreezeCluster` |
| `small` | 四核纯标量 | `BreezeClusterAxi` | `BreezeCluster` |
| `single-zve32f` | Zve32f 单核路径 | `BreezeClusterAxi` | `BreezeCluster`，启用 `ZveMount` / `ZveL2Client` |

profile 定义见 [config.scala](../design/src/main/scala/config/config.scala) 的 `BreezeClusterConfig` 和 `BreezeClusterPresets`。公开配置只有 `single`、`small`、`single-zve32f`；`baseline`、`mcu` 和旧 `single-rvv` 均被拒绝。旧 RVV 的 `BreezeRvvAxi` / `BreezeRvvSystem` / `RvvMount` 等源码和内部模块测试保留。配置存在不等于完整 ISA、时序或上板已经验证。

下面是源码生成命令索引，执行目录为选定工作区的 `design/`。实际编译和生成前，仍须重新读取共享 [simulation-host.md](/home/chen/leisure/flow/docs/cross-project/simulation-host.md)，校验 cloud_chen，不可用再使用 Alan。

```sh
sbt 'runMain flow.top.GenerateBreezeCluster single'
sbt 'runMain flow.top.GenerateBreezeCluster small'
sbt 'runMain flow.top.GenerateBreezeCluster single-zve32f'
```

可选参数为 `tandem`、`fpga-debug`、`coherent-dma`。不再传入预测器或特权模式的两个位置参数。`design/build.sbt` 的 `elaborate` 生成 `single`；生成目录和 marker 仍保留固定的 `gshare/linux` 标识，避免混用旧产物。

入口关系图（箭头表示生成选择与实例化层次）：

![Breeze 入口与顶层关系](figures/breeze-entrypoints/breeze-entrypoints.png)

[下载可编辑 Draw.io 文件](figures/breeze-entrypoints/breeze-entrypoints.drawio)。原生 XML 结构检查通过；预览用官方 Draw.io viewer 渲染并已目视检查。

## 3. 先从哪一层读代码

```text
生成程序：GenerateBreezeCluster（按 profile 选择硬件顶层）
  single / small / single-zve32f
    BreezeClusterAxi
      BreezeCluster
        共享：L2Home、Axi4LiteArbiter
        每 hart：BreezeFrontendPipeline、BreezeBackend、L1DCache、L1IClient
        Zve32f 配置：ZveL2Client、ZveTlbShare
      可选：CoherentDmaWishbone、BreezeHangMonitor
```

这个树只列主要实例化层次。`BreezePipelineLink` 是 Scala 接线辅助对象，不是一个额外硬件流水级：它连接前后端的指令、解析、提交和恢复信号。

纯标量与 Zve32f 复用 `BreezeCluster`：前端内部实例化 `Sv39Mmu`，其 dTLB/PTW 端口供系统连接 L1D；启用 Zve32f 时，通过 `ZveTlbShare` 共享数据翻译。后端内部按配置实例化 `ZveMount`。向量普通数据经 `ZveL2Client` 接真实 `L2Home`，不穿过标量 L1D。

早期 `single-rvv` 使用 `RvvMount`、写穿 L1D、`RvvDatalessHome` 和宽 AXI 路径。它不是 Zve32f 的 L2 连接方式。

建议分析顺序：`GenerateBreezeCluster` 的配置选择 → `BreezeClusterAxi` 的外部接口 → `BreezeCluster` 的模块接线 → `BreezeFrontendPipeline` → 后端 → L1D/MMU/L2 → Zve32f 路径。每个模块再单独补内部结构和逐拍场景。

## 4. FPGA 层次

KCU105 板级入口是 [fpga/kcu105/target.py](../fpga/kcu105/target.py) 的 `BreezeKCU105SoC`，由 LiteX/Migen 组织 DDR、外设及 CPU wrapper。CPU wrapper 位于 [litex_wrapper/flow/core.py](../litex_wrapper/flow/core.py)，加载 `filelist.f`，校验 `cluster-profile.txt` 并实例化相应 RTL 顶层。

因此要区分三个概念：生成程序 `GenerateBreezeCluster`；当前公开配置的 CPU/集群硬件顶层 `BreezeClusterAxi`；完整 FPGA 工程的板级顶层（KCU105 构建通常为 `xilinx_kcu105`，具体以该次工程为准）。

## 5. 第一轮已删除的文件

在当前主线删除以下三个旧模块；未修改任何活动实例、配置、接口或测试期望。

| 文件 | 删除依据 |
| --- | --- |
| `design/src/main/scala/bus/DCacheWishboneBridge.scala` | 当前内存主路径使用 AXI；该类及其状态枚举无其他源码、测试或构建入口引用 |
| `design/src/main/scala/bus/BreezeMmioArbiter.scala` | 当前 MMIO 使用 `Axi4LiteArbiter`；旧脉冲协议仲裁器及状态枚举无其他活动引用 |
| `design/src/main/scala/mmu/BreezeDataTranslator.scala` | 当前使用 `Sv39Mmu` 的 dTLB/PTW 接口；本基线中旧翻译适配器无其他活动引用 |

检索覆盖当前 Git 跟踪文件及相关未跟踪代码，并包括 Makefile 和 Verilator 的 `V` 前缀模块名。上述名字仍可能出现在历史文档、归档记录和旧利用率报告中；这些资料不是当前实例化关系，保留原样。

删除前的三文件原文、SHA-256、原工作区状态、删除 diff 和编译输入快照保存在本机 `/home/chen/leisure/flow/records/breeze-cleanup-20261019/`。可用 `before/` 内原文件恢复。

## 6. 保留和后续候选

| 对象 | 本轮处理与原因 |
| --- | --- |
| `BreezeFrontendPipeline`、`BreezePipelinePredictor`、`BreezeViptICache`、`BreezeCompressedDecoder` | 保留，生产前端使用 |
| `rvv/` 与 `rvv/zve/` | 保留新旧向量模块及共享依赖；只有 Zve32f 提供公开配置入口 |
| `BreezeFrontendL2System`、`BreezeFrontendTimingHarness` | 保留，独立集成/时序验证入口；不是完整 CPU 顶层 |
| `mmu/BreezeMmu.scala` | 保留，旧 MMU 仍被 `BreezeMmuSpec`、`BreezeMmuAdSpec`、`BreezePmpSharingSpec` 引用；退役需连同测试职责一起审查 |
| `core/`、`interface/`、`cache/` | 保留，目录名旧但仍包含活动后端、CSR、译码、PMP、AMO 等共享定义 |
| `fase/`、`wishbone/` | 保留，独立控制器、记录器或 UART/Wishbone 工程仍有引用 |
| `hello/` | 保留为下一轮成组退役候选，`sim/simple/Makefile`、`sim/simple/sim_hellomem.cpp`、`sim/datapath/` 仍引用例程 |
| Air、Wisp、matrix 及其 FPGA 工程 | 独立产品/实验，未进入本次 Breeze 清理范围 |
| `records/`、旧 `.bit`/`.ltx`/报告、其他 worktree | 保留，既有证据和工作区不因模块未进入当前配置而删除 |

整合前主目录 `2051dd0` 中仍存在的旧前端文件，包括 `BreezeFrontend`、`BreezeBTB`、`BreezePHT`、`BreezeFetchTranslator`、`BreezeInstrRealigner`、`BreezeFrontendBoundary`，以及 `FetchBuffer`、`FetchTlbClient`、旧 `L1ICache`，在 `99c7e8d` 后的基线已经删除。这是版本替换的记录；当前主目录已采用新主线，不再使用这些旧前端文件。

## 7. 文档过期点与验证范围

整合前主目录 `2051dd0` 的 `design/build.sbt` 指向不存在的 `GenerateBreezeCoreWishbone`；其 KCU105 README 使用不存在的 `GenerateBreezeMulticoreClusterWishbone`，根 README 也仍链接不存在的 `core/BreezeCore.scala`。这些是过期入口。当前任务用上述源代码索引替代它们；历史发布镜像的说明与验证结论不迁移到新源码。

本报告的层次和候选判定来自静态源码核对。共扫描 570 个代码与构建文件，已删除的三个类及两个状态枚举均无剩余引用。生成入口、两套系统顶层、后端、配置及 LiteX/KCU105 接线文件与基线逐字节一致。

第一轮验证于 Alan 执行：

- cloud_chen `47.96.71.231:22` SSH 连接超时（exit 255），按当次读取的共享配置回退到 Alan，`chen@localhost:2286` 经 `clawbot`。
- 独立 cwd：`/home/chen/FUN/flow-cleanup-20261019-90bd6f35/design`。
- 输入：`452937a` 工作区删除后的源码快照，包含原有未跟踪的 `FlowZveGemvSpec.scala`；源码归档 SHA-256 为 `90bd6f35406c04a27bb52c0debb49be7adb230cd57d5b3988e698b0a9d116671`。清理后的未提交状态不能只用基线 SHA 表示。
- 实际工具：Java `11.0.32.1`、项目指定 sbt `1.9.7`。Alan 原有 sbt wrapper 指向缺失文件，首次启动 exit 127；随后在本任务 evidence 目录下载 Maven launcher，经恢复的反向代理运行，不修改全局工具配置。
- 命令的 sbt 任务：`Compile / compile`、`Test / compile`、`testOnly flow.config.BreezeCoreConfigSpec`。完整 Java 命令与 launcher 散列见本地 evidence。
- 结果：138 个主源码文件编译通过；113 个测试源码文件编译通过；配置测试 9/9，通过、失败/取消/忽略/pending 均为 0；最终 exit 0。`ClusterIsaSpec.scala:59` 有一个外部引用类型检查警告。
- Alan evidence：`/home/chen/FUN/flow-runs/breeze-cleanup-20261019-90bd6f35/`；日志已取回本地 `records/breeze-cleanup-20261019/alan-logs.tgz`。

这些结果覆盖删除后的编译依赖和配置测试，不是四种产品的 RTL 功能回归。未执行 RTL 生成、Vivado、bitstream 生成或上板验证。清理代码和图文已提交并 push。随后工作区整合只改变目录布局与本文记录，没有增加硬件代码改动。


## 8. 工作区收敛记录

2026-10-19，按用户“push，把其他工作区删了，就留这一个”的要求完成：

- 唯一 Git 工作区：`/home/chen/leisure/flow`，分支 `main`。`git worktree list --porcelain` 已核对只有这一项。
- 已移除 11 个 linked worktree 和 2 个独立源码副本，`.worktrees/` 目录已移除。两份独立副本为 `pipeline-closure-20261012` 与 `single-full-20261012`，它们此前没有登记在主仓库的 worktree 列表中。
- 每个移除目录均先完整归档（包括未提交、未跟踪、忽略文件和其中独立 Git 数据），逐文件验证归档内容，并在删除前再次核对文件散列和活动进程引用。
- 主仓库全部 refs 已另存并验证 Git bundle；CVFPU 也有独立 bundle。保留所有本地历史分支，不删除远端分支。
- 原主目录的 Linux 修改已恢复为未提交状态；Buildroot 配置同时保留新主线的 `flow-coremark` 与用户原有的 `flow-llama` 条目。
- CVFPU 已在主目录切到匹配主线的 `d0ed6e548bfd298a03f2f8c62e610a5fd1afa91e`；共享仿真主机配置内容与整合前一致。

仓库外备份目录：`/home/chen/leisure/flow-worktree-archives/20261019T045214Z`。目录内 `inventory-before.json`、`verified-archives.json`、各目录 `.files.json`、`migration.json` 和 `removals.json` 记录来源、SHA、文件散列、迁移及删除结果。压缩归档是恢复资料，不是保留的开发工作区。

本次交付摘要与编译证据索引见 [delivery-summary.json](../records/breeze-cleanup-20261019/delivery-summary.json)。


## 9. 公开配置简化

2026-10-19，按用户要求关闭旧 RVV、baseline 和 MCU 的配置入口。`BreezeBuildOptions` 统一解析三个公开 profile 和三个可选功能，生产生成器只实例化 `BreezeClusterAxi`。GShare 和 Linux 为固定默认；省略预测器、特权位置参数。旧位置参数写法会报错，应使用第 2 节新命令。

Scala 命名配置注册表、KCU105 CPU 菜单、SD 硬件/软件构建与打包菜单、旧仿真 wrapper 的可选值一并收敛。旧 RVV 实现保留；机器态 CSR 的隔离测试参数保持内部可用，不在命名配置中注册。无预测器的模块级参数同样保留，`baseline` 不再是命名构建 preset。

本轮验证在 Alan 的独立目录执行：139 个主源码、114 个测试源码编译通过；Scala 配置测试 14/14、Python wrapper/公开选项/runner 测试 23/23 通过。旧选项在创建硬件输出前被拒绝，Zve32f 收集器仍拒绝基础配置或身份不匹配的产物。旧 RVV 模块逐文件核对未修改，图的原生结构和渲染检查通过。未运行 RTL 功能仿真、RTL 生成或 Vivado。验证输入散列、命令及原始结果见 [本轮验证记录](../records/config-interface-20261019/result.json)。
