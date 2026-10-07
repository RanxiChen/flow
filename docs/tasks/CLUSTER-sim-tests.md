# CLUSTER：整机仿真与 riscv-tests（交给 codex 跑和修）

起点：包含本任务文件的提交。测试台、构建脚本与测试由 Claude 编写，本地未编译、未运行 sbt（`agent.md`：sbt 只在 Alan 执行）；`tools/build_riscv_tests.sh` 已在本地用 `riscv64-linux-gnu-gcc` 11.4 实跑，构建 111 个 `-p-` 程序，`amocas_w/d/q` 因汇编器不支持 Zacas 未构建。先读 [`CLUSTER-sim-abi.md`](CLUSTER-sim-abi.md)（测试台与程序约定）与 [`MEM-multicore-tests.md`](MEM-multicore-tests.md) 的规则。

这是真实 `BreezeCluster`（后端、前端、L1I、Sv39 MMU、L1D、L2）第一次运行指令。此前后端对 `L1DCoreIO` 的使用、真实 MMU/PTW 经 L1D/L2、L1I 经 L2 取指只有 elaborate 证据，预期会出现真实 RTL 问题。

## 1. 文件

| 文件 | 内容 |
| --- | --- |
| `design/src/test/scala/cluster/ClusterSim.scala` | `ClusterRunConfig`/`ClusterRunResult`/`ClusterSim.run`、`runAll`、`runMany`；`Elf64` 加载器（PT_LOAD、符号表）；`ClusterMmio`（CLINT、控制台、未建模地址记录）；复用 `memsys` 的 `AxiMemory` 作主存 |
| `design/src/test/scala/cluster/ClusterIsaSpec.scala` | riscv-tests：单核 Linux 配置下 rv64ui/um/ua/uc/mi/si 各一个用例；rv64ui+ua 随机 AXI/MMIO 反压；双核 cluster 上 rv64ui（hart 1 被 `RISCV_MULTICORE_DISABLE` 停住） |
| `tools/build_riscv_tests.sh` | 只构建 `-p-` 程序到 `design/build/riscv-tests/`；参数同 riscv-tests `isa/Makefile` 的 p 模板，另加 `-Wl,--build-id=none`；检查每个 ELF 有 `tohost` 且入口为 0x8000_0000；不能汇编的程序写入 `NOT_BUILT.txt` |
| `third_party/riscv-tests` | submodule，固定 `bcffa2b`（`env` 子模块 `6de71ed`） |
| `design/src/test/scala/core/RegFileCsrFileSpec.scala` | 从被删 `breezecoreSpec.scala` 原样保留的 `RegFileSpec`、`CSRFileSpec`（不依赖旧核） |

测试台要点：

- 一次 elaboration 内顺序运行多个 ELF：每个程序前把输入置空闲、复位 5 拍、换新的零背景主存并装入 PT_LOAD 段。测试台检测到的失败（tohost 非 1、看门狗、maxCycles、`hartFatal`、AXI 模型检查）只判该程序失败，继续下一个；仿真器自身停止（RTL 断言、`$fatal`）时记录当前程序失败并在新仿真中继续其余程序；elaborate/Verilator 构建失败时全部判失败。生成物在 `design/build/cluster-sim/<profile>-<tag>-<attempt>/`。
- tohost：只看 `retire[h]` 的已提交普通 Store（32 位 STORE/STORE-FP 或任意压缩 Store；AMO/SC 不算），按 `memWMask` 取写入字节，落在 `tohost + 64h` 的 8 B 槽内且值非零才记为结果；1 = 通过，其他 = 失败（`value >> 1` 为编号；p 环境的意外 trap 写 `TESTNUM | 1337`）。
- 看门狗：所有 hart 都无提交的连续拍数超过 `watchdog`（默认 20 000）。失败信息含每 hart 最近 32 条提交与晚写、控制台、AXI 模型状态、最近未建模 MMIO 访问。
- CLINT：`mtime` 每 `timerDivider` 拍加 1，同时驱动 `io.time`；`mtip[h] = mtime >= mtimecmp[h]`，`mtimecmp` 复位为全 1；`msip[h]` 由 MMIO 写入。

