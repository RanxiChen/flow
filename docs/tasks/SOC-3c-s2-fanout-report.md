# SOC-3c：S2 fanout 执行报告

状态（2026-10-08）：**§8/§9 已授权恢复，功能门槛补跑中**。S09/S13 与新增同拍 kill 反向用例在 `ae435f1` 上通过 3/3；D1/D2/CSR 补跑仍在进行。补充真实 skid + backend 的 D1 集成用例，并修正新增 D1 陷入目标误写为 0 的刺激预期（Linux CSRFile 的 mtvec 复位值为 0x200）。尚未启动本轮 Vivado，尚无单核/四核 routed 100 MHz 或 bitstream/上板证据。以下 §0–§8 保留之前停止时的历史记录；最新推进记录见 §9。

## 0. SOC-3b 提交与本轮版本

- SOC-3b 实现提交：`7116e32e98de19db2d0b7ea3676cecb3f76b2534`，分支 `feat/pcie-fase-20260920`，cwd `/home/chen/leisure/flow`。仅提交任务 §0 指定的 SOC-3b RTL、测试、规格、frozen.json、任务/报告及 records/soc3b-*；其它工作区改动保留。
- SHA 已回填 [SOC-3b 报告 §9 末尾](SOC-3b-wb-split-report.md)，回填文本随本轮实现提交落盘。
- SOC-3c RTL/规格提交：`0d4cb261fddee341d095d649fd3531f237b6f170`。
- 新增前端测试回填响应安排修正：`435e877d6746acff7c56d92671fff9b05558a11e`，在已发出的请求被接受后才送回填。
- 新增后端保持场景刺激修正：`2dea0b7689d270a14cd59cfff4a99cc66fa98ec2`，load 与分支/FP 间插入 nop，使 EX 指令与 load 的 WB/S2 保持重叠；新增陷入覆盖用例的快路拍相应为 3。现有测试未因此修改。
- **本轮停止诊断版本为 `2dea0b7`**。从 `0d4cb26` 到该版本仅改上述两个新增测试文件，RTL/规格相同；先前版本的通过结果仍按实际 SHA 列出，不冒充最后版本的全量复跑。
- `21e3b18` 是 SOC-3b 既有验证快照，不将其测试或时序结果转记为 `7116e32` 或 SOC-3c 的重新验证。

## 1. 停止原因与集中待裁定项

任务 §6 D3 明确要求：“若有已有测试依赖消费拍取消语义而失败，停下报告该测试，不要自行改。”在 `2dea0b7` 原样运行两条相关测试，结果如下。

| 现有测试及期望位置 | 原期望 | 实测 | 推导 |
| --- | --- | --- | --- |
| `BackendContractSpec.scala:472–473`，`S09_BTB_training_waits_for_held_WB_once` | BTB 在拍 8；PHT/GHR 在拍 2；BTB 不与 WB hold 重叠 | 首项比对失败：BTB `List(3)`，而非 `List(8)`；之后 expect 未执行 | 分支 EX 真推进在拍 2，训练包在拍 3消费。较老 load 的 WB 被保持至拍 8，D3 禁止消费拍再用 downHold 延迟训练 |
| `BackendContractSpec.scala:479`，`S13_WB_fault_discards_younger_pending_BTB_training` | BTB 为空 | BTB `List(3)`，非空；之后 expect 未执行 | 分支在拍 2 决策时 wbKill=0，拍 3消费；较老 load 在拍 8 陷入，D3 不再取消已决策训练 |

证据：[诊断日志](../../records/soc3c-2dea0b7/cloud/training-stop-audit/run.log)、[JUnit XML](../../records/soc3c-2dea0b7/cloud/training-stop-audit/xml/TEST-flow.backend.BackendContractSpec.xml)、[完整运行元数据](../../records/soc3c-2dea0b7/cloud/training-stop-audit/result.json)。结果 0/2，exit 1，80.383 s（含编译；测试本身 42.660 s）。

