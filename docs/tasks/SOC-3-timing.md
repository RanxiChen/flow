# SOC-3：L1D 权限检查前移到 S1、乘法器寄存器重排，tiny 100 MHz 时序收敛（交给 codex 实现、跑和修）

起点：`feat/pcie-fase-20260920` 上包含本文件的提交（`bd48477` 之后）。在主工作区 `/home/chen/leisure/flow` 的这个分支上工作。

前情：[`SOC-2-bram-report.md`](SOC-2-bram-report.md)。tiny 生产版 100 MHz routed **WNS=-6.112 ns / TNS=-201377 ns**，49130 个 setup 失败端点。worst-100 只有两个路径族，起点都是 `l1d/internal2_valid_reg_replica_17`：

| 终点 | slack | 说明 |
| --- | ---: | --- |
| `backend/mulUnit/product_0_reg/DSP_OUTPUT_INST/ALU_OUT[*]` | -6.112 | 到 DSP A 输入前约 10.8 ns，65×65 乘法本身约 4.8 ns |
| `frontend/realigner/state_reg[*]/CE` | -6.107 | **不经过乘法器** |

路径：`internal2.valid`（`L1DCache.scala:271` 的 `s2 := Mux(internal2.valid, internal2, cpu2)` 选择）→ `BreezePmpChecker`（65 bit `accessLast` 加法 + 边界比较，两段 CARRY8）→ PMA → `permissionFault` → outcome / miss 分配 → `cpuHold`/`s2Hold` → 后端 scoreboard → 前端 CE 与乘法器操作数。只改乘法器只能消掉第一族，WNS 仍约 -6.1 ns；两项都必须做。

`MulUnit.scala:45` 在 E 拍组合完成整个 `a * b`，其后三级只搬运结果；实测 16 个乘法 DSP 的 AREG/BREG/MREG 全为 0。

本任务由 codex 实现、跑和修。下文「裁定」是冻结的设计决定，不得为通过测试改变；冲突时停止该项并报告。

## 1. 裁定

### M1：L1D 的 PMP/PMA 检查在 S1 算完，结果随 S1→S2 寄存

- 对 cpu 与 internal 两条 S1→S2 通路（`L1DCache.scala` 中 `when(cpuAdvance)` / `when(internalAdvance)` 两处），**各自**在写入 `cpu2` / `internal2` 时，用将写入的 `physicalAddress`、size、访问类型和有效特权级计算 PMP 与 PMA，把结果寄存进 `L1S2`。新增字段至少覆盖 S2 现在用到的所有 PMP/PMA 派生量：`pmpAllowed`、PMA 的 `allowed/device/amoOk/rsrvOk`、`highAddress`；`atomicDenied`、`misaligned` 可以同样前移（codex 决定）。
- S2 中**删除** `pma`/`pmp` 对 `s2.physicalAddress` 的组合检查；`permissionFault`、`atomicDenied`、device 路由等只读取寄存的位。S2 的 outcome 判定规则（`l1d-rtl-spec.md` §5 的优先级与结果）**完全不变**：同一请求在同一上下文下的 outcome、resp、MSHR 分配、MMIO 路由与改动前逐拍一致。
- 两条通路各用一份 checker，不得在 checker 前面再按 `internal2.valid` 选择地址。`recheckReturns` 时 `cpu2 := internal2` 连同新字段一起搬。
- PTW 来源仍用 S 特权、Load、8 B（`l1d-rtl-spec.md` §8）；Replay 仍沿用「已检查」语义，不重新判定。
- **上下文一致性**：S2 使用的寄存结果必须等于「按 S2 当拍 CSR 上下文（pmpcfg/pmpaddr、privilege、mstatus.MPRV/MPP）重新计算」的结果。codex 先核对后端串行规则（`backend-v1-rtl-spec.md` 中 CSR 等空与 WB 串行条款）是否已保证这些 CSR 或特权级改变时 S1/S2 中不存在会使用旧结果的有效项（含 cpu 保持项、Recheck、PTW 内部项）。
  - 已保证：在报告中写出依据（条款与 RTL 位置），不加机制。
  - 不能完全保证：上下文改变当拍把受影响的有效 S1/S2 项标记为重新检查（cpu 项走现有 `needsRecheck`→`Recheck` 路径；内部项的处理由 codex 定，但不得改变其对外协议），并在报告中说明。
  - 两种情况都要加一个**仅仿真的影子检查**：S2 中按当拍上下文组合重算 PMP/PMA，与寄存结果比较，不一致时断言失败。影子检查必须随断言开关在 FPGA 综合版本中消失；报告中给出综合网表里没有影子 checker 的证据（层级利用率或 cell 查询）。
