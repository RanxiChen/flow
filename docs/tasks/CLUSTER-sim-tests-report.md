# CLUSTER 整机仿真执行报告

执行日期：2026-10-07。任务依据：`CLUSTER-sim-tests.md`、`CLUSTER-sim-abi.md`。
执行已停在冻结契约边界，未完成整机闭环。“未运行”不代表通过。

## 主机与版本

- 起点：`11136581209562867fa74ff24979ffdbd711a1c8`。
- 实际主机：`cloud_chen@47.111.104.2:22`。按 `docs/cross-project/simulation-host.md` 预检首选主机，免密 SSH、Flow 环境、GitHub 反向代理均可用，无须回退 Alan。
- 工作区：`/home/cloud_chen/work/flow-cluster-20261007`；sbt cwd 为其 `design/`。
- 证据根目录：`/home/cloud_chen/evidence/cluster-20261007-1113658/`。
- 环境：16 逻辑核、30 GiB 内存（预检可用约 29 GiB）、磁盘可用 144 GiB；Verilator 5.028、Java 11.0.32.1、RISC-V GCC 13.2.0。sbt 实际版本为 1.9.7、Chisel 7.0.0，生成 RTL 文件头为 CIRCT firtool 1.128.0；工具身份和环境保存于 `environment.txt`。
- 外网代理：远端 `http://127.0.0.1:18897`，现有反向转发已验证 GitHub HTTP 200。未修改无关仓库全局配置。
- riscv-tests：`bcffa2b3188b040c611f90dc0b6e422f54775a09`；env=`6de71edb142be36319e380ce782c3d1830c65d68`（也记录于 `environment.txt`）。
- 本地 `python3 tools/frozen_check.py`：exit 0，7 个冻结文件一致。保留所有任务外未跟踪文件。

## 命令与执行状态

前两条测试门槛全部通过后才运行 ClusterIsaSpec；ISA 全部通过后才运行 ClusterProgramSpec。

| 阶段 | 命令 | 结果 | exit | 日志 |
| --- | --- | --- | --- | --- |
| 子模块 | `git submodule update --init --recursive third_party/riscv-tests`（命令级 http.proxy） | 完成 | 0 | `submodule.log` |
| ISA ELF | `tools/build_riscv_tests.sh` | 构建 111；未构建 3 | 0 | `build-riscv-tests.log` |
| 自写 ELF | `make -C tests/cluster` | 构建 19 | 0 | `build-cluster.log` |
| 模块/配置门槛 | `sbt "testOnly flow.config.BreezeCoreConfigSpec flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"` | 111/111（6 suites） | 0 | `gate1-baseline.log`、`gate1-baseline-reports/` |
| 真实单核系统门槛 | `sbt "testOnly flow.memsys.L1DL2SystemSpec"` | 12/12 | 0 | `gate2-baseline.log`、`gate2-baseline-reports/` |
| ISA 整机 | `sbt "testOnly flow.cluster.ClusterIsaSpec"` | ScalaTest 6/8；4 次程序非预期失败 | 1 | `isa-baseline.log`、`isa-baseline-reports/` |
| 自写程序整机 | `sbt "testOnly flow.cluster.ClusterProgramSpec"` | 未运行：ISA 门槛未通过且触及冻结契约 | — | — |
| 全量 | `sbt test` | 未运行：按冻结文件停止规则停下 | — | — |

`BUILD_INFO.txt` 当前内容：

```text
prefix=riscv64-unknown-elf-
riscv64-unknown-elf-gcc (13.2.0-11ubuntu1+12) 13.2.0
riscv-tests=bcffa2b3188b040c611f90dc0b6e422f54775a09
```

`NOT_BUILT.txt` 当前内容（汇编器不支持 Zacas）：

```text
rv64ua-p-amocas_w
rv64ua-p-amocas_d
rv64ua-p-amocas_q
```

## 需求与证据范围

| 需求 | 对应检查 | 本轮证据状态 |
| --- | --- | --- |
| 现有配置、驱动、L1D/L2 功能不回退 | 两条既有门槛 | 111/111 + 12/12，通过 |
| ISA 单核、随机 AXI/MMIO 反压、双核停驻 | ClusterIsaSpec；每个程序 tohost 与周期数 | 完整首轮执行，6/8 测试组通过；见下表 |
| Sv39/PTW、FENCE.I、异常/权限、MMIO/中断、原子操作与多 hart 协作 | ClusterProgramSpec；全部 hart 结果及 console 精确检查 | 未运行 |
| 旧核删除后工程编译与其他 suite 回归 | 全量 sbt test | 未运行；无全量回归结论 |

## 修复与失败归因

没有编译错误，没有进行 RTL、测试台或程序修改。冻结检查前后均为 7/7，exit 0；`L1DCoreIO` 与冻结 hash 未修改，expectedUnsupported 未扩大。以下失败均来自 DUT 功能/契约，不是主机、编译或代理故障，不通过切换 Alan 重跑掩盖。

### R1：SFENCE 首次 WB 拍与尚未排空的 iTLB 查询 S1 重合（停止项）

最小复现为原集合中的 `rv64mi-p-illegal`；同根因在 `rv64si-p-dirty` 与 `rv64si-p-icache-alias` 复现。仿真器在 iTLB 的 `sfence requires a drained TLB` 断言停止；测试台保留最近 32 条提交，重新 elaborate 后继续后续程序，没有删用例或关闭断言。

| 程序 | 失败拍（诊断原文） | SFENCE PC（ELF disassembly） | 最后提交 PC | 最后提交指令 |
| --- | --- | --- | --- | --- |
| rv64mi-p-illegal | 2210 | 0x80000258 | 0x80000254 | `csrc sstatus,t0`（0x1002b073） |
| rv64si-p-dirty | 1357 | 0x800001bc | 0x800001b8 | `csrw satp,a1`（0x18059073） |
| rv64si-p-icache-alias | 1672 | 0x80000220 | 0x8000021c | `csrw satp,a1`（0x18059073） |

断言现场与源码交叉定位：

- `Sv39Tlb.scala:31` 的 `idle = !missValid && !pendingFault && !sfS1Valid` 不包括查询 `s1Valid`；而 `Sv39Tlb.scala:150` 明确要求 sfence 当拍 `idle && !s1Valid`。
- `Sv39Mmu.scala:26` 将两侧 TLB idle 与 PTW idle 相与，并直接送后端；`BreezeBackend.scala:393` 一旦 WB sfence、L1D drained、MMU idle 就可发 sfence。
- `BreezeBackend.scala:392,402` 在 SFENCE 首次 WB 拍才关闭新翻译/发前端 flush；`BreezeCluster.scala` 将该 flush 接为 FetchTlbClient kill，再送 iTLB kill。kill 能抑制当拍请求/响应，但不能让已有查询 S1 寄存器在同拍已经变空。
- 因此 SFENCE 与首次 kill 可以在同拍到达：对后端可见的 mmuIdle 已为 1，TLB 内查询 S1 仍为 1，真实整机触发上述断言。现有 `BackendContractSpec` T20 在 SFENCE 入 WB 前先把行为 MMU 的 idle 拉低，未刺激这个首拍 idle=1、S1 仍占用的组合；模块门槛通过不能证明本整机边界正确。

