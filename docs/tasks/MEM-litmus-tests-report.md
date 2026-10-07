# MEM 多核 litmus 测试报告

日期：2026-10-07。按用户指定从 `52cd8f0` 开始，先完成 [多核 fault 任务](MEM-multicore-fault-tests-report.md)，再执行本任务。生产 RTL 没有改动。最终代码 `e232d2f9d8cccdff6098ca0a8a159eedbf7e82ee` 上模块 111/111、单核系统 12/12、旧多核 21/21、fault 20/20、litmus 14/14，exit 均为 0；没有失败或跳过。

## 环境、版本与命令

- Alan 独立 cwd：`/home/chen/FUN/flow-mem-fault-litmus-20261007/design`，保留主 checkout 和其他任务。
- 证据根：`/home/chen/FUN/flow-runs/20261007-mem-fault-litmus/`；每次运行的 `source.sha`、`cwd`、`command`、`start/end`、`exit`、`run.log`、`reports/` 绑定具体 SHA；失败生成源码和模拟输入/日志另行归档。`summary.json` 从各次 XML 提取 spec 计数和命令墙钟耗时。
- 工具：flow 环境、sbt 1.9.7、Java 11.0.32.1、Chisel 7.0.0、Verilator 5.028。CVFPU 及嵌套依赖保持仓库锁定 SHA，详见 fault 报告环境表。
- `52cd8f0`：起点，两份新套件及生产代码均编译通过。
- `b2393c97ec8c1ea1eb21ab45df47ea99b80ff5cd`：先完成 fault 修复与四条门槛；本任务首次完整运行，4/14、exit=1（`12-litmus-initial/`）。
- `047b97749da5818c6ef022d7702787d121d22173`：修复轮间地址复用和 spread 的 set 布局；完整运行 12/14、exit=1（`14-litmus-layout-fixed/`）。
- `cd379d3`：取整去重后补足任务书名义轮数。
- `e232d2f9d8cccdff6098ca0a8a159eedbf7e82ee`：为 ISA2/IRIW 追加扩大网格，保留全部原网格。

四条任务命令，均在上述 Alan cwd 执行：

```sh
sbt "testOnly flow.config.BreezeCoreConfigSpec flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
sbt "testOnly flow.memsys.L1DL2SystemSpec"
sbt "testOnly flow.memsys.L1DL2MultiCoreSpec"
sbt "testOnly flow.memsys.L1DL2LitmusSpec"
```

初次 litmus 运行前，前三条已在 `b2393c9` 完整通过 111/111、12/12、21/21，exit=0；fault 也通过 20/20。litmus 修改限 `L1DL2LitmusSpec.scala`，共享驱动、oracle、RTL 与 spec 不变。

## 最终同 SHA 回归

以下全部在 `e232d2f9d8cccdff6098ca0a8a159eedbf7e82ee` 上运行，XML 计数与 `exit` 一致，全部无失败、跳过或 suite 中止。前三条全部通过后才执行完整 litmus。另行重跑 fault 20/20、exit=0（`19-final-fault/`），确认两份任务合并后的共享测试设施兼容。

| 命令 / spec | 通过 / 总数 | exit | 证据子目录 / 命令耗时 |
| --- | --- | --- | --- |
| 第 1 条：BreezeCoreConfigSpec | 8/8 | 0 | `16-final-modules/`，整条 103 s |
| L1DCacheSpec | 39/39 | 0 | 同上 |
| L1DPermissionsSpec | 5/5 | 0 | 同上 |
| L2HomeSpec | 12/12 | 0 | 同上 |
| MemAgentsSpec | 10/10 | 0 | 同上 |
| MemSkeletonElabSpec | 37/37 | 0 | 同上 |
| 第 2 条：L1DL2SystemSpec | 12/12 | 0 | `17-final-system/`，整条 76 s |
| 第 3 条：L1DL2MultiCoreSpec | 21/21 | 0 | `18-final-multicore/`，整条 468 s |
| 第 4 条：L1DL2LitmusSpec | 14/14 | 0 | `20-final-litmus/`，整条 725 s |

