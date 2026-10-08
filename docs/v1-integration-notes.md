# Breeze v1 集成备忘：现有代码事实与集成决定

> 2026-10-08 SOC-3b 适用说明：本文保留旧 T01/B01/集成基线。普通 WB 当拍写口、late.fire 等同 RF 写回、6 次冲突、S2 load bypass、fatal 接收当拍生效等旧后端条款，已由用户最新裁定覆盖。当前后端实现以 [`backend-timing-contract.md`](backend-timing-contract.md) §4、[`backend-v1-rtl-spec.md`](backend-v1-rtl-spec.md) 和 [`SOC-3b 任务`](tasks/SOC-3b-wb-split.md) 为准；未覆盖的协议/安全规则沿用。W2 无条件完成、不碰 busy，后台来源为 lateReg/DIV/MUL/FPU；FENCE.I/SFENCE 不额外等待 W2/lateReg。硬件执行主机以每次重读的共享 simulation-host.md 为准，不能沿用本文历史 Alan-only 规则。

2026-10-06 由三路只读代码梳理得出（HEAD `9f0b23b`），供各 RTL spec 引用。“决定”一栏是 v1 的处理方式，写 spec 时以此为准。路径相对 `design/src/main/scala/`。

## 1. 现有结构（将被覆盖）

| 位置 | 现状 |
| --- | --- |
| `core/BreezeCore.scala` | frontend + FetchBuffer + backend；`enableMmu` 时用旧 `BreezeMmu`（16 项全相联 TLB，自带 PMP、Svadu A/D 更新）+ `BreezeDataTranslator`（阻塞翻译后发物理请求）；PTW 与数据请求在核心内单口复用，`physicalBusy` 一笔在途 |
| 后端访存 | MEM 级发 `BackendMemReq`（脉冲、无 ready、一笔在途），`pipelineHold` 停整条流水等 `BackendMemResp`；D$ 返回对齐的 64 bit 字，后端自己移位/符号扩展；SC 结果 0/1 且 `isWrite=0`；页错误要求 `error=1 && pageFault=1`；tval 用后端自己的 VA |
| FENCE | 译为 NOP（依赖一笔在途） |
| FENCE.I | MEM 级发 `dcacheFlushReq` 脉冲，D$ 全部写回并失效，`flushDone` 后 I$ 清 valid、重定向。已知隐患：D$ 在 Idle 优先处理 probe 时会丢 flush 脉冲 |
| SFENCE.VMA | EX 直接发脉冲并重定向，不等 MMU 空闲（不满足 Sv39 MMU 合同 C4） |
| `cache/BreezeDCache.scala` | 阻塞 8 KiB/4 路/32 B，PIPT（VIPT 预读快照），MESI，PMA 在内、无 PMP；MMIO 经 `DCacheMemReqIO` 脉冲 → `BreezeMmioArbiter` → `DCacheWishboneBridge` |
| `cache/BreezeL2Home.scala`、`Coherence.scala` | 阻塞一次一事务的目录式 L2，Wishbone 下游 |
| `cache/BreezeCache.scala`（I$） | 64 set/4 路/32 B，VIPT，PMA 无 PMP；refill 为 `L1CacheMissReqIO` 脉冲 + `L1CacheMissRespIO`（256 bit 单拍、`vld` 脉冲、`error`）；`flush` 一拍清全部 valid，已发 refill 的回包在 flush 后仍被吞掉不安装；PLRU 写死 4 路 |
| `frontend/BreezeFetchTranslator.scala` | 阻塞 FSM，经旧翻译口 |
| `top/BreezeMulticoreClusterWishbone.scala` | 集群 Wishbone 顶层；生成器 `GenerateBreezeMulticoreClusterWishbone` 输出 `design/build/rtl/cluster/<profile>/<preset>[/linux]...`，`filelist.f` 含 CVFPU；`cluster-profile.txt` 被 LiteX `core.py` 校验 |
| `litex_wrapper/flow/core.py` | `_BreezeClusterCPU`：`memory_bus`、`mmio_bus` 两个 64 bit Wishbone 主口（都挂主总线），可选 `dma_bus`；io_regions CLINT 0x0200_0000、PLIC 0x0C00_0000、LiteX 0x1200_0000；产品类 `Breeze`（4 核）、`BreezeTiny`（单核）、Dma/Fase 变体 |
| `sim/litex/multicore_sim.py` | `MulticoreSimSoC`（CPU `flow_cluster`，`cluster.py`），`--with-litedram` 用 `SDRAMPHYModel`；`linux_sim.py`、`act4_linux_soc.py` 都用它 |
| `fpga/kcu105/target.py` | `breeze` CPU，LiteDRAM DDR4 2 GiB @0x8000_0000，SD 卡 DMA 与 PCIe 挂 `cpu.dma_bus` |
| PMA | `platform/BreezeMcuPlatform.scala` + `PMAChecker.scala`，数据来自 `config/breeze_mcu_platform.json`：timer 0x0200_0000、plic 0x0C00_0000、boot_rom 0x1000_0000、linux_boot_rom 0x1001_0000、sram 0x1100_0000（64 KiB）、litex_mmio 0x1200_0000、main_ram 0x8000_0000（2 GiB） |
| PMP | 唯一实现 `mmu/BreezePmpChecker.scala`（8 项生效，TOR/NA4/NAPOT），CSR 在 `BreezeMmuContext.pmpcfg/pmpaddr` |
| CSR | `core/RegFile.scala` `CSRFile`：`mmu_context`（satp、privilege、mprv、mpp、sum、mxr、adue、pmp）、`fp_enabled`、`frm`、`fp_commit_valid/fp_flags` |
| 仿真 | `sim/BreezeCoreSimSupport.scala` 直接驱动核心的 `nextLevelRsp` 与 `dmem.rsp`（标量内存模型），约 30 个核心级 spec 依赖它 |