需要裁定的冻结条款：

1. [`backend-timing-contract.md` T20](../backend-timing-contract.md#1-逐拍定向用例)：要求 **第一个** `drained && mmu.idle` 拍 S 发 sfence；该文件 §0 将“等于”定义为精确拍数，不能自行增加排空拍。
2. [`breeze-mmu-rtl-spec.md` §4.4](../breeze-mmu-rtl-spec.md#44-mmu-状态输出)：冻结的 idle 定义没有查询 S1，执行顺序则是 kill 前端 → 排空 → idle → sfence。
3. `backend-v1-rtl-spec.md:91` 将前端 kill/关闭请求放在 SFENCE 到 WB 后；`v1-integration-notes.md` §3（冻结）也规定 WB 串行执行、前端已被 kill 后等待 idle。

直接给 SFENCE 加“首次 WB flush 后至少一拍”的条件会改变 T20 的首次 idle 精确拍约定；给 MMU idle 加 `!s1Valid` 会改变冻结 MMU §4.4。提前到 EX/MEM kill 也是另一个串行边界设计选择，当前条款没有规定该前置排空过程，不自行替换为新约定。按任务书 §5.4 停下，保留 RTL 与断言，等待明确冻结契约如何表达查询 S1 排空后再修。

建议裁定时明确：sfence 只能在前端 kill 已生效、查询 S1 与 miss/PTW 均排空后发出；选择把查询 S1 纳入 idle，或定义独立的前端排空阶段，再同步 T20 和对应定向用例。这里仅列出待裁定方案，没有实施或重新登记冻结文件。

### R2：rv64mi-p-breakpoint 在 tselect 访问处收到 illegal instruction

cycle 1797 提交 tohost Store，值 `0x5`（test 2），runner 返回 cycles=1798；最后提交 PC=`0x80000044`。

ELF 中 `0x800001c0` 是 `csrw tselect,x0`（0x7a001073）。`tselect` 不在 `RegFile.scala` 的 implementedAddresses 白名单；未知 CSR 触发 cause 2。日志在最近一次普通提交 `0x800001bc: csrsi mstatus,8` 后进入 trap，随后 `0x80000004` 读取 mcause=2；程序 handler 期望 CAUSE_BREAKPOINT=3，故到 fail 并写 0x5。程序对可选 tcontrol 安排了临时 mtvec，但对 tselect 没有同样的非法访问探测。

这是明确的程序/CSR 实现兼容性失败，不是 EBREAK 用例失败（`rv64mi-p-sbreak` 已通过）。任务书要求 breakpoint 必须通过；未找到设计文档授权把该程序列为 expectedUnsupported，故按任务规则保留为待处理的 RTL 问题。未实现触发器、未修改 CSR 白名单，也没有修改程序 handler 或预期值；后续需据设计/spec 确定合法的无 trigger 探测语义，不能仅为过门槛返回任意值。

### 测试台报告限制

当前 `ClusterSim.runMany` 对仿真器断言停止构造 `cycles=0, retired=Nil` 的结果，但 `liveReport` 留下真实失败拍号与最近提交。因此下表对此类失败列“断言拍”，不把 runner 的 0 当成执行了 0 拍。这只是报告提取，没有修改测试台。

## expectedUnsupported

列表尚未改变：

- `rv64ui-p-ma_data`：设计规定非对齐数据访问 trap；必须到达 tohost=`1 | 1337`。
- `rv64ua-p-amocas_w/d/q`：未实现 Zacas；若构建成功，必须到达 tohost 非 1 且 `(value & 1337) == 1337`。本轮工具链未构建，按 `NOT_BUILT.txt` 明确报告。

依据为任务书 §4、L1D misaligned cause 4/6、MMU closure C2 与 InstDecode 缺少 Zacas funct5 `00101`。不以超时、hartFatal 或断言退出代替指定结束值。

## 已执行 suite 与逐程序结果

首轮模块/配置门槛在 `1113658` 上通过，合计 111/111，共用命令 exit 0：

| suite | 通过/总数 |
| --- | --- |
| BreezeCoreConfigSpec | 8/8 |
| MemAgentsSpec | 10/10 |
| L1DCacheSpec | 39/39 |
| L2HomeSpec | 12/12 |
| MemSkeletonElabSpec | 37/37 |
| L1DPermissionsSpec | 5/5 |

生产 Scala 96 个源码文件、测试 Scala 71 个源码文件编译通过，0 编译错误；`Planned` 内部 case class 有 1 条 outer-reference warning，不影响编译。真实单核系统门槛 `L1DL2SystemSpec` 12/12，通过，exit 0。两个门槛均为 0 aborted/canceled/ignored/pending。


ClusterIsaSpec 首轮（同 SHA）完整结束：6/8 测试组通过，2/8 失败；0 aborted/canceled/ignored/pending，命令 exit 1。失败组为 rv64mi、rv64si；其余四个单核 suite、随机反压组、双核组均通过。

| 配置 | 计划项 | 实际执行 | 普通 PASS | 预期 unsupported 结束值达成 | 非预期 FAIL | NOT_BUILT |
| --- | --- | --- | --- | --- | --- | --- |
| single | 114 | 111 | 106 | 1 | 4 | 3 |
| single-backpressure | 76 | 73 | 72 | 1 | 0 | 3 |
| dual（hart 0 判断结果，hart 1 停驻） | 54 | 54 | 53 | 1 | 0 | 0 |
| 合计 | 244 | 238 | 231 | 3 | 4 | 6 |

NOT_BUILT 的 6 项是同三个 Zacas 程序在 single 和 single-backpressure 各一次，不是六个不同 ELF。三次 `ma_data` 的 tohost 均为 0x539=1337，符合本任务已有 expectedUnsupported 判定。

以下逐程序表从 `isa-baseline.log` 提取，结构化原始提取结果另存于证据根的 `program-results.json`。普通 PASS 必须 tohost=1；“FAIL（预期）”仍是程序失败，只代表现有任务要求的指定结束值确实到达。

### single

| 程序 | 实际结果 | 周期 / 失败拍 | tohost |
| --- | --- | --- | --- |
| rv64ui-p-add | PASS | 6484 | 0x1 |
| rv64ui-p-addi | PASS | 3822 | 0x1 |
| rv64ui-p-addiw | PASS | 3784 | 0x1 |
| rv64ui-p-addw | PASS | 6394 | 0x1 |
| rv64ui-p-and | PASS | 7272 | 0x1 |
| rv64ui-p-andi | PASS | 3437 | 0x1 |
| rv64ui-p-auipc | PASS | 1601 | 0x1 |
| rv64ui-p-beq | PASS | 4355 | 0x1 |
| rv64ui-p-bge | PASS | 4635 | 0x1 |
| rv64ui-p-bgeu | PASS | 5671 | 0x1 |
| rv64ui-p-blt | PASS | 4365 | 0x1 |
| rv64ui-p-bltu | PASS | 5353 | 0x1 |
| rv64ui-p-bne | PASS | 4389 | 0x1 |
| rv64ui-p-simple | PASS | 1369 | 0x1 |
| rv64ui-p-fence_i | PASS | 4156 | 0x1 |
| rv64ui-p-jal | PASS | 1571 | 0x1 |
| rv64ui-p-jalr | PASS | 2296 | 0x1 |
| rv64ui-p-lb | PASS | 3884 | 0x1 |
| rv64ui-p-lbu | PASS | 3893 | 0x1 |
| rv64ui-p-lh | PASS | 4071 | 0x1 |
| rv64ui-p-lhu | PASS | 4178 | 0x1 |
| rv64ui-p-lw | PASS | 4233 | 0x1 |
| rv64ui-p-lwu | PASS | 4638 | 0x1 |
| rv64ui-p-ld | PASS | 6073 | 0x1 |
| rv64ui-p-ld_st | PASS | 18652 | 0x1 |
| rv64ui-p-lui | PASS | 1658 | 0x1 |
| rv64ui-p-ma_data | FAIL（预期） | 1457 | 0x539 |
| rv64ui-p-or | PASS | 7671 | 0x1 |
| rv64ui-p-ori | PASS | 3386 | 0x1 |
| rv64ui-p-sb | PASS | 6234 | 0x1 |
| rv64ui-p-sh | PASS | 6830 | 0x1 |
| rv64ui-p-sw | PASS | 6927 | 0x1 |
| rv64ui-p-sd | PASS | 8325 | 0x1 |
| rv64ui-p-st_ld | PASS | 9845 | 0x1 |
| rv64ui-p-sll | PASS | 7345 | 0x1 |
| rv64ui-p-slli | PASS | 4124 | 0x1 |
| rv64ui-p-slliw | PASS | 4233 | 0x1 |
| rv64ui-p-sllw | PASS | 7342 | 0x1 |
| rv64ui-p-slt | PASS | 6313 | 0x1 |
| rv64ui-p-slti | PASS | 3727 | 0x1 |
| rv64ui-p-sltiu | PASS | 3724 | 0x1 |
| rv64ui-p-sltu | PASS | 6553 | 0x1 |
| rv64ui-p-sra | PASS | 6985 | 0x1 |
| rv64ui-p-srai | PASS | 3978 | 0x1 |
| rv64ui-p-sraiw | PASS | 4541 | 0x1 |
| rv64ui-p-sraw | PASS | 7460 | 0x1 |
| rv64ui-p-srl | PASS | 7472 | 0x1 |
| rv64ui-p-srli | PASS | 4221 | 0x1 |
| rv64ui-p-srliw | PASS | 4338 | 0x1 |
| rv64ui-p-srlw | PASS | 7419 | 0x1 |
| rv64ui-p-sub | PASS | 6364 | 0x1 |
| rv64ui-p-subw | PASS | 6297 | 0x1 |
| rv64ui-p-xor | PASS | 7615 | 0x1 |
| rv64ui-p-xori | PASS | 3342 | 0x1 |
| rv64um-p-div | PASS | 2219 | 0x1 |
| rv64um-p-divu | PASS | 2193 | 0x1 |
| rv64um-p-divuw | PASS | 2101 | 0x1 |
| rv64um-p-divw | PASS | 2126 | 0x1 |
| rv64um-p-mul | PASS | 6355 | 0x1 |
| rv64um-p-mulh | PASS | 6369 | 0x1 |
| rv64um-p-mulhsu | PASS | 6364 | 0x1 |
| rv64um-p-mulhu | PASS | 6772 | 0x1 |
| rv64um-p-mulw | PASS | 5591 | 0x1 |
| rv64um-p-rem | PASS | 2119 | 0x1 |
| rv64um-p-remu | PASS | 2127 | 0x1 |
| rv64um-p-remuw | PASS | 2059 | 0x1 |
| rv64um-p-remw | PASS | 2134 | 0x1 |
| rv64ua-p-amoadd_d | PASS | 1733 | 0x1 |
| rv64ua-p-amoand_d | PASS | 1699 | 0x1 |
| rv64ua-p-amomax_d | PASS | 1659 | 0x1 |
| rv64ua-p-amomaxu_d | PASS | 1662 | 0x1 |
| rv64ua-p-amomin_d | PASS | 1661 | 0x1 |
| rv64ua-p-amominu_d | PASS | 1667 | 0x1 |
| rv64ua-p-amoor_d | PASS | 1652 | 0x1 |
| rv64ua-p-amoxor_d | PASS | 1704 | 0x1 |
| rv64ua-p-amoswap_d | PASS | 1690 | 0x1 |
| rv64ua-p-amoadd_w | PASS | 1693 | 0x1 |
| rv64ua-p-amoand_w | PASS | 1662 | 0x1 |
| rv64ua-p-amomax_w | PASS | 1849 | 0x1 |
| rv64ua-p-amomaxu_w | PASS | 1844 | 0x1 |
| rv64ua-p-amomin_w | PASS | 1842 | 0x1 |
| rv64ua-p-amominu_w | PASS | 1842 | 0x1 |
| rv64ua-p-amoor_w | PASS | 1666 | 0x1 |
| rv64ua-p-amoxor_w | PASS | 1726 | 0x1 |
| rv64ua-p-amoswap_w | PASS | 1664 | 0x1 |
| rv64ua-p-amocas_w | NOT_BUILT | — | — |
| rv64ua-p-amocas_d | NOT_BUILT | — | — |
| rv64ua-p-amocas_q | NOT_BUILT | — | — |
| rv64ua-p-lrsc | PASS | 63563 | 0x1 |
| rv64uc-p-rvc | PASS | 3810 | 0x1 |
| rv64mi-p-breakpoint | FAIL | 1798 | 0x5 |
| rv64mi-p-csr | PASS | 3796 | 0x1 |
| rv64mi-p-mcsr | PASS | 1658 | 0x1 |
| rv64mi-p-illegal | FAIL | 断言拍 2210（runner cycles=0） | — |
| rv64mi-p-ma_fetch | PASS | 1975 | 0x1 |
| rv64mi-p-ma_addr | PASS | 9049 | 0x1 |
| rv64mi-p-scall | PASS | 1546 | 0x1 |
| rv64mi-p-sbreak | PASS | 1743 | 0x1 |
| rv64mi-p-ld-misaligned | PASS | 4723 | 0x1 |
| rv64mi-p-lw-misaligned | PASS | 2543 | 0x1 |
| rv64mi-p-lh-misaligned | PASS | 1882 | 0x1 |
| rv64mi-p-sh-misaligned | PASS | 1968 | 0x1 |
| rv64mi-p-sw-misaligned | PASS | 2616 | 0x1 |
| rv64mi-p-sd-misaligned | PASS | 5060 | 0x1 |
| rv64mi-p-zicntr | PASS | 1844 | 0x1 |
| rv64mi-p-instret_overflow | PASS | 1563 | 0x1 |
| rv64mi-p-pmpaddr | PASS | 1560 | 0x1 |
| rv64si-p-csr | PASS | 2803 | 0x1 |
| rv64si-p-dirty | FAIL | 断言拍 1357（runner cycles=0） | — |
| rv64si-p-icache-alias | FAIL | 断言拍 1672（runner cycles=0） | — |
| rv64si-p-ma_fetch | PASS | 1944 | 0x1 |
| rv64si-p-scall | PASS | 1780 | 0x1 |
| rv64si-p-wfi | PASS | 1500 | 0x1 |
| rv64si-p-sbreak | PASS | 1685 | 0x1 |

### single-backpressure

| 程序 | 实际结果 | 周期 / 失败拍 | tohost |
| --- | --- | --- | --- |
| rv64ui-p-add | PASS | 7235 | 0x1 |
| rv64ui-p-addi | PASS | 4239 | 0x1 |
| rv64ui-p-addiw | PASS | 4199 | 0x1 |
| rv64ui-p-addw | PASS | 7122 | 0x1 |
| rv64ui-p-and | PASS | 8023 | 0x1 |
| rv64ui-p-andi | PASS | 3812 | 0x1 |
| rv64ui-p-auipc | PASS | 1878 | 0x1 |
| rv64ui-p-beq | PASS | 4892 | 0x1 |
| rv64ui-p-bge | PASS | 5080 | 0x1 |
| rv64ui-p-bgeu | PASS | 6157 | 0x1 |
| rv64ui-p-blt | PASS | 4779 | 0x1 |
| rv64ui-p-bltu | PASS | 5851 | 0x1 |
| rv64ui-p-bne | PASS | 4916 | 0x1 |
| rv64ui-p-simple | PASS | 1545 | 0x1 |
| rv64ui-p-fence_i | PASS | 4452 | 0x1 |
| rv64ui-p-jal | PASS | 1764 | 0x1 |
| rv64ui-p-jalr | PASS | 2560 | 0x1 |
| rv64ui-p-lb | PASS | 4303 | 0x1 |
| rv64ui-p-lbu | PASS | 4327 | 0x1 |
| rv64ui-p-lh | PASS | 4547 | 0x1 |
| rv64ui-p-lhu | PASS | 4689 | 0x1 |
| rv64ui-p-lw | PASS | 4774 | 0x1 |
| rv64ui-p-lwu | PASS | 5140 | 0x1 |
| rv64ui-p-ld | PASS | 6749 | 0x1 |
| rv64ui-p-ld_st | PASS | 21031 | 0x1 |
| rv64ui-p-lui | PASS | 1869 | 0x1 |
| rv64ui-p-ma_data | FAIL（预期） | 1609 | 0x539 |
| rv64ui-p-or | PASS | 8358 | 0x1 |
| rv64ui-p-ori | PASS | 3707 | 0x1 |
| rv64ui-p-sb | PASS | 6906 | 0x1 |
| rv64ui-p-sh | PASS | 7576 | 0x1 |
| rv64ui-p-sw | PASS | 7654 | 0x1 |
| rv64ui-p-sd | PASS | 9129 | 0x1 |
| rv64ui-p-st_ld | PASS | 11208 | 0x1 |
| rv64ui-p-sll | PASS | 8078 | 0x1 |
| rv64ui-p-slli | PASS | 4516 | 0x1 |
| rv64ui-p-slliw | PASS | 4745 | 0x1 |
| rv64ui-p-sllw | PASS | 8211 | 0x1 |
| rv64ui-p-slt | PASS | 7043 | 0x1 |
| rv64ui-p-slti | PASS | 4156 | 0x1 |
| rv64ui-p-sltiu | PASS | 4147 | 0x1 |
| rv64ui-p-sltu | PASS | 7310 | 0x1 |
| rv64ui-p-sra | PASS | 7668 | 0x1 |
| rv64ui-p-srai | PASS | 4433 | 0x1 |
| rv64ui-p-sraiw | PASS | 5140 | 0x1 |
| rv64ui-p-sraw | PASS | 8390 | 0x1 |
| rv64ui-p-srl | PASS | 8310 | 0x1 |
| rv64ui-p-srli | PASS | 4690 | 0x1 |
| rv64ui-p-srliw | PASS | 4873 | 0x1 |
| rv64ui-p-srlw | PASS | 8313 | 0x1 |
| rv64ui-p-sub | PASS | 7023 | 0x1 |
| rv64ui-p-subw | PASS | 6957 | 0x1 |
| rv64ui-p-xor | PASS | 8439 | 0x1 |
| rv64ui-p-xori | PASS | 3712 | 0x1 |
| rv64ua-p-amoadd_d | PASS | 1971 | 0x1 |
| rv64ua-p-amoand_d | PASS | 1891 | 0x1 |
| rv64ua-p-amomax_d | PASS | 1888 | 0x1 |
| rv64ua-p-amomaxu_d | PASS | 1920 | 0x1 |
| rv64ua-p-amomin_d | PASS | 1862 | 0x1 |
| rv64ua-p-amominu_d | PASS | 1899 | 0x1 |
| rv64ua-p-amoor_d | PASS | 1920 | 0x1 |
| rv64ua-p-amoxor_d | PASS | 1924 | 0x1 |
| rv64ua-p-amoswap_d | PASS | 1896 | 0x1 |
| rv64ua-p-amoadd_w | PASS | 1946 | 0x1 |
| rv64ua-p-amoand_w | PASS | 1872 | 0x1 |
| rv64ua-p-amomax_w | PASS | 2057 | 0x1 |
| rv64ua-p-amomaxu_w | PASS | 2069 | 0x1 |
| rv64ua-p-amomin_w | PASS | 2068 | 0x1 |
| rv64ua-p-amominu_w | PASS | 2077 | 0x1 |
| rv64ua-p-amoor_w | PASS | 1898 | 0x1 |
| rv64ua-p-amoxor_w | PASS | 1948 | 0x1 |
| rv64ua-p-amoswap_w | PASS | 1864 | 0x1 |
| rv64ua-p-amocas_w | NOT_BUILT | — | — |
| rv64ua-p-amocas_d | NOT_BUILT | — | — |
| rv64ua-p-amocas_q | NOT_BUILT | — | — |
| rv64ua-p-lrsc | PASS | 63810 | 0x1 |

### dual

| 程序 | 实际结果 | 周期 / 失败拍 | tohost |
| --- | --- | --- | --- |
| rv64ui-p-add | PASS | 6716 | 0x1 |
| rv64ui-p-addi | PASS | 4075 | 0x1 |
| rv64ui-p-addiw | PASS | 4047 | 0x1 |
| rv64ui-p-addw | PASS | 6659 | 0x1 |
| rv64ui-p-and | PASS | 7524 | 0x1 |
| rv64ui-p-andi | PASS | 3685 | 0x1 |
| rv64ui-p-auipc | PASS | 1865 | 0x1 |
| rv64ui-p-beq | PASS | 4635 | 0x1 |
| rv64ui-p-bge | PASS | 4899 | 0x1 |
| rv64ui-p-bgeu | PASS | 5923 | 0x1 |
| rv64ui-p-blt | PASS | 4626 | 0x1 |
| rv64ui-p-bltu | PASS | 5614 | 0x1 |
| rv64ui-p-bne | PASS | 4638 | 0x1 |
| rv64ui-p-simple | PASS | 1622 | 0x1 |
| rv64ui-p-fence_i | PASS | 4411 | 0x1 |
| rv64ui-p-jal | PASS | 1832 | 0x1 |
| rv64ui-p-jalr | PASS | 2540 | 0x1 |
| rv64ui-p-lb | PASS | 4155 | 0x1 |
| rv64ui-p-lbu | PASS | 4146 | 0x1 |
| rv64ui-p-lh | PASS | 4321 | 0x1 |
| rv64ui-p-lhu | PASS | 4419 | 0x1 |
| rv64ui-p-lw | PASS | 4515 | 0x1 |
| rv64ui-p-lwu | PASS | 4897 | 0x1 |
| rv64ui-p-ld | PASS | 6309 | 0x1 |
| rv64ui-p-ld_st | PASS | 18930 | 0x1 |
| rv64ui-p-lui | PASS | 1932 | 0x1 |
| rv64ui-p-ma_data | FAIL（预期） | 1714 | 0x539 |
| rv64ui-p-or | PASS | 7928 | 0x1 |
| rv64ui-p-ori | PASS | 3614 | 0x1 |
| rv64ui-p-sb | PASS | 6486 | 0x1 |
| rv64ui-p-sh | PASS | 7085 | 0x1 |
| rv64ui-p-sw | PASS | 7168 | 0x1 |
| rv64ui-p-sd | PASS | 8561 | 0x1 |
| rv64ui-p-st_ld | PASS | 10098 | 0x1 |
| rv64ui-p-sll | PASS | 7583 | 0x1 |
| rv64ui-p-slli | PASS | 4388 | 0x1 |
| rv64ui-p-slliw | PASS | 4476 | 0x1 |
| rv64ui-p-sllw | PASS | 7603 | 0x1 |
| rv64ui-p-slt | PASS | 6589 | 0x1 |
| rv64ui-p-slti | PASS | 3983 | 0x1 |
| rv64ui-p-sltiu | PASS | 3972 | 0x1 |
| rv64ui-p-sltu | PASS | 6800 | 0x1 |
| rv64ui-p-sra | PASS | 7244 | 0x1 |
| rv64ui-p-srai | PASS | 4241 | 0x1 |
| rv64ui-p-sraiw | PASS | 4800 | 0x1 |
| rv64ui-p-sraw | PASS | 7735 | 0x1 |
| rv64ui-p-srl | PASS | 7736 | 0x1 |
| rv64ui-p-srli | PASS | 4470 | 0x1 |
| rv64ui-p-srliw | PASS | 4574 | 0x1 |
| rv64ui-p-srlw | PASS | 7668 | 0x1 |
| rv64ui-p-sub | PASS | 6598 | 0x1 |
| rv64ui-p-subw | PASS | 6550 | 0x1 |
| rv64ui-p-xor | PASS | 7873 | 0x1 |
| rv64ui-p-xori | PASS | 3616 | 0x1 |

## 证据与继续条件

- 远端证据根：`/home/cloud_chen/evidence/cluster-20261007-1113658/`。
- 三条 sbt 命令各有 `.sha/.cwd/.command/.exit/.log`，XML 报告分别归档到 `gate1-baseline-reports/`、`gate2-baseline-reports/`、`isa-baseline-reports/`。后两批目录也包含此前 suite 的 XML，只统计各自命令选中的 suite，不重复计数。
- `environment.txt`、`submodules.txt`、`BUILD_INFO.txt`、`NOT_BUILT.txt` 保存工具链和依赖身份；另初始化 `third_party/cvfpu` 及其嵌套子模块，依赖固定 gitlink，日志为 `submodule-cvfpu.log`。
- 四个失败程序的原 ELF disassembly 保存为 `<program>.dump`；`isa-baseline-cluster-artifacts.tar.gz` 保存 single-isa-0/1/2/3 与 dual-isa-0 的生成 RTL、仿真构建、执行脚本和仿真日志，归档约 297 MiB，校验值见 `isa-baseline-cluster-artifacts.sha256`。sfence 三次断言分别位于 single-isa-0/1/2。
- `frozen-final.txt`：7/7、exit 0；`final-source.sha/status` 保存测试结束时源版本与干净 tracked 工作区。本地仅新增本报告，报告提交不改变被测源码身份。

当前不能给出 ClusterProgramSpec 或全量 sbt test 的通过数：两者均**未运行**。19 个自写 ELF 构建成功仅是构建证据，不是整机执行证据。没有进行综合、时序、FPGA、Linux 或性能验证。

继续前先明确 R1 的冻结排空/拍级契约，再本地修改 RTL 和对应 spec/最小定向回归，提交并通过 GitHub 同步到预检后的仿真机器；修复版本重新通过两条门槛、完整 ClusterIsaSpec 后再运行 ClusterProgramSpec，最后全量 sbt test。R2 不得无依据地改为允许失败。全量中与本任务无关的既有失败仍应照实报告，不扩大修复范围。

## 第二轮

任务：`CLUSTER-sfence-idle-fix.md`。首轮内容原样保留；本节记录新裁定后的独立执行，不能与首轮 SHA 混计。

### 版本、裁定与环境

- 本轮起点及 01—06 被测 SHA：`cc60ebf659625ccc874a1eea8e33e866981fb1e3`，本地提交通过 GitHub 同步到 cloud_chen。
- 测试修正后的最终被测 SHA：`62ceea9e00c90edeaf9dc43ba9c7e2f23f5bb103`（07 定向、08 全量）；报告提交只更新文档，不能替代被测源码 SHA。
- 本轮预检实际主机：`cloud_chen@47.111.104.2:22`；免密 SSH、Flow 环境、GitHub loopback 反向代理、29 GiB 可用内存、141 GiB 可用磁盘通过检查，无其他仿真任务。
- cwd：`/home/cloud_chen/work/flow-cluster-20261007/design`；本轮证据根：`/home/cloud_chen/evidence/cluster-round2-20261007-cc60ebf/`，首轮归档保留。
- R1 裁定：MMU idle 包含 I/D 查询 S1；T20 不变，按新 idle 计算首次 SFENCE 允许拍。已有 TLB 排空断言保留；新增 T18 检查两侧 S1、kill 当拍与下一拍 idle。
- R2 裁定：M 态 tselect/tdata1/tdata2 读零、写忽略，tdata1.type=0 表示无 trigger；breakpoint 仍必须 tohost=1，不加入 expectedUnsupported。
- 冻结 spec/hash 的修订来自起点提交；本轮本地和远端冻结检查均为 OK (7 files)、exit 0。后续不得自行修改冻结文件。
- RISC-V GCC 13.2.0、Verilator 5.028、Java 11.0.32.1；工具版本/子模块身份保存于 `environment.txt`。重新执行子模块更新与 riscv-tests 构建，111 ELF 构建成功，三个 Zacas ELF 列于 `NOT_BUILT.txt`；`make -C tests/cluster` exit 0。

### 第二轮命令状态

| 顺序 | 命令 | 通过/总数 | exit | 日志 |
| --- | --- | --- | --- | --- |
| 1 | `sbt "testOnly flow.mmu.sv39.Sv39MmuSpec flow.core.CSRFileSpec flow.core.RegFileSpec flow.backend.BackendContractSpec"` | 64/64（4 suites） | 0 | `01-direct.log` |
| 2 | 原六 suite 模块/配置门槛 | 111/111 | 0 | `02-unit.log` |
| 3 | `sbt "testOnly flow.memsys.L1DL2SystemSpec"` | 12/12 | 0 | `03-system.log` |
| 4 | `sbt "testOnly flow.cluster.ClusterIsaSpec"` | 8/8 | 0 | `04-isa.log` |
| 5 | `sbt "testOnly flow.cluster.ClusterProgramSpec"` | 4/4 测试组，25/25 次程序 PASS | 0 | `05-program.log` |
| 6 | `sbt test` | 408/410，2 失败 | 1 | `06-full.log` |
| 7 | 修正后 `sbt "testOnly flow.core.BreezePrivilegeSpec"` | 19/19 | 0 | 新证据根 `07-privilege.log` |
| 8 | 修正后 `sbt test` | 410/410，57 suite 全部完成 | 0 | 新证据根 `08-full.log` |

直接门槛、模块/配置、单核系统、ISA 与自写程序均已通过。首次全量暴露两个未同步 R2 的旧 CSR 测试；按裁定修正后该 suite 19/19 通过。最终提交全量 410/410、exit 0，确认两个旧测试问题已修正，其他 suite 未回退。

### R1/R2 四个失败转为 PASS

同一 `cc60ebf`、cloud_chen 的完整 ClusterIsaSpec 8/8、exit 0：

| 程序 | 结果 | cycles | tohost |
| --- | --- | --- | --- |
| rv64mi-p-illegal | PASS | 4701 | 0x1 |
| rv64si-p-dirty | PASS | 2892 | 0x1 |
| rv64si-p-icache-alias | PASS | 3187 | 0x1 |
| rv64mi-p-breakpoint | PASS | 1934 | 0x1 |

本轮执行全部 238 次已构建程序：235 次普通 PASS、3 次 ma_data 达到既定 unsupported 结束值 0x539，0 次非预期失败。三个 Zacas ELF 在 single 与 single-backpressure 中共 6 次 NOT_BUILT，列表/检查/看门狗未改变；首轮通过项没有回退。`04-isa-artifacts.tar.gz` 保存本轮 single-isa-0、dual-isa-0，首轮的重启目录不混入本轮证据。

### 离线后台队列

用户要求离线约一小时期间服务器自行执行。2026-10-07 在 ISA exit 0 后，用 `nohup` 启动证据根中的 `queued-run.sh`，脱离 SSH；先首次运行 ClusterProgramSpec，再首次运行全量 sbt test。程序功能失败仍保存结果并继续收集任务要求的全量回归；脚本不改代码、不修改 expectedUnsupported 或任何检查。

每阶段之前核对 HEAD=`cc60ebf659625ccc874a1eea8e33e866981fb1e3`、tracked 工作区干净、冻结检查通过；身份/冻结文件变化时停止。两个阶段由 `run-stage.sh` 保存 SHA、cwd、命令、开始/结束时间、exit、日志和 XML。自写程序生成物在全量复用目录前归档为 `05-program-artifacts.tar.gz`。后台状态见 `background.status`，PID 见 `background.pid`，队列日志为 `background.log`；完成后记录 `queue-program.exit`、`queue-full.exit`。

主机配置已在排队前重新读取并验证 cloud_chen；队列使用这次已确认的主机和环境，不启动 Alan 或本地仿真。待回来后依据日志诊断新问题、按原任务规则本地修复；后台不会自动放宽测试或修改冻结契约。


### 第二轮门槛逐 suite

| 命令 | suite | 通过/总数 | 命令 exit |
| --- | --- | --- | --- |
| 01-direct | Sv39MmuSpec | 17/17 | 0 |
| 01-direct | CSRFileSpec | 7/7 | 0 |
| 01-direct | RegFileSpec | 1/1 | 0 |
| 01-direct | BackendContractSpec | 39/39 | 0 |
| 02-unit | BreezeCoreConfigSpec | 8/8 | 0 |
| 02-unit | MemAgentsSpec | 10/10 | 0 |
| 02-unit | L1DCacheSpec | 39/39 | 0 |
| 02-unit | L2HomeSpec | 12/12 | 0 |
| 02-unit | MemSkeletonElabSpec | 37/37 | 0 |
| 02-unit | L1DPermissionsSpec | 5/5 | 0 |
| 03-system | L1DL2SystemSpec | 12/12 | 0 |
| 04-isa | ClusterIsaSpec | 8/8 | 0 |
| 05-program | ClusterProgramSpec | 4/4 | 0 |

直接门槛 T18、三个 trigger CSR 的新增检查及原 T20 通过。以上各批均 0 aborted/canceled/ignored/pending。XML 目录为 `<stage>-reports/`，包含累计 suite XML，按各阶段实际执行 suite 筛选，不能重复累计。

### 首次 ClusterProgramSpec 结果

同一 `cc60ebf` 在 cloud_chen 上首次执行，4/4 测试组通过、命令 exit 0。全部 19 个唯一 ELF 在 single（7）、dual（6）、small（6）、small+随机反压 seed 7（6）共 25 次执行，各参与 hart 都提交 tohost=1；console 用例的文本精确断言也通过。没有新发现需要修复的 RTL、测试台或程序问题，没有延长 watchdog/maxCycles 或改变随机种子、程序规模、检查强度。

| 配置 | 程序 | 结果 | cycles | 每 hart 提交数 |
| --- | --- | --- | --- | --- |
| single | fencei_smc | PASS | 5140 | 350 |
| single | sv39_basic | PASS | 10273 | 778 |
| single | sv39_ptw_cache | PASS | 10555 | 869 |
| single | trap_misc | PASS | 25511 | 2092 |
| single | mmio_console | PASS | 43372 | 4258 |
| single | timer_irq | PASS | 6821 | 456 |
| single | lrsc_amo_single | PASS | 14389 | 1127 |
| dual | mh_amo_lock_2 | PASS | 37718 | 3278/3375 |
| dual | mh_lrsc_counter_2 | PASS | 13634 | 1282/1276 |
| dual | mh_message_pass_2 | PASS | 22918 | 2175/2155 |
| dual | mh_ipi_2 | PASS | 6866 | 572/545 |
| dual | mh_sv39_2 | PASS | 6729 | 532/517 |
| dual | mh_fencei_2 | PASS | 3946 | 263/259 |
| small | mh_amo_lock_4 | PASS | 109412 | 7431/7012/8712/6729 |
| small | mh_lrsc_counter_4 | PASS | 22136 | 1286/1280/1280/1276 |
| small | mh_message_pass_4 | PASS | 26815 | 2335/2359/2435/2415 |
| small | mh_ipi_4 | PASS | 16256 | 1428/550/550/545 |
| small | mh_sv39_4 | PASS | 8973 | 537/518/523/533 |
| small | mh_fencei_4 | PASS | 4665 | 267/271/271/259 |
| small-backpressure | mh_amo_lock_4 | PASS | 111682 | 6789/7023/8419/7596 |
| small-backpressure | mh_lrsc_counter_4 | PASS | 22408 | 1282/1280/1280/1276 |
| small-backpressure | mh_message_pass_4 | PASS | 26762 | 2363/2363/2335/2327 |
| small-backpressure | mh_ipi_4 | PASS | 16664 | 1440/550/550/545 |
| small-backpressure | mh_sv39_4 | PASS | 9679 | 546/521/525/537 |
| small-backpressure | mh_fencei_4 | PASS | 4914 | 271/263/255/263 |


逐程序结构化结果保存于证据根 `program-results.json`。`05-program-artifacts.tar.gz` 在全量开始前保存了单核、双核、四核配置的生成 RTL/构建及仿真日志；两组 small 使用相同测试台目录，原始 small 仿真日志最终由 small-backpressure 覆盖，正常 small 的每程序结果/周期/提交数保留在 `05-program.log`。两组运行参数与刺激结果按日志分别统计，不把反压结果代替正常组。


### 全量中发现的旧 CSR 测试未同步 R2

静态检查 `BreezePrivilegeSpec.scala` 发现 MCU/Linux 两个 4096 地址白名单均缺少 R2 新增的 `tselect/tdata1/tdata2`，旧 OpenSBI 探测表还要求 `tselect` 非法。该期望与本轮任务 R2 和 `m-mode-implementation.md` 的 Debug Trigger CSRs 裁定冲突；先保留 `cc60ebf` 首次全量结果，再按裁定同步测试。

本地修正提交 `62ceea9e00c90edeaf9dc43ba9c7e2f23f5bb103` 仅修改 `BreezePrivilegeSpec.scala`：两个 4096 地址扫描保留；原有六个 OpenSBI 探测地址保留，五个无关扩展仍要求非法，tselect 改为明确的合法读零检查；补充 tdata1/tdata2 合法读零及 tdata3/tinfo/tcontrol 非法检查；在既有 S/U 切换用例中分别检查三个 M-only CSR 被拒绝。依据来自显式 R2 裁定，不是放宽未定义行为，不改 RTL、冻结文件或 expectedUnsupported。

修正通过 GitHub 传递；独立证据根 `/home/cloud_chen/evidence/cluster-round2-20261007-62ceea9/`。后台 `followup.sh` 等本轮首次全量进程结束、exit 和归档完成后，核对旧 SHA/干净工作区/冻结检查，再同步准确新 SHA，并执行 `sbt "testOnly flow.core.BreezePrivilegeSpec"`，结果写 `07-privilege.*`。已保存实际首次全量失败证据，修正后定向 suite 为 19/19、exit 0；源码 SHA 为 `62ceea9e00c90edeaf9dc43ba9c7e2f23f5bb103`，冻结检查 7/7，tracked 工作区干净。


首次全量实际失败信息：

- MCU 地址扫描：`MCU CSR 0x7a0: false was not equal to true`（旧文件第 121 行）。
- Linux 固件探测：`OpenSBI probe tselect/Sdtrig at 0x7a0: 0 was not equal to 1`（旧文件第 164 行）。

两项均是旧测试还要求 tselect 非法，与明确的 R2 裁定冲突；不是 RTL 失败。首次全量 57 个 suite 全部完成，410 个测试中 408 通过、2 失败；0 aborted/canceled/ignored/pending，无其他失败。日志 `06-full.log`、原始 XML `06-full-reports/`、结构化结果 `full-results.json` 保留于 cc60ebf 证据根，实际 sbt 耗时 5142 秒。之后新 SHA 的定向 19/19 包含原有全部用例及新增的探测/权限检查。

### 全量逐 suite

最终源代码 SHA 为 `62ceea9e00c90edeaf9dc43ba9c7e2f23f5bb103`，相较起点仅追加报告并同步一个 CSR 测试文件，全部 RTL、其他测试及冻结文件不变。2026-10-07 19:13 在重新读取主机配置、核对 cloud_chen 环境/版本/准确 SHA/干净工作区以及约 24 GiB 可用内存、90 GiB 可用磁盘后，启动后台 `final-full.sh`。`08-full.*` 单独记录最终提交的完整回归，前后核对冻结检查；失败不改检查或自行修改代码。最终全量于 20:38:28 完成：57 suite、410/410 通过，0 failed/aborted/canceled/ignored/pending，exit 0，实际 sbt 耗时 5066 秒。前后冻结检查均 OK (7 files)，被测前后 HEAD 均为上述 SHA，tracked 工作区干净。逐 suite XML 仅统计本次开始时间之后、命令为全量 test 的结果；日志总数与 XML 汇总一致。

| suite | cc60ebf 首次全量通过/总数 | 62ceea9 最终全量通过/总数 |
| --- | --- | --- |
| `flow.backend.BackendBehaviorSpec` | 16/16 | 16/16 |
| `flow.backend.BackendContractSpec` | 39/39 | 39/39 |
| `flow.backend.HpmSpec` | 1/1 | 1/1 |
| `flow.backend.MduBoundarySpec` | 3/3 | 3/3 |
| `flow.backend.MduTimingSpec` | 3/3 | 3/3 |
| `flow.backend.ScoreboardSpec` | 3/3 | 3/3 |
| `flow.backend.TraceProtocolSpec` | 2/2 | 2/2 |
| `flow.backend.WritebackSpec` | 2/2 | 2/2 |
| `flow.cache.BreezeAmoAluSpec` | 2/2 | 2/2 |
| `flow.cache.BreezeParallelLookupSpec` | 1/1 | 1/1 |
| `flow.cache.L1ICacheSpec` | 3/3 | 3/3 |
| `flow.cluster.ClusterIsaSpec` | 8/8 | 8/8 |
| `flow.cluster.ClusterProgramSpec` | 4/4 | 4/4 |
| `flow.config.BreezeCoreConfigSpec` | 8/8 | 8/8 |
| `flow.core.BreezeCsrPipelineSpec` | 2/2 | 2/2 |
| `flow.core.BreezePrivilegeSpec` | 17/19 | 19/19 |
| `flow.core.BreezeRegisterStorageSpec` | 2/2 | 2/2 |
| `flow.core.CSRFileSpec` | 7/7 | 7/7 |
| `flow.core.MulDecodeSpec` | 1/1 | 1/1 |
| `flow.core.RegFileSpec` | 1/1 | 1/1 |
| `flow.divider.DivProtocolSpec` | 2/2 | 2/2 |
| `flow.divider.DivUnitSpec` | 1/1 | 1/1 |
| `flow.divider.UnsignedRadix4DividerSpec` | 2/2 | 2/2 |
| `flow.fase.FlightRecorderSpec` | 1/1 | 1/1 |
| `flow.fpu.BreezeFpDecoderSpec` | 2/2 | 2/2 |
| `flow.fpu.FpUnitSpec` | 5/5 | 5/5 |
| `flow.frontend.BreezeBTBSpec` | 2/2 | 2/2 |
| `flow.frontend.BreezeCompressedDecoderSpec` | 1/1 | 1/1 |
| `flow.frontend.BreezeFrontendFE001Spec` | 1/1 | 1/1 |
| `flow.frontend.BreezeFrontendFE002Spec` | 1/1 | 1/1 |
| `flow.frontend.BreezeFrontendGShareSpec` | 4/4 | 4/4 |
| `flow.frontend.BreezeFrontendSpec` | 2/2 | 2/2 |
| `flow.frontend.BreezeInstrRealignerSpec` | 3/3 | 3/3 |
| `flow.frontend.BreezePHTSpec` | 3/3 | 3/3 |
| `flow.frontend.MiniDecodeSpec` | 1/1 | 1/1 |
| `flow.memsys.L1DCacheSpec` | 39/39 | 39/39 |
| `flow.memsys.L1DL2LitmusSpec` | 14/14 | 14/14 |
| `flow.memsys.L1DL2MultiCoreFaultSpec` | 20/20 | 20/20 |
| `flow.memsys.L1DL2MultiCoreSpec` | 21/21 | 21/21 |
| `flow.memsys.L1DL2SystemSpec` | 12/12 | 12/12 |
| `flow.memsys.L1DPermissionsSpec` | 5/5 | 5/5 |
| `flow.memsys.L1IClientSpec` | 2/2 | 2/2 |
| `flow.memsys.L2HomeSpec` | 12/12 | 12/12 |
| `flow.memsys.MemAgentsSpec` | 10/10 | 10/10 |
| `flow.memsys.MemSkeletonElabSpec` | 37/37 | 37/37 |
| `flow.memsys.MemoryBridgeSpec` | 3/3 | 3/3 |
| `flow.mmu.BreezeMmuAdSpec` | 5/5 | 5/5 |
| `flow.mmu.BreezeMmuSpec` | 5/5 | 5/5 |
| `flow.mmu.BreezeParallelTranslatorSpec` | 2/2 | 2/2 |
| `flow.mmu.BreezePmpSharingSpec` | 1/1 | 1/1 |
| `flow.mmu.sv39.Sv39MmuSpec` | 17/17 | 17/17 |
| `flow.mmu.sv39.Sv39StructuresSpec` | 13/13 | 13/13 |
| `flow.multiplier.MulEquivalenceSpec` | 1/1 | 1/1 |
| `flow.multiplier.MulProtocolSpec` | 4/4 | 4/4 |
| `flow.multiplier.MulUnitSpec` | 2/2 | 2/2 |
| `flow.multiplier.SignedMul65x65Spec` | 22/22 | 22/22 |
| `flow.platform.BreezeLinuxPmaSpec` | 5/5 | 5/5 |
| **总计** | **408/410，exit 1** | **410/410，exit 0** |


### 第二轮最终 expectedUnsupported 与边界

`ClusterIsaSpec.scala` 未修改；列表保持原来的四个程序名：

- `rv64ui-p-ma_data`：L1D misaligned cause 4/6 与 MMU closure C2 规定非对齐访问 trap，要求 tohost=`1 | 1337`（0x539）。本轮三次运行均达到指定结束值。
- `rv64ua-p-amocas_w`、`rv64ua-p-amocas_d`、`rv64ua-p-amocas_q`：InstDecode 未实现 Zacas，若有 ELF 必须到达 tohost 非 1 且 `(value & 1337) == 1337`。当前三个 ELF 因工具链不支持 Zacas 未构建，按 NOT_BUILT 明确报告，未视为 DUT PASS。

breakpoint 未加入列表，实际必须且已经 tohost=1。所有程序、原有扫描、断言、golden、反压和看门狗检查保留。R1 的冻结修订仅来自 Claude 起点提交；本轮测试修正未触及冻结文件或 `L1DCoreIO`。


### 最终复核与仍失败/未运行项

- 最终 `62ceea9` 全量再次包含 ClusterIsaSpec 8/8、ClusterProgramSpec 4/4；四个 R1/R2 目标程序均 tohost=1，周期仍分别为 illegal 4701、dirty 2892、icache-alias 3187、breakpoint 1934。
- 最终完整 ISA 的 238 次实际执行、逐程序 cycles/retired/tohost 及 6 次 NOT_BUILT 清单保存在最终证据根 `isa-results.json`：235 次普通 PASS，3 次 ma_data 达到指定 0x539，0 次非预期失败。
- 最终全量中的 25 次自写程序均 PASS，逐程序 cycles、retired、hart 结果与上面的首次独立程序表逐项一致；结构化结果另存最终证据根 `program-results.json`。控制台、AXI、oracle、断言和看门狗均按原检查执行。
- 最终证据根 `full-results.json` 为 57 个 suite/410 个测试的结构化结果；`08-full-reports/` 保留原始 XML，`08-full.sha`、`final-source.sha` 均为最终被测 SHA，`final-source.status` 为空，冻结前后检查均 7/7。
- 没有仍失败的已执行测试，没有遗漏任务要求的命令。三个 Zacas ELF 仍 NOT_BUILT；ma_data 的三次指定 trap 结束仍属于原有 expectedUnsupported，不算普通程序 PASS。
- 本轮新增修复只有 R2 旧 CSR 测试同步，未再修改 RTL/规格/冻结文件或 L1DCoreIO。起点中的 R1/R2 RTL 与规格修订由 Claude 提供，已按本任务顺序验证；首轮报告保留不变。