- 允许的辅助优化（codex 决定，非必须）：CSR 写 pmpcfg/pmpaddr 时预解码出每项上下界并寄存；`accessLast` 或其 block/low 位与 paddr 一起在更早处算出。若用了预解码，预解码寄存器的更新拍与 CSR 生效拍的关系必须由上面的影子检查覆盖。
- `BreezeMmu` 内的 `sharedPmp` 不在本任务范围，除非修复后的真实时序报告显示它进入最差路径族。

### M2：乘法器 4 拍延迟不变，寄存器移进乘法内部

- 对外时序**完全不变**：`backend-timing-contract.md` T04（`gprWrite = E+4`）、P02（8 条连续）、T13、P10 以及 MDU 协议测试的期望和断言全部保持。不改 3 拍或 5 拍。
- 结构：E 末操作数寄存（DSP AREG/BREG）→ E+1 部分积（MREG）→ E+2 部分积寄存（PREG）→ E+3 fabric 中压缩求和、最终进位加与 `MUL/MULH/MULHSU/MULHU/MULW` 选择后寄存 → E+4 输出。部分积拆分与符号处理由 codex 决定（建议 64×64 无符号核 + MULH/MULHSU 修正项，约 12 个 DSP），**不得依赖** Vivado 自动推导或 `-retiming` 把寄存器推进 DSP。若 E+3 一拍放不下压缩+进位加，可把一部分压缩前移到 E+2 的 DSP 级联或 fabric，但总延迟仍为 4。
- valid/committed/kill/authorize 逻辑只作用于控制位，数据通路随 `mulEnable` 推进；现有 S04/S07/S11 等断言按新内部级等价改写（检查含义不变，不得删除或放宽）。
- `mulEnable` 依赖 `io.result.ready`。如果修复后的报告显示 `ready → DSP CE` 进入最差路径族，可以改成数据通路自由推进加出口小缓冲，但前提是 T04/T13/P02/P10 与全部 MDU 测试原样通过；做不到则停下报告，不改合同。
- 门槛：tiny 布线后，`mulUnit` 下每个 DSP48E2 的 AREG/BREG=1（或用 ACASCREG/BCASCREG 等价）、MREG=1、PREG=1。用 `records/soc2-resume-20261008/inspect-tiny-route.tcl` 同样的查询输出 `tiny-dsp-pipeline.tsv`；任一 DSP 不满足即未通过。

### M3：修复后其余路径

M1+M2 完成后跑 tiny，按真实 routed 报告重新排序。只要修复**不改**任何冻结拍数合同、L1D/后端对外协议和测试期望（纯粹在现有级内重排逻辑、复制驱动、拆分扇出、把已有寄存器边界内的计算前移），codex 可以继续修，每项写进报告（路径、根因、改法、前后 slack）。需要改拍数、改协议或改冻结文件才能修的，停下报告具体路径和建议的裁定，不自行改。

固定不变：100 MHz、xcku040-ffva1156-2-e、现有约束；**不加 false path / multicycle，不改综合或实现策略（不开 `-retiming`、不改 directive）**，不降频。

## 2. 主机

按 `AGENTS.md`：每次仿真、编译、RTL 生成前重新读取 `/home/chen/leisure/flow/docs/cross-project/simulation-host.md`，先校验 `cloud_chen`，不可用再校验并使用 Alan，两边都不可用就停止并报告具体原因。Vivado 用 Alan。独立工作区与 evidence 目录（如 `flow-soc3-<日期>`、`soc3-<SHA短>`），不覆盖 SOC-2 证据。