四条任务命令合计 1372 s；完整 litmus 的命令墙钟耗时为 725 s。`15-litmus-expanded-shapes/` 的 ISA2/IRIW 单独复跑 2/2、exit=0，耗时 763 s；ISA2 链命中 400 次，IRIW 两项命中 197/186 次，随后完整套件再次得到相同分布。

14 个形状总计 **7014 轮**，均达到各自原名义最小轮数（原合计 4800），每个必需结果仍要求至少 8 次，所有禁止结果均未出现。各形状均完成 monitor idle、无协议违规、逐字 oracle 和全量 DMA 最终读回。报告提交只改文档；其生产和测试源码与上述被测 SHA 一致。被测及报告提交的 `design` Git tree 均为 `b052df3986e179650a5869c587810f5d20e0018a`；最终 push 后的提交和树核对记录在证据根 `final-doc-commit`、`final-doc-design-tree`。

| 形状 | T / 拍 | 实际轮数 |
| --- | --- | --- |
| MP+fence.rw.rw | 20 | 427 |
| MP+amoswap.rl+amoor.aq | 19 | 406 |
| MP+amoswap.rl+lr.aq | 18 | 385 |
| MP (no fence, coverage) | 19 | 232 |
| SB+fence.rw.rw | 19 | 385 |
| LB+fence.rw.rw | 18 | 438 |
| CoRR | 19 | 384 |
| CoWR | 20 | 384 |
| CoWW | 20 | 384 |
| CoRW | 20 | 384 |
| 2+2W+fence.rw.rw | 20 | 405 |
| WRC+fence.rw.rws | 19 | 256 |
| ISA2+fence.rw.rws | 18 | 1264 |
| IRIW+fence.rw.rws | 20 | 1280 |

下面逐项保留 `20-final-litmus/run.log` 的分布、必需结果命中数及扫描范围；范围单位为拍，T 为该形状实际校准值，`0` 为该轮预热后的旧值。