## 2. 删除清单与覆盖去向

| 删除 | 原覆盖 | 去向 |
| --- | --- | --- |
| `design/src/main/scala/core/BreezeCore.scala` | 旧核顶层（旧 dmem/imem 接口、`dcacheFlushReq`） | 由 `BreezeCluster` 取代 |
| `design/src/main/scala/sim/BreezeCoreSimSupport.scala` | 旧 runner、JSON 内存装载、`BreezeCoreSimApp`、L1DCoreIO 行为内存 | `ClusterSim`（ELF + 真实 L1D/L2） |
| `design/src/test/scala/sim/BreezeCoreSim.scala`、`BreezeCoreSimAppSpec.scala`、`BreezeCoreSimMemoryLoaderSpec.scala` | 旧 runner 自测、架构测试退出写 | `ClusterIsaSpec`（tohost 判定） |
| `design/src/test/scala/sim/BreezeCoreGShareSpec.scala` | baseline/GShare 提交序列一致：循环、交替分支、load→branch、JALR 换目标、ecall/illegal/mret | rv64ui 分支/跳转与 rv64mi 程序（Linux 配置默认 GShare）；baseline 对比不再覆盖 |
| `design/src/test/scala/sim/BreezePrivilegeFlowSpec.scala` | CSR 别名串行化、MRET 后定时器、M→S→U ECALL 委托与 SRET、高位用户 PC、Sv39 高地址循环、OpenSBI trap frame、mtopi 探测 trap | rv64mi/rv64si（csr、mcsr、scall、illegal、sbreak、dirty）；Sv39 用户态与定时器交付由 `ClusterProgramSpec` 覆盖（见下节），OpenSBI 片段不再单独覆盖 |
| `design/src/test/scala/sim/BreezeWfiFlowSpec.scala` | WFI 睡眠后 msip 唤醒于 PC+4 | rv64si-p-wfi；多 hart msip 唤醒由 `ClusterProgramSpec` 覆盖 |
| `design/src/test/scala/core/breezecoreSpec.scala` 中 `BreezeCoreNoFASECustomInstrSpec`、`BreezeCoreNoFASESpec` | ESTOP、前端 icache miss 气泡、分支重定向、ROM 非法指令 trap、mcause/mepc | rv64ui、rv64mi-p-illegal/csr；icache 气泡拍数不再覆盖 |
| `BreezeCoreTandem.scala` 中 `BreezeCoreSimTandemResult` | 依赖被删的 `BreezeCoreSimResult`，无其他使用 | 删除 |

保留：`sim/BreezeCoreTandem*.scala`（`RawCommitEvent`、`PendingTrace`、格式化与解析，`TraceProtocolSpec` 与 `ClusterSim` 使用）；`sim/breezecore/` 的汇编资产（README 已注明旧 runner 删除）。

## 3. 运行

```sh
git submodule update --init --recursive third_party/riscv-tests
tools/build_riscv_tests.sh                  # 或 RISCV_PREFIX=riscv64-linux-gnu- tools/build_riscv_tests.sh
make -C tests/cluster
cd design
sbt "testOnly flow.config.BreezeCoreConfigSpec flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
sbt "testOnly flow.memsys.L1DL2SystemSpec"
sbt "testOnly flow.cluster.ClusterIsaSpec"
sbt "testOnly flow.cluster.ClusterProgramSpec"
sbt test
```