**待裁定只有一项：是否授权把这两条现有训练测试同步到 D3 的“决策后下一拍无条件消费”语义。** 需要同步的检查包括第一条的 BTB/PHT/GHR 消费拍与 hold 交集，以及第二条的 BTB 非空预期；本轮未改。若允许同步，应保留检查数量，继续检查单次训练、决策拍 !wbKill、训练内容和架构副作用。当前这两项消费语义检查不在 T01–T22/P01–P10 的冻结拍数行中；拍数合同保持原文。

触发后未启动新的仿真、生成或 Vivado 作业。尝试终止已运行的另一批次时，首次 SSH 被本地沙箱阻止；获准重试时该批次已自然结束，目标 PID 不存在。未误杀其它进程，所有结果保留，当前无本任务作业在运行。

## 2. C1–C4 实现

### C1：FP 发起与 WB/S2 解耦

`design/src/main/scala/backend/BreezeBackend.scala:105,328–359` 增加 exFpIssued。请求 valid 只含 exFpLong、!exFpIssued、寄存的 hartFatal/stopped，不含 downHold/wbKill/allowEx；FP resourceWait 与 allowEx 并行。每次 EX 更换、kill 或停止时清 issued，保持期 fire 后置位。

`design/src/main/scala/fpu/FpUnit.scala:53–55,90–125` 移除分配准入的组合 kill 门控。同拍 fire/kill 的新项显式 valid=0，allocate 正常递增；commitCursor 跳过该死项，保留 commit-before-kill，killDrain 防止尚未归还的 tag 被复用。返回仍按原规则丢弃作废项。

`FlowFpnewWrapper.sv:37–42` 的 ADDMUL 各格式 PipeRegs=3/4/1/1/1，DIVSQRT=2，NONCOMP=1，CONV=4，无零级 opgroup，本轮未改 CVFPU 配置。新增 D2 用例包含同拍 fire/kill、killDrain 阻塞和后续 40 条连续 FMA、tag 回绕、结果/flags/目的寄存器比对；**首次运行因依赖缺失而 suite abort，补齐依赖后因 D3 停止未复跑，因此 D2 动态门禁未通过**。

### C2：快路、慢路与 skid

`BreezeBackend.scala:351–359,397–458,541` 的 EX 快路由 EX 状态、寄存旁路操作数和 exRedirectSent 决定。EX 保持时 redirect 不清 ex.valid；只禁止错路 ID 同拍离开，并清入口 skid。JALR BTB 判断及 mem.predictionMiss 使用 `exRedirectSent || branchRedirect`。下一条纠错后指令进入 EX 时不再重复清空。

新增 `design/src/main/scala/frontend/BreezeFrontendBoundary.scala`：慢路 redirect 寄存一拍，应用拍慢路目标优先于快路。入口 2 项 Queue，flow=true、pipe=false，空时直通；前端 ready 取队列已寄存占用状态。快路、WB 慢路产生拍和慢路应用拍均清 skid，避免间隙到达的错路项存活。

`design/src/main/scala/top/BreezeCluster.scala` 在 FetchBuffer 与 backend 间接入 boundary；PC、FetchBuffer flush、FetchTlbClient kill 使用应用后的 redirect。backend 仍保留原 io.frontendRedirect 作为 WB 当拍观察事件，新增快/慢接口负责功能连接；RegNext(wbKill) 禁止 ID 在慢路间隙离开。

`design/src/main/scala/frontend/BreezeFrontend.scala` 将 flush-only 事件纳入 translator/realigner/S2/S3 的取消条件，支持 SFENCE。PC 更新仍由 redirect.valid 控制，cacheFlush 随慢路延迟一拍应用。

后端 D1 新用例覆盖 held EX 分支保留、快路一次性、错路无写回、正确路径退休，以及快路后 WB 陷入覆盖。**这些新增 backend 用例 suite abort，尚无动态通过结论**；目前 backend 与真实 skid 的组合 D1 场景还需补充集成用例，不能用两个分开的组件测试冒充 §6 D1 全场景通过。

BTB/PHT/GHR 在 `BreezeBackend.scala:413–437` 由同一 EX 推进决策采样为一拍寄存训练包（各接口分别寄存）；次拍直接输出，无 wbKill/downHold/exAdvance 消费门控。决定拍仍含 !wbKill。上述两条现有测试冲突正发生在这一批准的边界变化上。