```text
MP+fence.rw.rw: T=20 cycles, 427 rounds, oracle (load,1285) (store,1293)
MP+fence.rw.rw scan: 61 unique points, 7 passes, nominal minimum 384 rounds
MP+fence.rw.rw outcomes: [r1=0 r2=1]=252, [r1=1 r2=1]=103, [r1=0 r2=0]=72
MP+fence.rw.rw required 'flag new': 103 hits (d -3..40)
MP+fence.rw.rw required 'flag old': 324 hits (d -20..40)
MP+amoswap.rl+amoor.aq: T=19 cycles, 406 rounds, oracle (amo,812) (load,819) (store,823)
MP+amoswap.rl+amoor.aq scan: 58 unique points, 7 passes, nominal minimum 384 rounds
MP+amoswap.rl+amoor.aq outcomes: [r1=0 r2=1]=272, [r1=1 r2=1]=105, [r1=0 r2=0]=29
MP+amoswap.rl+amoor.aq required 'flag new': 105 hits (d -9..38)
MP+amoswap.rl+amoor.aq required 'flag old': 301 hits (d -19..38)
MP+amoswap.rl+lr.aq: T=18 cycles, 385 rounds, oracle (amo,385) (load,1159) (lr,385) (store,781)
MP+amoswap.rl+lr.aq scan: 55 unique points, 7 passes, nominal minimum 384 rounds
MP+amoswap.rl+lr.aq outcomes: [r1=0 r2=1]=266, [r1=1 r2=1]=90, [r1=0 r2=0]=29
MP+amoswap.rl+lr.aq required 'flag new': 90 hits (d -3..36)
MP+amoswap.rl+lr.aq required 'flag old': 295 hits (d -18..36)
MP (no fence, coverage): T=19 cycles, 232 rounds, oracle (load,701) (store,708)
MP (no fence, coverage) scan: 58 unique points, 4 passes, nominal minimum 192 rounds
MP (no fence, coverage) outcomes: [r1=0 r2=1]=108, [r1=1 r2=1]=65, [r1=0 r2=0]=54, [r1=1 r2=0]=5
SB+fence.rw.rw: T=19 cycles, 385 rounds, oracle (load,1159) (store,1166)
SB+fence.rw.rw scan: 77 unique points, 5 passes, nominal minimum 384 rounds
SB+fence.rw.rw outcomes: [r0=1 r1=1]=207, [r0=0 r1=1]=90, [r0=1 r1=0]=88
SB+fence.rw.rw required 'P0 first (r0=0 r1=1)': 90 hits (d -1..38)
SB+fence.rw.rw required 'P1 first (r0=1 r1=0)': 88 hits (d -38..3)
LB+fence.rw.rw: T=18 cycles, 438 rounds, oracle (load,1321) (store,1325)
LB+fence.rw.rw scan: 73 unique points, 6 passes, nominal minimum 384 rounds
LB+fence.rw.rw outcomes: [r0=0 r1=0]=204, [r0=1 r1=0]=117, [r0=0 r1=1]=117
LB+fence.rw.rw required 'P0 first (r0=0 r1=1)': 117 hits (d -10..36)
LB+fence.rw.rw required 'P1 first (r0=1 r1=0)': 117 hits (d -36..9)
CoRR: T=19 cycles, 384 rounds, oracle (load,4517) (store,588)
CoRR scan: 192 unique points, 2 passes, nominal minimum 384 rounds
CoRR outcomes: [r1=1 r2=1]=242, [r1=0 r2=1]=100, [r1=0 r2=0]=42
CoRR required 'first read new': 242 hits (d -7..38, g 0..19)
CoRR required 'store between the reads (r1=0 r2=1)': 100 hits (d -19..10, g 0..19)
CoWR: T=20 cycles, 384 rounds, oracle (load,4325) (store,972)
CoWR scan: 192 unique points, 2 passes, nominal minimum 384 rounds
CoWR outcomes: [r0=a fx=b]=141, [r0=a fx=a]=130, [r0=b fx=b]=113
CoWR required 'read other store (r0=b)': 113 hits (d -6..23, g 0..20)
CoWR required 'final a': 130 hits (d -20..6, g 0..20)
CoWR required 'final b': 254 hits (d -6..40, g 0..20)
CoWW: T=20 cycles, 384 rounds, oracle (load,8453) (store,972)
CoWW scan: 192 unique points, 2 passes, nominal minimum 384 rounds
CoWW outcomes: [r1=2 r2=2 fx=2]=143, [r1=1 r2=2 fx=2]=87, [r1=0 r2=1 fx=2]=73, [r1=0 r2=0 fx=2]=45, [r1=0 r2=2 fx=2]=24, [r1=1 r2=1 fx=2]=12
CoWW required 'intermediate value observed': 172 hits (d -20..22, g 0..20)
CoRW: T=20 cycles, 384 rounds, oracle (load,4325) (store,972)
CoRW scan: 192 unique points, 2 passes, nominal minimum 384 rounds
CoRW outcomes: [r0=0 fx=b]=150, [r0=b fx=a]=120, [r0=0 fx=a]=114
CoRW required 'read other store (r0=b)': 120 hits (d -20..6, g 0..20)
CoRW required 'final a': 234 hits (d -20..29, g 0..20)
CoRW required 'final b': 150 hits (d 8..40, g 0..20)
2+2W+fence.rw.rw: T=20 cycles, 405 rounds, oracle (load,411) (store,2036)
2+2W+fence.rw.rw scan: 81 unique points, 5 passes, nominal minimum 384 rounds
2+2W+fence.rw.rw outcomes: [fx=b2 fy=a2]=225, [fx=b2 fy=b1]=91, [fx=a1 fy=a2]=89
2+2W+fence.rw.rw required 'interleaved (x=b2 y=a2)': 225 hits (d -35..35)
2+2W+fence.rw.rw required 'P1 first (x=a1 y=a2)': 89 hits (d -40..-2)
2+2W+fence.rw.rw required 'P0 first (x=b2 y=b1)': 91 hits (d 3..40)
WRC+fence.rw.rws: T=19 cycles, 256 rounds, oracle (load,1158) (store,780)
WRC+fence.rw.rws scan: 256 unique points, 1 passes, nominal minimum 256 rounds
WRC+fence.rw.rws outcomes: [r1=1 r2=0 r3=1]=146, [r1=0 r2=0 r3=1]=39, [r1=0 r2=0 r3=0]=30, [r1=0 r2=1 r3=1]=23, [r1=1 r2=1 r3=1]=14, [r1=1 r2=0 r3=0]=4
WRC+fence.rw.rws required 'chain observed (r1=1 r2=1)': 14 hits (d1 -4..38, d2 34..38)
ISA2+fence.rw.rws: T=18 cycles, 1264 rounds, oracle (load,6645) (store,5698)
ISA2+fence.rw.rws scan: 1264 unique points, 1 passes, nominal minimum 256 rounds
ISA2+fence.rw.rws outcomes: [r1=1 r2=1 r3=1]=400, [r1=1 r2=0 r3=1]=319, [r1=0 r2=1 r3=1]=279, [r1=0 r2=0 r3=1]=211, [r1=0 r2=0 r3=0]=55
ISA2+fence.rw.rws required 'chain observed (r1=1 r2=1)': 400 hits (d1 -2..108, d2 22..108)
IRIW+fence.rw.rws: T=20 cycles, 1280 rounds, oracle (load,7687) (store,3852)
IRIW+fence.rw.rws scan: 1280 unique points, 1 passes, nominal minimum 256 rounds
IRIW+fence.rw.rws outcomes: [a=1 b=1 c=1 d=1]=610, [a=1 b=1 c=1 d=0]=186, [a=1 b=0 c=1 d=1]=178, [a=0 b=1 c=0 d=0]=93, [a=0 b=0 c=0 d=1]=81, [a=1 b=1 c=0 d=1]=35, [a=0 b=1 c=0 d=1]=22, [a=0 b=0 c=0 d=0]=20, [a=1 b=0 c=0 d=1]=19, [a=0 b=1 c=1 d=1]=16, [a=1 b=1 c=0 d=0]=16, [a=0 b=0 c=1 d=1]=4
IRIW+fence.rw.rws required 'P2 sees x first (a=1 b=0)': 197 hits (w 25..160, s 2..120)
IRIW+fence.rw.rws required 'P3 sees y first (c=1 d=0)': 186 hits (w -160..-37, s 7..120)
```