前两条门槛通过后才跑 cluster；`ClusterIsaSpec` 通过后再跑 `ClusterProgramSpec`。最后的全量 `sbt test` 确认删除旧核后整个工程编译、其他 suite 没有回退，记录每个 suite 的通过数；与本任务无关的既有失败照实列出，不在本任务修。报告 `BUILD_INFO.txt`（工具链与 riscv-tests 版本）与 `NOT_BUILT.txt`。若 Alan 的工具链能汇编 Zacas，`amocas_*` 会被构建并按 `expectedUnsupported` 判定。

调试单个程序：在 `ClusterIsaSpec` 中临时以 `ClusterSim.run(ClusterRunConfig(cfg, elf, traceLog = true))` 打印逐条提交；不要把临时改动提交进最终版本。

## 4. expectedUnsupported

`ClusterIsaSpec` 中这些程序必须失败，并到达指定结束值（不是"允许失败"）：

| 程序 | 依据 | 必须的结束 |
| --- | --- | --- |
| `rv64ui-p-ma_data` | 非对齐数据访问 trap（`L1DCache` misaligned → cause 4/6；`breeze-mmu-closure-checklist.md` C2），程序要求硬件支持且无 handler | tohost = `1 | 1337`（第 1 个测试进入 other_exception） |
| `rv64ua-p-amocas_w/d/q` | 未实现 Zacas（`InstDecode` 无 AMO funct5 `00101`） | tohost 含 `1337` 位且非 1（illegal instruction 进入 other_exception）；工具链不能汇编时须列在 `NOT_BUILT.txt` |

其余程序（含 rv64mi 的 breakpoint、zicntr、instret_overflow、pmpaddr、misaligned 系列，rv64si 的 dirty、icache-alias、wfi，rv64uc）不预设结果，必须通过。若某个失败确系本设计未实现或有意不同的特性，在报告中给出 spec/设计文档依据后再加入列表，并为其写明必须的结束值；没有依据的失败按 RTL 问题处理。

## 自写程序（ClusterProgramSpec）

日期：2026-10-07。ABI 见 [`CLUSTER-sim-abi.md`](CLUSTER-sim-abi.md)。构建：`make -C tests/cluster`（默认 `RISCV_PREFIX=riscv64-unknown-elf-`；`riscv64-linux-gnu-` 亦可）。单核程序生成 `build/<name>.elf`，多 hart 程序按 `-DNHARTS=2/4` 生成 `build/<name>_2.elf`、`_4.elf`。全部 19 个 ELF 已用本地 `riscv64-linux-gnu-gcc` 构建（`-march=rv64ima_zicsr_zifencei`，无 C 扩展：trap 处理一律以 epc+4 跳过）。

本地逻辑检查（非整机证据）：`make -C tests/cluster qemu` 生成 `-DQEMU` 变体（控制台改为 virt UART，结果另写 sifive_test 退出设备），在 `qemu-system-riscv64 6.2 -M virt -bios none` 上 19/19 退出码 0。QEMU 与本设计不一致的部分用 `#ifndef QEMU` 排除，仅静态检查编码：Svade（QEMU 6.2 硬件置 A/D）、misaligned trap（QEMU 直接完成非对齐访存）、PMA 地址映射（virt 无 0x4000_0000 空洞、无 GPIO0）、S 取指 U 页（QEMU 6.2 在 SUM 改变后取指未报 fault）。

### 运行时约定（`common.h`）

- 每个程序以 `TEST_INIT` 开始：mtvec=direct `m_trap`，stvec=`s_trap`，PMP 条目 0 开放全部地址（否则 S/U 访问全被 PMP 拒绝），medeleg/mideleg/satp/mie 清零，s11=mhartid；超出 NHARTS 的 hart 停在 `park`（mie=0 的 WFI 循环）。
- 结束：任意模式 `a0=0/码，a7=EXIT_MAGIC，ecall`；M 处理程序在 M 模式写 `tohost+64*h`（trace 地址为虚拟地址，故只在未翻译的 M 模式写）。
- 期望 trap：`EXPECT cause`（M 处理）/`S_EXPECT cause`（经 medeleg 委托到 S）；处理程序记录 cause/epc/tval/MPP 或 SPP 并返回 epc+4，或返回 `*_RESUME` 指定的标签（S 处理程序返回时强制 SPP=S）。未期望的同步 trap 失败码 0x200+cause（M）、0x300+cause（S），未登记 hook 的中断 0x280+cause。
- 失败码在每个程序内唯一；`ClusterProgramSpec` 打印每 hart 的 `fail(code)`。