### C3：S2 结局与分配选择分开

`design/src/main/scala/l1d/L1DBundles.scala:114–116` 增加同 MSHR 行、同 WB 行和同 MSHR set 的内部预比较位，原有字段语义保持。

`L1DCache.scala:255–265,315–324` 在 S1 捕获同行/同 set 比较，用本拍 alloc 的下一项地址转发；被保持的 CPU/internal S2 项在新分配拍更新对应比较位，重新检查项随之复制。`349–363` 使用命中向量直接一热选行状态，S2 不再经编码 way 再选择状态；同行阻塞取匹配位与当前状态 valid。

`397–400` 的 WB 容量判断取 wbValid、是否有无效 way 和 shared ownership 命中事实，不经 victim/allocWay 数据选择。空闲 MSHR 下不存在其锁定 way，无效 way 优先和共享升级复用命中 way 使该容量判断与原选择规则一致。victim/allocWay、数据及分配地址仍留在分配寄存器 D 端；协议、S0/S1/S2 拍数、一致性流程保持。

已有 L1DCache/Permissions/Context 共 53 条通过（实际 SHA `435e877`）。尚未完成其它 SOC-3 定向、双核/litmus/fault 集成与网表结构查询，不能据此声称所有结局锥已达到 ≤10 级或协议全覆盖。

### C4：HPM 原始事件寄存

`design/src/main/scala/core/BreezePerformanceCounters.scala:36–38,72–88` 寄存 rawEvents 与发生拍的 selector/inhibit，次拍选择并计数；软件写 counter 优先，并丢弃写前未可见和写同拍事件，防止下拍再次累计。mcycle/minstret 维持原实现。

## 3. 规格、断言与冻结范围

- 已按 §6 D1 同步 `backend-rtl-spec.md` A08/S09 与 `backend-v1-rtl-spec.md`：held WB 禁止慢路、退休、CSR 与训练决策，允许 EX 快路一次性。S09 增加 `!(branchRedirect && exRedirectSent)`，保持原 SFENCE 一次性与 held WB 不退休检查。
- S13 只为 FP 同拍 fire/kill 加授权例外；L1D/MUL/DIV kill 拍禁发仍保留。FpUnit S05 改为下一拍被 kill 新项无效且 killDrain 有效，以及 killDrain 期间禁止 fire；原 speculative FP write 等检查保留。
- 已按 D3 同步训练的决策/消费边界，按 D4 同步 HPM 可见延迟与覆盖归属。
- `tools/frozen.json` 仅更新已有 backend-rtl-spec 的哈希，冻结文件数仍为 8。`backend-timing-contract.md` 未修改；MUL/DIV 本体、L1D 对外协议、综合/实现策略与约束均未修改。
- [冻结检查](../../records/soc3c-2dea0b7/static/frozen-check.txt) 为 OK (8 files)；[冻结内容哈希](../../records/soc3c-2dea0b7/static/frozen-spec-sha256.json)与[源版本关系](../../records/soc3c-2dea0b7/static/source-provenance.json)一并保存。

## 4. HPM 每处 expect 调整

下表行号为 `2dea0b7` 源码。所有 expect/check() 与随机 400 拍、种子 `0x435352` 保留，不使用 RTL 输出生成期望。

| 文件:行 | 旧值 → 新值 | 延迟推导 |
| --- | --- | --- |
| `design/src/test/scala/backend/HpmSpec.scala:20` | 2 → 0 | WB_PORT_CONFLICT 的首个 +2 事件尚在事件寄存级 |
| 同文件:21 | 4 → 2 | 第二个 +2 尚未可见，仅第一个已计入 |
| 同文件:25 | 13 → 11 | 拍 24 软件写 11 同拍的事件被覆盖，不能在次拍累计；拍 25 自身事件仍在寄存级 |
| 同文件:27 | 15 → 13 | selector 写拍仍用旧 selector 的发生拍归属；此时只累计上一拍 +2，本拍 +2 留至拍 28 |
| `design/src/test/scala/core/RegFileCsrFileSpec.scala:384` | 3 → 2 | 连续三拍控制退休事件，第三拍尚未计入；下一 inhibit 写拍会排空前一事件，原后续 3 的检查保持 |
| `design/src/test/scala/core/BreezeCsrPipelineSpec.scala:67`，由 :71/:72 的 HPM machine/user alias 调用 | `oldCount + currentAttributedIncrement` → `newCount + previousAttributedIncrement`；软件写时均为写值 | 独立 delayedIncrements 由刺激、旧 selector/inhibit、CSR 写资格计算；发生拍软件写丢弃对应 pending/current，复位清 pending。其它 CSR 期望规则保持 |