## 3. 门槛（按顺序；每步记录 SHA、主机、cwd、命令、通过数/总数、exit、日志）

**用户要求（2026-10-08）：先只跑必要的定向测试就上 Vivado 看时序，时序迭代期间不跑全量回归。** 分两段：

### 3.1 时序迭代段（每次改 RTL 后、上 Vivado 前只跑这些）

```sh
python3 tools/frozen_check.py                      # OK (8 files)
cd design
sbt "testOnly flow.multiplier.MulUnitSpec flow.multiplier.MulProtocolSpec flow.backend.MduTimingSpec flow.backend.MduBoundarySpec"
sbt "testOnly <L1D 单元/定向测试> <新增 PMP/PMA 上下文测试>"
sbt "runMain flow.top.GenerateBreezeCluster single gshare linux"   # tiny 用的生成物
```

只改了乘法器的那一轮可以跳过 L1D 那一行，反过来也一样；报告里写明跳过了哪一行。这些测试失败就先修，修好再上 Vivado，不允许带着已知功能失败去看时序。M3 修到哪个模块，就加跑那个模块的已有单元测试（例如后端时序合同 spec、前端 spec），具体选哪几个由 codex 决定，在报告里列出。

### 3.2 验收段（tiny routed WNS ≥ 0 之后，或用户要求时，用最终 RTL 跑一次）

```sh
sbt "testOnly flow.memsys.L1DL2MultiCoreSpec flow.memsys.L1DL2LitmusSpec flow.memsys.L1DL2MultiCoreFaultSpec flow.cluster.ClusterIsaSpec"
sbt test                                           # 全量；基线 bd48477 的 420/420
cd .. && python -m pytest sim/litex -q
python -u sim/litex/run_soc_smoke.py --profile single --evidence-dir <evidence>/smoke-single
```

验收段发现失败、需要改 RTL 的，修完后 tiny 重跑一次 Vivado，确认 WNS 仍 ≥ 0。

新增测试（3.1 中必须有）：

- 乘法：MUL/MULH/MULHSU/MULHU/MULW 对参考模型的随机测试（含 0、±1、最大/最小值、`-2^63`），以及在 E+1…E+3 各拍注入 kill/背压的用例，结果逐拍等于原实现。
- PMP/PMA：`csrw pmpcfg0/pmpaddr*` 或 `mstatus.MPRV/MPP` 改变后紧接 load/store/AMO（允许与不允许两种方向），以及 load 在 S2 因 Hold 保持多拍期间发生上下文相关事件的情况；影子检查全程开启。PTW 访问 PMP 拒绝区。

Vivado（Alan，100 MHz）：tiny 生产版。D5 停止规则沿用 SOC-2：`place_design` 前失败立即停。tiny 布线完成后：

- WNS ≥ 0：跑 §3.2 验收段，通过后再跑 `breeze-tiny --debug`，然后 small 冒烟与 small 生产版。
- WNS < 0：按 M3 继续修再跑；无法在 M3 范围内修的，停下报告。

## 4. 规则

同 [`SOC-axi-fpga-bringup.md`](SOC-axi-fpga-bringup.md) §3（冻结文件不改、不改测试期望/断言/看门狗凑通过、修根因、报告每个改动、不做形式化、不跑 OpenSBI/Linux、不烧板）。Vivado 与全量测试后台运行 + 退出码文件，不密集轮询。

## 5. 报告

写入 `docs/tasks/SOC-3-timing-report.md`：

- M1：新增 `L1S2` 字段、两份 checker 的位置、上下文一致性的结论与依据（或新增机制）、影子检查在综合网表中消失的证据；
- M2：部分积拆分、每级内容、`tiny-dsp-pipeline.tsv`；
- 各门槛结果表；
- 每次 Vivado：WNS/TNS/WHS、失败端点数、最差 10 条路径（起点、终点、slack、逻辑级数、logic/route 拆分、所属模块）、利用率；M3 每项修复的前后对比；
- 停下的项及建议裁定；bitstream 的 Alan 绝对路径。