## 2. 新 Sv39 MMU 合同要点（`mmu/sv39/`，已冻结）

- `TlbPortIO`：`req` Decoupled{vaddr, cmd}，`resp` Valid（req.fire 后一拍，无 ready），`kill`。miss 后 ready 拉低直到 walk 完成，请求方**必须重发同一请求**；miss 同拍被接收的下一笔被丢弃（`dropS1Next`），请求方要重放；walk 出错时下一笔必须是同一 VPN，得到 fault。
- `PtwMemIO`：req Decoupled{paddr[56]}（ready 前保持），resp Valid{data, accessFault}（单拍，不反压）。
- `MmuCsrIO`：sv39、asid、rootPpn、priv、mprv、mpp、sum、mxr；**无 PMP、无 A/D 更新（Svade）**。
- `SfenceIO`：valid、rs1Nz、rs2Nz、vaddr、asid；要求 MMU `idle` 时才发、不能连发。
- 检查清单 C1–C11（`docs/breeze-mmu-closure-checklist.md`）。

## 3. 集成决定

| 项 | 决定 |
| --- | --- |
| MMU | 换成 `Sv39Mmu`，删除旧 `BreezeMmu`、`BreezeDataTranslator`、`BreezeFetchTranslator`。 |
| A/D 位 | Svade：硬件不更新 A/D，`menvcfg.ADUE` 改为只读 0；设备树 ISA 串加 `svade`。 |
| PMP | 数据访问：L1D S2 用最终 PA 与有效特权（含 MPRV）检查；PTW 读：L1D 以 S 特权检查；取指：L1I 在命中判断时检查。复用 `BreezePmpChecker`。 |
| PMA | L1D S2、L1I 命中判断各一份 `PMAChecker`；PA ≥ 2^32 判为不存在（access fault）。`breeze_mcu_platform.json` 每区域显式增加 `mainMemory`、`amo`（`arithmetic`/`none`）、`reservability`（`eventual`/`none`），`PMAChecker` 增加 `amoOk`、`rsrvOk` 输出：main_ram、sram = main memory、可缓存、AMOArithmetic、RsrvEventual；boot_rom、linux_boot_rom = I/O（只读 ROM，可缓存，特权规范允许只读区缓存）、AMONone、RsrvNone；timer、plic、litex_mmio = I/O、不可缓存、AMONone、RsrvNone。 |
| Ziccrse / Ziccamoa | 设计按两者实现（无新指令，只是主存属性承诺）。设备树 `riscv,isa` 加 `_ziccrse_ziccamoa` 的时间点：4 核 LR/SC 争用压力测试（含 watchdog）与 main_ram/sram 全部 AMO 测试通过之后。 |
| FENCE.I | 不再需要 D$ 写回：L1I 经 L2 一致性 Read 取最新数据（L2 对 owner 发 Down）。后端在 WB 等 L1D 的 MSHR 与 pending-store 为空后清 L1I 并重定向；删除 `dcacheFlushReq/Done`。 |
| FENCE | 作为 L1D 请求，MSHR 与 pending-store 为空时完成。 |
| SFENCE.VMA | 改在 WB 执行的串行指令，按 C4：更老指令按序提交、前端已被 kill（已提交后台结果可继续） → 等 L1D MSHR/pending-store 空 → 等 MMU idle → 一拍 sfence → 等 idle → 重定向到下一条。 |
| 物理地址 | 缓存与协议内部 32 bit；MMU 输出 56 bit，超出部分由 PMA 拒绝。 |
| CVFPU | 输出被反压时各级保持、不丢结果；`tag` 贯穿所有单元（含 THMULTI DIVSQRT）；乱序跨单元返回；`flush_i` 清全部在途；`in_ready_o` 组合依赖 `in_valid_i`、`op_i/dst_fmt_i` 和 `out_ready_i`；`result_o` 在 `out_valid&&!out_ready` 时可能变化（只在 fire 时采样）。 |
| FASE | 本轮不适配新内存系统：`useFASE` 路径保留编译所需的接口但 v1 新核心不支持 `useFASE=true`（生成时 require 为 false）；FASE 适配以后单独做。 |
| 旧核心级仿真 | `BreezeCoreSimSupport` 的标量内存模型改为驱动新核心的 L1D/L1I 下游（REQ/RSP 链路）或直接用单核集群 + AXI 内存模型，见 `cluster-soc-rtl-spec.md`。 |