模型所驱动的变值 expect 逐次列在 [HPM expect 附表 CSV](../../records/soc3c-2dea0b7/static/hpm-expect-changes.csv)：577 个刺激拍，含初始/复位共 9264 次 HPM machine/user 检查，2866 次期望变化；每行包含拍号、counter、CSR、expect 位置、旧值→新值及前/本拍归属增量。[生成脚本](../../records/soc3c-2dea0b7/static/hpm-expect-audit.py)只重放原刺激及 Java/Scala 随机流，不读取 RTL 或仿真输出。HpmSpec 和 BreezeCsrPipelineSpec 动态通过；CSRFileSpec 尚未执行，不能将批准的期望调整等同测试通过。

## 5. C2(b) 动态取证

实际运行 `BreezeFrontendSoc3cSpec`，SHA `435e877`，6/6，suite 时间 34.843 s。[XML](../../records/soc3c-435e877/cloud/targeted-new-components/xml/TEST-flow.frontend.BreezeFrontendSoc3cSpec.xml)及[批次日志](../../records/soc3c-435e877/cloud/targeted-new-components/run.log)保留。

| 场景 | 测试环境及检查 | 结论/范围 |
| --- | --- | --- |
| FENCE.I 慢路间隙取指及旧回填 | 真 BreezeFrontend、FetchBuffer、boundary、FetchTlbClient；分别在慢路事件后偏移 0/1/3 拍返回旧 line，慢路应用拍 cacheFlush，重新取回不同新指令，对输出指令比对 | 3 条通过，旧指令未在 flush 后输出，新指令正确；覆盖模块集成的所测响应相位 |
| SFENCE 旧翻译请求结果 | block 保持、已有翻译请求跨越间隙/应用 kill；旧响应在应用拍和之后返回，检查无旧取指/输出，再开放新翻译 | 1 条通过，旧结果丢弃、重新请求可推进 |
| 同拍快/慢路 | 应用快路后，次拍寄存慢路目标优先 | 1 条通过 |
| skid 满/空/错路清空 | 保持时保存两条纠错路径项；WB 产生拍与下一应用拍分别送错路项，检查都清空；空时直通 | 1 条通过；独立 boundary，未连接真实 backend |

本证据没有将后端 D1 全场景、已有前端回归、整 Cluster 或 ClusterIsa 判作通过。

## 6. 执行主机与门槛表

各次编译/仿真前重读共享 host 配置并先校验 cloud_chen；连接、环境和资源可用，未触发 Alan fallback。实际主机 `cloud_chen@47.96.71.231` / `iZbp16rhtg91v96m32vggjZ`，Java 11.0.32.1、项目 sbt 1.9.7、Verilator 5.028、缓存 firtool 1.128.0。配置快照和工具记录在 [static](../../records/soc3c-2dea0b7/static)。Vivado 仍计划用 Alan，每个独立作业 timeout 1h；本轮未启动。

第一次批次的 BackendSoc3cSpec/FpUnitSpec 在 JVM 初始化时遇到 `third_party/cvfpu/src/common_cells/include` 缺失，非主机不可用或 RTL 失败。后来按 CVFPU gitlink 补齐 common_cells `6aeee85d0a34fedc06c14f04fd6363c9f7b4eeea`、fpu_div_sqrt_mvp `86e1f558b3c95e91577c41b2fc452c86b04e85ac`；CVFPU 保持 `1b220f3bc89df99e246b72e3574a3a533cf87653`。新诊断独立工作区带完整所需依赖。