### 本设计约定的核实

| 项 | 结论 | 依据 |
| --- | --- | --- |
| A/D | **Svade**：A=0 → page fault；Store 且 D=0 → store page fault；硬件不写 PTE | `breeze-mmu-rtl-spec.md` §0 "不实现硬件 A/D 更新"、§5.3 `permFail` |
| 非对齐数据访问 | L1D S2 trap：Load/LR → 4，Store/SC/AMO → 6，tval=地址，不访存 | `L1DCache.scala` `misaligned` / `excCause`；closure-checklist C2 |
| mtvec | Direct 与 Vectored（中断跳 BASE+4·cause）；其他 MODE WARL 为 Direct | `RegFile.scala` mtvec 写 |
| WFI | 局部使能的待处理中断唤醒 WFI，与 mstatus.MIE/mideleg 无关；WFI 不停后台 | `RegFile.scala` `wfiWakeup` 注释；backend-rtl-spec §6 |
| 委托 | Linux 配置有 medeleg（可委托 1–9、12、13、15）与 mideleg（仅 S 级 SSIP/STIP/SEIP）；M 级中断不可委托，S 下总是使能 | `RegFile.scala` `medelegMask`/`midelegMask` |
| 取指 PMA | 不可执行区（设备、空洞）→ instruction access fault（1） | `L1ICache` PMAChecker Fetch；`FetchTlbClient` |
| 原子 PMA | device 区 AMONone/RsrvNone：AMO/SC → 7，LR → 5，不发请求 | `dcache-pipeline-design.md` 原子性 PMA；l1d-rtl-spec §5.1 |

### 用例

