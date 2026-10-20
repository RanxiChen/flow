# 每核 MMU 独立迁移

范围：按用户 2026-10-19 指示，仅实施 R1；prefetch 留到下一次。依据 `docs/plans/2026-10-19-breeze-core-refactor.md` 和既有 MMU RTL spec；不改 MMU 内部算法、流水或寄存器。

## 边界与验收映射

| 要求 | 实现映射 | 验证入口 | 当前证据 |
|---|---|---|---|
| 生产 frontend 无 MMU，每核独立 iTLB/dTLB + shared PTW | Cluster/RvvSystem 每核创建 Sv39Mmu，frontend 仅 iTLB | MmuExtractionElabSpec、生产系统程序 | 已通过，见下表与 XML |
| 保持翻译/权限/跨页 RVC 与 kill/旧响应行为 | iTLB 原样外接，PA candidates/select/kill 全连接 | BreezeFrontendPipelineSpec、L1IRegisteredReturnSpec、Sv39MmuSpec | 已通过，见下表与 XML |
| CSR 当前值直连，restart/sfence 原寄存边界不变 | Sv39MmuContextLink、BreezePipelineLink | ClusterProgramSpec 的 Sv39/SFENCE/fence.i/多核/AXI 反压 | 已通过，见下表与 XML |
| PTW 经 L1D，D-side kill/反压/前进规则不变 | L1D ptw/tlb 直接接每核 MMU | ClusterProgramSpec、MMU 模块回归 | 已通过，见下表与 XML |
| 标量/Zve/RVV dTLB 共享与 idle 协调保留 | ZveTlbShare/RvvTlbArbiter、mmu idle 合并 | ZveSystemSmokeSpec、RvvSystemSpec | 已通过，见下表与 XML |
| 预取仍关闭 | L1IClient prefetch.valid 继续 false | 源码审查 | 已核对 |

frontend `idle` 现在只表示本地 cache/queue/carry 排空；frontend+MMU 独立包装器和前端 L2 top 显式合并 MMU idle。生产后端继续观察 MMU 与向量翻译的 idle，与迁移前相同。

## 执行证据

实际执行主机为 Alan（`chen@localhost:2286 via clawbot`）：首选 `cloud_chen@47.96.71.231:22` 预检连接超时；Alan 环境和资源可用。Java 11.0.32.1、Verilator 5.028、sbt launcher 1.9.7。

源码身份为基线 `f058e3bc7292973a41cd3a766e8d872db2571357` 加本任务精确文件覆盖（未提交工作区），不是声称该 HEAD 已含本次改动。源归档/补充归档的 SHA256、每个覆盖文件哈希、子模块身份见 `records/mmu-extraction-20261019/source-manifest.json`。

- 远端源码 cwd：`/home/chen/FUN/flow-mmu-extraction-20261019-f058e3b/design`。
- 远端证据：`/home/chen/FUN/flow-runs/mmu-extraction-20261019-f058e3b`。
- 本地命令与证据：`records/mmu-extraction-20261019/`。`run.sh` 编译主源码和测试源码；`test.sh` 运行模块与边界检查；`system.sh` 从快照重建 ELF 并执行生产系统回归。
- 快照的主/测试 Scala 编译 exit 0；定向回归 exit 0，6 套件、53 项通过；系统回归 exit 0，3 套件、7 项通过。合计 9 套件、60 项，failed/canceled/ignored/pending 均为 0。系统项内包含 63 次 ELF 程序运行，均通过原有签名与数据检查。没有修改 golden、seed 或 watchdog。

| 套件 | 项数 | 结果与范围 |
|---|---:|---|
| MmuExtractionElabSpec | 3 | 生产 frontend 无 MMU/PTW；前端 L2 与 timing fixture RTL elaboration |
| BreezeFrontendPipelineSpec | 28 | RVC/跨页、PMP、权限更新、sfence 重映射、kill/refill、反压、history/RAS |
| BreezeResolutionReservationSpec | 2 | 预测开/关的反馈容量与取消归属 |
| L1IRegisteredReturnSpec | 2 | 注册返回两种 flush 时点的旧数据排空 |
| Sv39MmuSpec | 17 | 翻译、权限、ASID、sfence、I/D 同时 miss、kill、随机 PTW 反压与参照 walker |
| Sv39PmpCandidateSpec | 1 | PA 候选与公开 fault 地址行为 |
| ClusterProgramSpec | 5 | 27 次程序运行：单核、SFENCE 反压、双核、四核、四核反压 |
| ZveSystemSmokeSpec | 1 | 19 次程序运行：Zve smoke、Sv39/fence、原标量 ISA |
| RvvSystemSpec | 1 | 17 次程序运行：原标量 ISA、RVV memory、Sv39 memory、arithmetic |

本轮生成日志仍有既有 frontend Vec 索引宽度 W005 与枚举转换 W001 警告；相关表达式未在本次修改。

证据归档：`records/mmu-extraction-20261019/evidence.tgz`，解包后的 `result.json`、`test-reports/`、`compile.txt`、`focused.txt`、`system.txt` 包含完整结果；`collect.py` 校验套件完整性、退出码和远端覆盖文件哈希。

模块仿真、系统程序、RTL elaboration 分开记录；没有综合、routed timing 或板上证据。