| 判据 / 来源 | 刺激与期望 | 验证入口 / 最终证据 |
| --- | --- | --- |
| LIT-ADDR，任务书 §2 | 每轮新行；每三轮同 L1D/L2 set；不得重复分类 | `Inst` 布局断言、oracle classify；14/14 完整扫描 |
| LIT-SCALE，任务书 §2/§5.3 | 保留原点、预热轮换并增加扫描；每形状达到名义轮数 | runShape 轮数断言与上述 scan/rounds 输出 |
| LIT-ORDER，任务书 §3/§5.2 | 原 load/store、FENCE、aq/rl 程序；原禁止结果不得出现 | 每轮 forbidden、CoherenceMonitor；上述全部形状 |
| LIT-HIT，任务书 §3/§5.3 | 各必需结果 >=8，覆盖其时序前提 | 原 required 断言；上述命中数与扫描范围 |
| LIT-DATA，任务书 §2/§5.4 | 只读旧值或本轮写值、不回退、完成且无 kill；最终值匹配 | Inst.label、CoreDriver、oracle、所有行 DMA 读回 |

环境仍为行为 AXI/驱动调度和 `L1DCoreIO`；这些结果只支持本报告列出的仿真覆盖，没有添加公平性假设。

## 测试修复与依据

### 轮间地址复用

原地址为 `0x80100000 + r*0x100 + i*32 + spread*i*stride`。两核 stride=16384：第 0 轮 spread 的 y 和第 64 轮非 spread 的 y 都是 `0x80104020`；四核 stride=32768 时第 128 轮同样复用 `0x80108020`。10 个多变量形状因此触发 oracle 的严格“字重复分类”检查，尚未完成扫描；四个同址形状 384 轮全部通过。没有删除重复分类检查。