| 程序 | 配置 | 检查点 | 依据 | 不覆盖 |
| --- | --- | --- | --- | --- |
| `fencei_smc` | single | 先执行旧代码进 L1I；`sw` 改写 + FENCE.I 后 16 轮新返回值；`sd` 同时改两条指令；同 32 B 行内数据字写入不破坏代码并读回；无 FENCE.I 的执行不检查；第二段代码先被同 set 数据写逼出 L1D 再 FENCE.I | l1d-rtl-spec §11；backend FENCE.I 重定向与 L1I flush；L1I 经 L2 Read（L1D 持 M 时 Down） | 跨页指令；C 扩展；FENCE.I 与 L1I 预取 |
| `sv39_basic` | single | 4 KiB / 2 MiB / 1 GiB 映射（含非恒等与 RAM 别名）；只读页写 → 委托 store page fault（scause/stval/sepc）；SUM=0/1 访问 U 页；S 取指 U 页 → fetch page fault；MXR 读 X-only 页；X-only 页可执行；Svade：A=0 load fault、D=0 store fault（委托），软件置位 + sfence.vma 后重试成功；改 PTE + `sfence.vma va` 后看到新映射；U 模式在 U 页读写并 ecall（委托到 S） | breeze-mmu-rtl-spec §5.3；特权规范 Sv39 | ASID 隔离（见 mh_sv39）；超页未对齐 PTE 的 fault；非规范 VA |
| `sv39_ptw_cache` | single | S 模式普通 Store 改 PTE（同行另写一个数据字），sfence 后 walk 读到 L1D 中的脏 PTE（7 轮）；同 set 8 次 Store 逼出 PTE 行后再 walk（8 轮）；改非叶 PTE（l1 指向另一 l0）+ 全 sfence 后 walk 新表、再改回；经翻译写、物理读 | breeze-mmu-vipt-design（PTW 经 L1D 物理通道）；l1d-rtl-spec §7.2 | PTE 行在他核（见 mh_sv39）；PTW 遇 refill 错误 |
| `trap_misc` | single | M ecall、非法指令（全 0、写只读 CSR）；非对齐 ld/lw/lh/sd/sw/sh/amoadd.w/amoadd.d/lr.d/sc.d 的 cause、epc、tval，内存不变；取指于设备区与空洞 → 1（epc/tval）；空洞 ld/sd → 5/7（tval）；设备区 amoadd.d/amoswap.w/sc.d → 7，lr.d → 5；S：ecall → M（MPP=S），非法（csrr mstatus、mret、全 1）委托到 S；U：ecall → M（MPP=U），sret/csrr sstatus/csrw satp 非法委托到 S | 上表核实项；特权规范 mcause | 非法 CSR 的 mtval 内容（WARL，未检查）；EBREAK；跨页取指 fault |
| `mmio_console` | single | 字节写控制台，Spec 断言控制台文本为 `breeze cluster console ok\nX\n`；mtime 递增；mtimecmp 64 位写读、32 位低半写后 64 位读、高半 32 位读；msip 置位后 mip.MSIP=1 与清除；MMIO 与可缓存访存交错时控制台写保序 | l1d-rtl-spec §9（阻塞式 MMIO）；平台 CLINT 偏移 | 设备错误响应（SLVERR）；窄读 |
| `timer_irq` | single | MIE=1 时 WFI 被 MTI 打断：mcause=MTI、MPP=M、mepc=WFI 后一条（若中断在 WFI 退休前到达，mepc=WFI，hook 跳过它，两者都接受）；MIE=0 时 WFI 被唤醒但不 trap、mip.MTIP=1；Vectored mtvec 进入 BASE+28；S 模式下 M 定时器中断总被接受（MPP=S，mepc 在 S 循环内） | 特权规范 3.1.6.1、3.3.3；RegFile `wfiWakeup` | S 级定时器（STIP 经 mideleg）；中断与异常同拍优先级 |
| `lrsc_amo_single` | single | LR/SC 成功写入；无 reservation 的 SC 失败不写；更新的 LR 移走 reservation 后旧地址 SC 失败；同行他字 SC 结果与内存一致；LR.W 符号扩展、SC.W 只写 4 B；.aqrl LR/SC 自增 50 次；同 hart 普通 Store 后 SC 结果与内存一致；9 种 AMO × .D/.W × 2 组操作数（Python 独立模型预计算：.W 结果符号扩展、只改 4 B），aq/rl 编码轮换 | l1d-rtl-spec §8；A 扩展 | 80 拍 LR 窗口（模块级已测）；LR/SC 遇中断 |
| `mh_amo_lock` | dual / small / small+反压 | amoswap.w.aq 获取、amoswap.w.rl 释放的自旋锁保护非原子 ld/add/sd 计数，每 hart 100 次；锁与计数同行、分行两阶段；总和 = NHARTS×100，锁最终为 0 | RVWMO acquire/release；L2 一致性 | 公平性（只验证终止） |
| `mh_lrsc_counter` | 同上 | 每 hart 100 次 LR.D/SC.D（+1）与同行 LR.W/SC.W（+3）重试循环；总和精确，所有 hart 完成 | A 扩展受约束 LR/SC 前进保证；l1d-rtl-spec §8.1 | 前进的拍数上界 |
| `mh_message_pass` | 同上 | 32 轮 data → fence w,w → flag；读者等 flag → fence r,r → 必读新 data，全体确认后进入下一轮；阶段 1 data/flag 分行，阶段 2 同行 | RVWMO MP+fences | 无 fence 的允许结果（模块级 litmus 已测） |
| `mh_ipi` | 同上 | 8 轮：hart 0 等对方就绪后写 msip[h]；对方在 MIE=0 下 WFI（避免检查后睡眠竞争），醒后开 MIE 进 hook：mcause=MSI、清自身 msip（MMIO 回读）、报到；hart 0 等报到后下一个 | 特权规范 CLINT/MSIP；WFI 唤醒 | 多个 hart 同时被唤醒的并发 IPI |
| `mh_sv39` | 同上 | 每 hart 独立页表、ASID=h+1：私有页经翻译写、物理读；共享物理页经各 hart 不同 VA 做 amoadd，总和 NHARTS×50；共享 l0 表映射先被各 dTLB 缓存，hart 0 用普通 Store 改共享 PTE 后 IPI，各 hart M hook 执行 sfence.vma 后必须看到新页（他核 walk 读取 hart 0 持脏的 PTE 行） | breeze-mmu-rtl-spec（ASID tag、sfence）；一致性 | 不 sfence 时旧映射的可见性（不检查）；ASID 复用 |
| `mh_fencei` | 同上 | 所有 hart 先执行旧代码；6 轮：hart 0 改写 + FENCE.I + flag；其他 hart 见 flag 后执行自己的 FENCE.I 再执行，必须得到新值；全体确认后下一轮 | 特权/Zifencei：远端 hart 需自身 FENCE.I | 远端不执行 FENCE.I 时的行为 |