| 阶段 | SHA | 主机 / cwd | 命令及结果 | exit / 用时 |
| --- | --- | --- | --- | --- |
| 冻结检查 | `2dea0b7` | 本地 `/home/chen/leisure/flow` | `python3 tools/frozen_check.py`，OK (8 files) | 0 |
| main/test Scala 编译 | `0d4cb26` | cloud，`/home/cloud_chen/work/flow-soc3c-0d4cb26/design` | `sbt "Test / compile"`，99 main、78 test 源编译成功 | 0 / 31.807 s |
| 新增/组件定向批次 | `435e877` | cloud，同上 | 下列完整 command；85/85 个实际运行用例通过，但 2 suite abort，批次失败 | 1 / 604.948 s |
| 原训练语义停止审计 | `2dea0b7` | cloud，`/home/cloud_chen/work/flow-soc3c-2dea0b7/design` | 下列筛选 command；两条现有测试原样运行，0/2；99 main、78 test 源编译成功 | 1 / 80.383 s |
| §4 全部 BackendContract / 已有前端 / CSRFileSpec | — | — | 未完成 | 未执行 |
| 剩余 L1D 定向、双核、litmus、fault | — | — | 未完成 | 未执行 |
| ClusterIsa / ClusterWbSplitSmoke | — | — | 未完成 | 未执行 |
| tiny 顶层 RTL 生成 | — | — | `GenerateBreezeCluster single gshare linux` 未执行；测试内部 RTL elaboration 不替代此门禁 | 未执行 |
| Alan Cluster OOC + 整 SoC tiny | — | — | §7 原定并行；D3 停止，未启动 | 未执行 |
| bitstream / 上板 / 后台全量回归 | — | — | 无 bitstream，无上板证据，未进入该阶段 | 未执行 |

批次完整命令：

```text
sbt "testOnly flow.backend.BackendSoc3cSpec flow.frontend.BreezeFrontendSoc3cSpec flow.fpu.FpUnitSpec flow.backend.HpmSpec flow.core.BreezeCsrPipelineSpec flow.core.RegFileCsrFileSpec flow.memsys.L1DCacheSpec flow.memsys.L1DPermissionsSpec flow.memsys.L1DContextSpec flow.backend.WritebackSpec flow.core.BreezePrivilegeSpec"
```

其中 `flow.core.RegFileCsrFileSpec` 是文件名误作 suite 名，无此类、未运行任何测试；实际应为 `flow.core.CSRFileSpec`（及需要时 RegFileSpec）。未把它算作通过，也未在停止后补跑。

| 实际完成 suite | 通过 / 实际运行数 | XML suite 用时 |
| --- | --- | --- |
| BreezeFrontendSoc3cSpec | 6/6 | 34.843 s |
| HpmSpec | 1/1 | 2.032 s |
| BreezeCsrPipelineSpec | 2/2 | 6.234 s |
| L1DCacheSpec | 39/39 | 360.238 s |
| L1DPermissionsSpec | 5/5 | 45.407 s |
| L1DContextSpec | 9/9 | 80.668 s |
| WritebackSpec | 3/3 | 6.787 s |
| BreezePrivilegeSpec | 20/20 | 60.584 s |
| BackendSoc3cSpec / FpUnitSpec | suite abort，各 XML 为 1 个错误占位，非已运行用例 | 0 |

停止审计完整命令：

```text
sbt "testOnly flow.backend.BackendContractSpec -- -z S09_BTB_training_waits_for_held_WB_once -z S13_WB_fault_discards_younger_pending_BTB_training"
```

原始证据分别在 [0d4cb26 编译](../../records/soc3c-0d4cb26/cloud/compile)、[435e877 批次](../../records/soc3c-435e877/cloud/targeted-new-components)、[2dea0b7 停止审计](../../records/soc3c-2dea0b7/cloud/training-stop-audit)。各 result.json 保存完整 SHA、实际 host/cwd/command、exit、UTC 起止及 elapsed_seconds；run.log/XML 均保留。

## 7. 时序、结构与 SoC 镜像