依据是任务书 §2“每轮新行、唯一值”。现每轮分配 `stride*(变量数+1)` 的独立区间，保留原 `r*0x100 mod stride` 的 set 轮换；变量地址只在该区间内使用相邻行或 stride 间隔。所有原轮、变量、程序和标签检查保持，地址不再跨轮复用。

### spread 原来不在同 set

原 spread 地址还带 `i*32`，因此虽然增加了 stride，变量 set 仍相邻。任务书 §2 要求每三轮一轮落在同一 L1D set、同一 L2 set。现 spread 的变量间隔仅为 stride，普通轮仍为 32 B；新增不同变量必须分属不同 cache line，以及 spread 同 L1D/L2 set 的构造断言。改变的是刺激布局，没有改变数据或禁止结果期望。

`047b977` 单独复跑 `testOnly flow.memsys.L1DL2LitmusSpec -- -z "MP+fence.rw.rw"` 为 1/1、exit=0（`13-litmus-layout-repro/`）。完整运行 12/14，所有重复分类错误消失，没有禁止结果；仅 ISA2/IRIW 的覆盖阈值失败。

### 拍差去重后的规模

原 `axisValues` 按任务书取整、去重，但 `passes` 固定：例如 MP+fence 的 T=20 时 96 个名义点去重成 61 个，只执行 244 轮；release/acquire 两形状分别只有 232、220 轮，MP 无 fence 116 轮，SB 308 轮，2+2W 324 轮。

用户禁止缩小规模，任务书 §2 给出单轴 384（MP 无 fence 192）、同址 384、二维 256 的名义轮数。保留原取整去重与原完整扫描，增加扫描遍数到 `max(原遍数, ceil(名义轮数/实际点数))`，并断言实际轮数不少于名义值。扫描范围、原点、预热轮换、全部形状与阈值保持；只增加轮数。

### ISA2 覆盖不足

`047b977` 原网格 T=18、256 轮，必需 `r1=1 && r2=1` 只出现 3 次（d1=18..36，d2=32），仍小于阈值 8。已有三个命中证明 RTL 可以产生该时序；覆盖集中于网格边缘，原 d1/d2 上限均为 2T=36，无法充分扫描经 P0 两次写和 P1 传递后的延迟。

按任务书 §5.3 扩大刺激：先跑原 16×16 网格（每轴 −T..2T），再追加每轴 −T..6T 的 32×32 网格，去掉重复点。原 256 轮排在前面，原扫描点、预热次序与检查保留；没有降低阈值或删除必需结果。

### IRIW 覆盖不足

`047b977` 原网格 T=20、256 轮，两项必需 `a=1,b=0`、`c=1,d=0` 都为 0 次。原写者偏移 w 只有 −20..20 拍，读者 s 为 −20..40；双读程序含 FENCE，未命中“一个写已可见、另一个写尚未可见”的窗口。

保留原 16×16 网格在前，再追加 w=−8T..8T、s=−T..6T 的 32×32 网格，使写者之间有足够间隔供读者完成两次读。阈值仍为各 8 次，全部禁止结果保持。扩大后的实际命中用于判定这确为刺激覆盖不足，而不是把“不能产生”当作通过理由。

### 失败诊断

轮内任何失败打印形状、轮号、扫描点、预热状态、T、失败拍和监视器最近 32 条消息，再原样抛出异常。没有新增公平性假设、延长 watchdog、放宽 oracle 或最终 DMA 读回。未出现 RVWMO 禁止结果，因此没有禁止判据修改、生产 RTL 修复或 RTL spec 修订。

## 覆盖边界

只覆盖两核、四核默认几何，三参与核形状使用四核硬件、第四核空闲。驱动边界为 `L1DCoreIO`，不含后端重排或真实指令流；PTW/dTLB/MMIO 不参与本套件。未覆盖 stress 几何、SC、混合访问尺寸、其他细分 FENCE 类型，也不注入 kill/TLB miss。预热组合仍轮换而非全交叉。未运行形式化、综合、布局布线、PPA、FPGA 或 Linux。