多 hart 组：`dual`（harts=2，`*_2.elf`）、`small`（harts=4，`*_4.elf`）、`small` + `axiBackpressure=true`、seed 7（`*_4.elf`）。全部用 Linux 特权配置（S/U、Sv39、C 打开；程序本身不含 C 指令）。

### 预计最先失败

1. `mh_sv39` 与 `sv39_ptw_cache`：真实 MMU 经 L1D/L2 的 walk 首次运行，尤其他核持脏 PTE 行时 PTW 的 Down、以及 PTE 行被逼出后的 walk。
2. `fencei_smc` / `mh_fencei`：L1I 经 L2 Read 拉取 L1D 持 M 的代码行（L2 Read 遇 UNIQUE 需 probe）以及 FENCE.I 对 L1I 的 flush。
3. `timer_irq` / `mh_ipi`：WFI 唤醒与 CLINT 模型、MMIO 回读时序。
4. `trap_misc`：取指 access fault 的 mtval 是否写入地址（本程序检查 tval=地址；若实现写 0，需按特权规范 WARL 讨论后再定是 RTL 还是测试问题）。

## 5. 规则

同 [`MEM-multicore-tests.md`](MEM-multicore-tests.md) §4：

1. 先修编译错误。测试代码与构建脚本未经 sbt 编译，语法与 ChiselSim API 用法错误直接修，不需要报告。
2. 失败时先判断是 RTL 错、测试台错还是程序错：对照 [`CLUSTER-sim-abi.md`](CLUSTER-sim-abi.md)、RISC-V 规范与 `docs/` 中的设计/spec。RTL 错就修 RTL 并同步 spec；测试台与约定冲突时按约定修测试台。
3. 不得放宽检查、删用例、降低程序集合、延长看门狗掩盖停顿，或无依据地扩大 `expectedUnsupported`。
4. `interface/L1DCoreIO.scala` 与冻结文件（`tools/frozen_check.py`）不改；若后端 bug 需要改冻结文件，停下报告，不自行修改。
5. 一个 RTL 问题先给出最小复现（riscv-tests 程序名或 `tests/cluster` 小程序，失败拍号、PC、最近提交），修复后保留复现用例。

## 6. 报告

写 `docs/tasks/CLUSTER-sim-tests-report.md`：被测 SHA、工具链与 riscv-tests 版本、各命令的通过数/总数与退出码；每个程序的 PASS/FAIL 与周期数（可附表）；每个 RTL 修复的根因（一两句）与对应程序；每处测试台/程序改动及依据；`expectedUnsupported` 的最终内容与依据；全量 `sbt test` 结果；仍失败或未运行的项。
