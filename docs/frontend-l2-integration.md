# 新前端与 L2 的集成边界

2026-10-19 MMU 独立迁移：生产 `BreezeCluster`/`BreezeRvvSystem` 和独立 `BreezeFrontendL2System` 均由每核集成层实例化 `Sv39Mmu`；`BreezeFrontendPipeline` 只提供 iTLB 请求/返回及候选旁带。CSR、sfence、dTLB、PTW、MMU idle 在集成层连接，预取继续关闭。迁移范围与验证状态见 [MMU 独立迁移](tasks/MMU-extraction-20261019.md)。下文“前端内 MMU”描述历史快照。

2026-10-12 生产路径更新：`BreezeCluster` 和 `BreezeRvvSystem` 已接入 `BreezeFrontendPipeline` 与 BE2-P `BreezeBackend`。每核共享前端内的真实 Sv39Mmu，dTLB 接 L1D（RVV 配置经翻译仲裁器），PTW 经 L1D，EX 解析使用 ID 预留的四项反馈容量。生产生成入口为 `flow.top.GenerateBreezeCluster`；旧阻塞前端及其 realigner/translator/cache/predictor 已删除。详见 [新前端接入报告](tasks/BE2-new-frontend-integration-20261012.md)。下文保留 2026-10-11 独立 `BreezeFrontendL2System` 的历史阶段边界；其中“生产顶层仍是历史路径”仅描述该日快照。

2026-10-11，按用户要求先完成新前端与真实 L2 的 RTL 连接，不以旧后端适配或仿真通过作为本阶段前提。实现为 [BreezeFrontendL2System](../design/src/main/scala/top/BreezeFrontendL2System.scala)。本页规定此次接线边界；前端命中流水、权限、取消规则仍以 [frontend-pipeline-design.md](frontend-pipeline-design.md) 为准。

## 顶层与数据路径

每 hart 实例化 `BreezeFrontendPipeline` 和一个 `L1IClient`，所有 hart 共享一个 `L2Home`。模块使用同一份 `BreezeMemGeometry` 推导行宽、物理地址宽、L2 容量和端口数；默认一个 hart，32 B 行，16 KiB L1I、64 KiB L2。

```text
指令/反馈边界
    ↕
BreezeFrontendPipeline（iTLB/PTW、预测器、VIPT ICache、指令队列）
    ↕ refillReq / refillRsp
L1IClient（需求 id=0；预取关闭）
    ↕ Read / ReadData
共享 L2Home ← 外部 L1D 一致性端口、DMA
    ↕ AXI4
下级内存
```

L1I miss 现在直接经过 `L1IClient` 送入真实 `L2Home`，不再由这个集成顶层的外部 refill 驱动器响应。L2 命中或下级 AXI 读完成后，完整行及错误状态经该 client 返回新 VIPT ICache。

`BreezeCluster` 和现有 SoC 生成入口仍是历史核心路径。新集成入口为 `flow.top.GenerateBreezeFrontendL2System`，它生成新前端/L2 顶层，不实例化旧 `BreezeBackend`、`FetchBuffer` 或旧 `BreezeFrontend`。指令输出及解析/提交接口直接提供给后续新后端；本阶段不编写旧接口转换。

## 请求、返回与取消

- 每个新 ICache 同时至多一个需求 refill。`refillReq.req` 是单周期脉冲；`L1IClient` 无条件锁存地址，随后保持 L2 `Read(id=0)` 的 valid/payload，直到 L2 握手。
- L2 使用现有只读 client 协议：Read 不登记 L1I 为 owner/sharer；遇 L1D 的 UNIQUE owner，仍经真实 probe 获取最新数据，不绕过一致性。
- `L1IClient` 无条件接收 `ReadData`，按 id 核对归属，注册完整行和 error，下一拍向 ICache 返回一次。高 PA 拒绝继续沿用 client 的 error 处理；不截断后静默访问低地址。
- redirect、分支恢复、fence.i 都不取消 client/L2 中的 refill 事务。新 ICache 保留等待归属直到返回，用原有 killed/fence.i 规则决定丢弃或安装，避免老返回进入新请求。
- 不增加命中级数，不改 F0/F1/F2、MMU 或 L2 状态机。L2 反压只影响 miss/refill 路径，命中仍由新前端本地阵列处理。
- client 的预取端口固定关闭，id=1 不产生事务；本阶段没有下一行预取、预取安装或多个需求 miss。

## 外部接口与后续接入

| 接口 | 此阶段含义 |
|---|---|
| `harts(h).instruction` | 完整单条指令 Decoupled 输出，由消费者提供 ready |
| `harts(h).resolve` / `committedBranch` | 沿用新前端的解析与提交反馈语义 |
| `harts(h).context/resetPc/block/redirect/fenceI/sfence` | 核心提供上下文、取指启动与串行恢复控制 |
| `harts(h).ptwMem` | 外部物理 PTE 读接口，后续接 D-cache 的 PTW 通道；本次没有绕过 D-cache 直接读 L2 |
| `harts(h).idle` | 仅该前端本地 idle，不代表共享 L2 或外部 client 已排空 |
| `l1d(h)` | 完整四链路一致性端口，供后续真实 L1D 接入，保留 probe/返回反压 |
| `dma` | 现有不登记的 Read/MaskWrite client 入口 |
| `mem` | 真实 L2 下级 AXI4 主口，需外部内存系统响应 |
| `l2Events` | 共享 L2 的现有事件计数接口 |

外部未使用 L1D/DMA 时仍须驱动其请求为无效，并正确处理返回及 probe 接口，不能悬空。若 L1D 发出事务并成为 owner，它必须履行后续 probe 响应责任。启用 Sv39 后必须有人响应 `ptwMem`；本次并未完成 MMU→D-cache 页表读闭环。前端内的 dTLB 仍处于原独立实现的停用状态，后续共享 MMU 接入属于数据侧集成。

## 检查与证据范围

本阶段只完成源码连接、接口/归属静态审查、冻结文件及 diff 空白检查。按用户要求不运行 sbt 编译、Chisel elaboration、RTL 生成、仿真或 Vivado，也不新增通过结论。

静态核对：所有前端功能接口均已连接（debug 留在模块内部），每 hart 的边界字段均有接线，真实 L2 Read/AXI、外部 PTW/L1D 链路存在；新增文档中的本地链接可解析。`git diff --check` 通过。这些检查不验证 Scala 类型、Chisel 方向/组合环或运行时协议。

`python3 tools/frozen_check.py` exit=1：`docs/breeze-mmu-rtl-spec.md` 的冻结清单记录为 `a2a279f53406ca7f089dfe1025b6d58c63a1369e8643d3b441152c054b1c698e`，当前为 `c9ee847c1e25cdb5de6fc2a7264929fd89aa4d2abc014fba992337bd5d719a84`。该当前哈希与本次起点 `8196173eecb03d7bbb3604f9bd1bbbae813ad383` 完全一致，是起点已有差异；本次全部冻结文件相对起点未变，也没有更新冻结清单。不能把该检查记录为通过。

已有独立前端的 62/62 测试及 100 MHz routed 证据仍仅属于之前的前端快照；不覆盖新增集成顶层、L1I→L2→AXI 闭环、后端或 SoC。本顶层的编译、闭环功能和时序验证待后续执行。