本轮 Cluster OOC、post-synth WNS/TNS、分组 worst-20、允许路径、TLB→S1、S2→CVFPU/前端/HPM 结构查询以及 SoC routed WNS/WHS **均无结果**。没有时序达标结论，没有本任务 bitstream 路径。

SOC-3b 验证快照 `21e3b18` 的 Cluster OOC post-synth WNS −4.244 ns、最差 42 级仅作为任务起点。缺少 SOC-3c 同配置网表结果，不能计算改善量。未改变 100 MHz、AreaOptimized_high、maxThreads4，没有 false path/multicycle/keep/retiming 等规避措施。

后续在本报告 §1 裁定后，仍须先完成并修复功能门禁，再依据实时 host 配置生成准确版本 RTL；Alan 独立工作区并行运行 Cluster OOC 与 SoC tiny，各 timeout 1h，超时不自行重试。以 SoC tiny routed WNS ≥0 和 bitstream 作为镜像门槛，并保留所有原有结构/拍数/协议停止条件。worst-20 分组分析可按 §7 后置，原始查询报告必须保存。

## 9. §8/§9 恢复执行

目标为单核与四核各自达到 100 MHz routed setup/hold 时序并生成 FPGA 镜像，按实测资源决定是否缩减容量/深度。保持已定微架构、冻结拍数和协议；本次用户授权自行同步滞后规格与测试。原 §1 的训练停止条件已由任务 §8 覆盖。

### 9.1 测试同步与独立依据

| 用例 / 检查 | 旧 → 新 | 依据 |
| --- | --- | --- |
| S09，BTB/PHT/GHR 消费拍 | 8/2/2 → 3/3/3，各一次 | 分支在 EX 拍 2 决策，共同训练寄存级在拍 3 消费 |
| S09，保持交集 | 消费拍与 hold 无交集 → 消费拍减 1 与 hold 无交集，三类分别检查 | S09 约束决策拍；消费拍无当拍 S2 门控 |
| S09，训练内容 | 增加 target=branch+8、PHT idx=5/taken=1、GHR taken=1 | 来自 beq x0,x0,+8 的编码、刺激 PHT 索引，未读取 RTL 计算期望 |
| S13，已决策训练 | BTB 为空 → BTB/PHT/GHR 于拍 3 各一次 | 拍 2 决策早于拍 8 的较老 WB fault，D3 不追溯取消 |
| S13，架构副作用 | 保留拍 8 redirect、gprBusy=0，增加分支不退休、无非 x0 GPR 写回 | 预测训练不等同架构提交，fault 仍清除年轻指令 |
| S13，反向边界 | 新增 WB fault 与 held EX 的潜在释放拍重合：无 EX 推进、无任何训练 | 检查决策拍的 !wbKill；保持真实反例刺激而非取消消费包 |
| D1 新增慢路覆盖用例，陷入目标 | 0 → 0x200 | `design/src/main/scala/core/RegFile.scala:241`，mtvec 的 Linux 复位值；该刺激未写 mtvec |
| D1 真实组合 | 新增生产 `BreezeFrontendBoundary` + backend，load 在 S2 保持时纠错路径两项进 skid，释放后按序执行 | 使用真实 2 项 skid；独立 memory 刺激在拍 10 给 Mshr、拍 30 给 late 数据，检查错路无 EX/退休/写回与正确结果 |

`ae435f1df56cb0f26fa9a98ea9457b247e1f5612`：训练三个用例通过 3/3；同批 D1/D2/CSR 仍在运行，已观察到 D1 陷入目标上述预期失败，保留原日志，不将批次算作通过。实际 cloud cwd 复用任务隔离目录 `/home/cloud_chen/work/flow-soc3c-2dea0b7/design`，目录名不是 SHA，HEAD 由 runner 显式校验；证据根 `/home/cloud_chen/evidence/soc3c-ae435f1/focused-authorization`。

实时预检：cloud 连接/环境可用，Verilator 5.028、可用内存约 28 GiB、磁盘约 40 GiB；Alan 约 113 GiB 磁盘与 47 GiB 可用内存，另有 CISLC-O3 Vivado 作业正在执行，未终止或迁移该任务。Vivado 仍只在 Alan 执行。
