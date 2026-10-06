# V1-BE 执行报告

状态（2026-10-06）：**V1-BE 验收完成并结束。Alan 最终指定全量 65/65 通过（含 P06），11 suites 完成，0 failed/aborted/canceled/ignored/pending，退出码 0；冻结检查 OK（9 files）。** B01 已实现，BreezeBackend 已切换；ND01/ND02 已接受并实现，B02/P06 已由用户提供的合同与测试提交澄清。本次仅补全报告，没有改动 RTL、测试或冻结输入。

## 1. 实现与范围

实现起点为用户指定的 `f12d65b44d53f50268eb217ed63b6ea4957c3707`，分支 `feat/pcie-fase-20260920`。已有未跟踪文件和本地报告草稿保留并补全。最后 RTL 实现提交为 `f867297373fe32590f093239431fea13cc041afa`（V1-BE/12）；最终 pull 后的被测提交为 `fe7aef411df6781a415cb126206c0788d25028d0`（P06 合同与测试澄清），RTL 相比 V1-BE/12 未变。最终复验绑定该 SHA，报告提交不替代被测 SHA；本报告以 `V1-BE/13` 单独提交。

- `backend-v1-rtl-spec.md` 按 B01 改写写口、停顿方向、饥饿保护、串行发射、行为 LATE，标注 `[V1-BE-B01-ruling§1]`；ND01/ND02 标注裁定来源。
- `Writeback` 普通 WB 写优先；过滤被普通写占用的 bank 后，后台全局单 grant 按 L1D > DIV > MUL > FPU。每 bank 有 2-bit 饱和计数和在途气泡标记，ID 只读寄存器，不依赖当拍 grant。
- `BreezeBackend` 使用双 bank 记分板、Committed MUL/DIV/FPU、直接 S2→WB 判定与 late→RF 写；资源/依赖等待仅在 ID/EX。MEM/WB 只因 s2Hold 或后方为空的串行 WB 等待保持；不加后端流水级、响应 FIFO、结果缓存。
- FENCE 是 L1D 请求；FENCE.I/SFENCE/WFI/ESTOP 从 ID 串行。FENCE.I 等 drained；SFENCE 关闭翻译请求、等 drained/idle、单拍发 sfence、后续 idle 才提交。late.error 停 hart、不陷入，保留 committed 后台完成。
- `loadUseBypass` 默认 false，经 Cluster/Core/Backend 参数传递；true 跑 T02b。可选旁路遇 miss 时，依赖者留在 EX，复用原 RF 读口/操作数寄存器等正确值。
- HPM11/12 保留来源事件，13 按未获后台 grant 的 bank 数计 0/1/2；保留软件写优先和旧 selector 采样。trace 分离 pending retire 与 late 完成，加入独立台账检查。
- 行为 L1D 遵守 LATE：late 反压保存 MSHR lateData，S1/S2 每个非 s2Hold 拍照常前进，resp 不依赖 late.ready；LATE 中新 miss 按 MSHR 满处理。固定合同返回延迟 30 拍；补充随机 1–40 拍、随机 s2Hold，seed=0xB01。模型无 probe 端口，不提供 L1D RTL/coherence/probe 活性证据。
- BTB 待发训练在 WB 保持期间留在原寄存边界，解除后只发一次，WB kill 丢弃年轻待发项；有额外保持/取消测试。

核心仅迁移新接口、HPM/trace 和编译所需接线；集群连接待集群 spec。历史 data-translator 入口关闭，旧 shell dcacheFlushReq 固定无效；Backend 已删除旧 dmem/cacheFlush 接口。旧后端测试通过 test-only `V1LegacyTestAdapter` 适配旧环境，原断言保留；**这些旧后端 suite 未运行**，编译成功不计作回归通过。

## 2. B02：P06 合同澄清与历史失败

P03 明确要求“20 条 ADD 在 DIV 写回之前全部提交”；P06 写的是“20 条 ALU 连续提交，不等任何一路后台结果；三路结果各写一次，记分板最终全 0”，没有使用 P03 的“之前全部提交”。

旧 P06 测试将“不等”解释为 **20 条 ALU 全部早于三路中最早的写回**，断言 `lastALUCommit < min(L1DWrite, DIVWrite, FPWrite)`。这比“不因后台结果而停顿”更强，不能直接把该断言失败认定为冻结文件互相矛盾。

合法 FP64 `1.0 / 3.0` 可区分两种解释。令 FDIV EX/input fire 为 E；四级单发射下 ADD1 在 E+1 EX、E+3 WB，ADD20 在 E+20 EX、E+22 WB。固定 CVFPU/wrapper 在 E+21 返回；GPR ADD 不占 FPR 口，当拍没有更高优先级后台返回，T15 要求直接写 f1。不能为了严格 P06 断言而故意推迟无冲突 FP ready。

历史复验 `59bd232` 的实际事件（旧断言，不作为新合同的验收结果）：

| 事件 | 实际拍 | 相对 FDIV EX |
| --- | --- | --- |
| FDIV EX/fpIn，PC 0x460 | 31 | E |
| ADD1 commit，PC 0x464 | 34 | E+3 |
| ADD1–ADD20 commit | 34–53，每拍一条 | E+3…E+22 |
| ADD19 commit、fpOut/fprWrite(f1) | 52 | E+21 |
| ADD20 commit，PC 0x4b0 | 53 | E+22 |
| DIV gprWrite(x2) | 64 | E+33 |
| Load late.fire/gprWrite(x1) | 68 | E+37 |

FP 结果为 `0x3fd5555555555555`；三路目的各写一次。20 条 ADD 连续提交断言已通过；失败为 `53 was not less than 52`。其后的最终 busy=0 断言未执行，不能宣称 P06 全部要求通过。

源码依据：`design/src/main/resources/vsrc/fpnew/FlowFpnewWrapper.sv:37–46` DIVSQRT PipeRegs=2、DISTRIBUTED；`third_party/cvfpu/src/fpnew_divsqrt_th_64_multi.sv:74–83` 输入/输出各 1 级；`third_party/cvfpu/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_ctrl.v:255–264` 的 DOUBLE SRT 初始计数为二进制 01101，并有 early-completion 分支。源码和算术配置未改。结论只需上述合法反例，不声称所有 FDIV 操作数延迟相同。

**B02 已澄清**：用户提供的提交 `fe7aef411df6781a415cb126206c0788d25028d0` 已修改冻结合同 P06、对应测试并重新登记合同哈希。本次直接验收该提交，没有再次修改测试、断言或 RTL。新合同要求：

1. 首条 ALU commit 早于三路结果中最早写回。
2. 首末 ALU commit 之间的空拍集合恰等于其间 x1/x2 后台 GPR 写回拍集合，相邻 ALU commit 间隔至多 2 拍；FDIV 写 FPR 不产生空拍。
3. 三路结果各写一次，GPR/FPR 记分板最终清零。测试的 `written` 辅助函数同时检查对应目的恰好写一次。

不再要求 20 条 ALU 全部早于后台结果；上述旧失败保留为合同澄清的历史证据。

最终 `fe7aef4` 的 `tests.log` 复核：20 条 ALU（PC 0x464–0x4b0）commit 拍序列为 **34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53**；三路写回分别为 **FDIV f1=52、DIV x2=64、Load x1=68**。首条 34<52；首末 ALU 之间无 GPR 后台写回，空拍集合为空；FPR 在 52 拍写回时 ALU 仍提交。三路各写一次与最终两组 busy=0 断言均执行通过。P06 已关闭。

## 3. [新决定] 与 [自定]

| ID | 内容 | 状态 |
| --- | --- | --- |
| ND01 | kill 后停止新 FP 分配至 CVFPU busy=0，保留 committed 返回、丢弃作废返回，不 flush CVFPU | B01 §3 已接受；组件实测 |
| ND02 | CSR 还等 committed FP→x0 flags 返回，不把 x0 置 busy | B01 §3 已接受；组件实测 |

无新增架构决定；B02 已由用户提供的合同与测试提交澄清，本次未自行修改合同。

[自定] 摘要：双 bank 各 32-bit busy/source mask，整数 bit0 无效而 f0 有效；来源 2-bit 编码 L1D/DIV/MUL/FPU=0/1/2/3。FPU 表 32 项、tag 5-bit、循环 allocate/commitCursor，无 data/flags 存储。串行等空用 busy 与 committed flags-only 元数据，不用包含当拍 req.valid 的 raw CVFPU busy。fatal 复位清零、错误当拍可见，取消未提交项、保留 committed 完成。可选旁路 miss 用 EX 控制位及原 RF/操作数，不加 late→EX 数据路径。观测/enabledebug printf 仅用于仿真，不参与执行；事件在周期末更新前采样。HPM pending 增量宽度可表示 2，selector≥14 无效；trace 独立台账不把 pending retire 占位值当 RF 写。

## 4. 提交和 Alan 证据

独立 checkout：`/home/chen/FUN/flow-runs/20261006-v1-be-b01-v2/repo`。未复用/切换 Alan 原 `/home/chen/FUN/flow` 的干净 51b62b9 checkout。各轮从 GitHub fetch 后 detached checkout 精确 SHA；本地未跑 sbt/仿真。

固定 CVFPU SHA `1b220f3bc89df99e246b72e3574a3a533cf87653`，common_cells SHA `6aeee85d0a34fedc06c14f04fd6363c9f7b4eeea`。环境 `source /home/chen/miniforge3/bin/activate flow`；OpenJDK 11.0.32.1、sbt 1.9.7、Verilator 5.028。

最终命令（Alan，repo/design）：

```bash
/home/chen/.local/share/coursier/bin/sbt -batch \
  'set Test / parallelExecution := false' \
  'testOnly flow.backend.BackendContractSpec flow.backend.WritebackSpec flow.backend.ScoreboardSpec flow.backend.MduTimingSpec flow.fpu.CommittedFpUnitSpec flow.multiplier.MulProtocolSpec flow.divider.CommittedDivProtocolSpec flow.backend.MduBoundarySpec flow.fpu.BreezeFpUnitSpec flow.backend.HpmSpec flow.backend.TraceProtocolSpec'
```

最终证据根目录（Alan）：`/home/chen/FUN/flow-runs/20261006-v1-be-final-fe7aef4/`。`tests.log` 为完整日志（SHA256 `8b57e87e6fe28befcfff7e31dabfe44a5e473464db37e43fb7b5eb687e8090a5`），`environment.log` 记录主机、被测 SHA、干净 tracked 工作区、依赖版本与工具；`command.txt` 保存上述完整命令；`run.sh` 保存执行脚本（实际调用 `/tmp/v1-be-run-fe7aef4.sh`）；`exit-code.txt` 为 `0`，`start.txt`/`end.txt` 保存起止时间。2026-10-06 19:06:24 +08:00 开始，sbt 于 19:13:02 +08:00 完成（脚本 19:13:03 结束），总耗时 393 秒，测试耗时 6 分 27 秒。

**最终指定全量结果：65/65 通过，11 suites 完成，0 failed/aborted/canceled/ignored/pending。** 合同 33 项、后端补充 6 项、其他组件/协议 26 项均包含在同一次命令中。下文全部 PASS 均绑定 `fe7aef4` 的 `tests.log`，包括 P06 和两项 BTB 补充。此处“全量”指用户指定的 65 项，不是仓库所有 `sbt test`。

`frozen-before.log` 与 `frozen-after.log` 均为 `frozen check: OK (9 files)`；`status-after.txt` 记录测试后 tracked 工作区干净。`p06-test-before.sha256` 与 `p06-test-after.sha256` 相同，`BackendContractSpec.scala` 的 SHA256 为 `161e1d8243e22f22ddcb7df981f52a850c70602816147e48d97b3dc5383b95a6`，与本地相同。

| 提交（均已 push） | 内容 |
| --- | --- |
| 6e77c05 V1-BE/3 | B01、后端集成、合同测试 |
| b5f7ed0 V1-BE/4 | pending trace、合法 refill 调度 |
| 3dde92e V1-BE/5 | 更新前事件采样 |
| 387abbd V1-BE/6 | DIV 活动观测命名 |
| 12f73cd V1-BE/7 | committed-only drain，消除组合环 |
| 2f47eef V1-BE/8 | 可选旁路 miss、late 协议补充 |
| a8728cf V1-BE/9 | fatal/trace/HPM；补充转换刺激更正 |
| 59bd232 V1-BE/10 | 通用周期证据 |
| 89a17da V1-BE/11 | BTB 单次训练保持/取消 |
| f867297 V1-BE/12 | S09 区分 SFENCE 合法单次请求与普通副作用 |
| fe7aef4（用户提供） | 澄清冻结合同 P06、改好对应测试并更新合同哈希；本次直接复验 |

验证历史保留：

| 被测 SHA | 实际结果 | 解释 |
| --- | --- | --- |
| 45eb1ca（B01 前） | 23/23，8 suites，exit=0，64s | `20261006-v1-be-95b4f16/units-45eb1ca.log`，仅组件/相关旧单元 |
| 387abbd | 12/46，34 失败，5 suites，exit=1，226s | Verilator 检出 downHold→wbKill→FP valid→raw busy→serialWait 环；只改 RTL，不屏蔽 UNOPTFLAT |
| 2f47eef | 46/48，2 失败，5 suites，exit=1，346s | P06 严格解释；新增跨 bank 刺激误用 S 转换读 D 值，编码更正为 FCVT.W.D |
| 59bd232 | 62/63，11 suites，exit=1，380s | 唯一失败 P06；0 aborted/canceled/ignored/pending |
| 89a17da | 63/65，11 suites，exit=1，391s | P06 与新增断言误拦 T20；BTB 补充通过；0 aborted/canceled/ignored/pending |
| f867297 | 64/65，11 suites，exit=1，394s | 唯一失败为旧 P06 严格断言；两项 BTB 补充通过；0 aborted/canceled/ignored/pending |
| fe7aef4（最终） | 65/65，11 suites，exit=0，393s | 新合同 P06 通过；0 failed/aborted/canceled/ignored/pending；冻结检查 OK（9 files） |

历史 `387abbd` 至 `f867297` 的证据根目录为 `/home/chen/FUN/flow-runs/20261006-v1-be-b01-v2/`，日志命名 `tests-<sha>.log`，环境 `environment-<sha>.log`、命令 `command-<sha>.txt`、退出码 `exit-<sha>.txt` 同目录，均保留。2f47eef 的补充刺激编码更正在收到该轮结果前已写入 a8728cf，不改冻结行期望；明确记为测试台错误，不称 RTL 修复。89a17da 新增通用无副作用断言错误包含 SFENCE 请求；冻结 S09 明确允许自带单次状态的请求，f867297 将其单独检查为未发送、drained/idle 才发。保留 held WB 无退休/训练/陷入检查，T20 测试与期望未变。

## 5. 合同逐项结果

全部 33 行均测实际 BreezeBackend、真实 MDU/CVFPU、行为 L1D，观察 ID/EX/WB commit/真实 RF write。各 PASS 的命令/日志见 §4；组件不替代合同。

| ID | 结果 | 观测 |
| --- | --- | --- |
| T01 | PASS | ALU 依赖 EX 间隔 1，值正确 |
| T02 | PASS | 默认 Load E+2 写，依赖 EX=E+3 |
| T02b | PASS | 参数 true 依赖 EX=E+2，值正确 |
| T03 | PASS | hit/独立 ALU 重叠 |
| T04 | PASS | MUL E+4 写、依赖同拍离开 ID |
| T05 | PASS | DIV fast E+3 写 |
| T06 | PASS | DIV E+2+实际迭代数写 |
| T07 | PASS | DIV 写回后一拍再接受 |
| T08 | PASS | Store/Load 同字 S0 冲突拍数 |
| T09 | PASS | 不同字不多停 |
| T10 | PASS | Mshr E+2 提交置 busy |
| T11 | PASS | late=R+7，依赖释放 |
| T12 | PASS | WB N 写，late N+1，无保持，冲突计 1 |
| T13 | PASS | 同时 valid 四来源按 L1D/DIV/MUL/FPU 写 |
| T14 | PASS | EX 与 CVFPU input fire 同拍 |
| T15 | PASS | output fire 与真实 RF write 同拍，含 FP→GPR |
| T16 | PASS | FP 完成 RF 穿透释放依赖 |
| T17 | PASS | CSR 等 raw busy 清零后一拍、读最终 flags |
| T18 | PASS | FENCE.I 首个 drained 拍提交并 flush/redirect |
| T19 | PASS | FENCE.I 等待，年轻访存不离开 ID |
| T20 | PASS | SFENCE 单次请求，后续 idle 提交，年轻访存关闭 |
| T21 | PASS | c+3 唯一 ID 气泡、late c+6 fire、冲突计 6 |
| T22 | PASS | B N、C N+1、late N+2；无保持、冲突计 2 |
| P01 | PASS | 16 hit req/commit II=1 |
| P02 | PASS | 8 MUL input/write II=1，各 E+4，尾随 nop |
| P03 | PASS | 20 ADD 连续，全部早于 DIV write |
| P04 | PASS | hit/Store E+3/E+4，独立 ALU 早于 late |
| P05 | PASS | 同行 s2Hold，Done=R+10 |
| P06 | PASS | 首条 ALU 早于最早后台写回；空拍集合/单拍间隔检查通过；三路各写一次，最终 GPR/FPR busy=0 |
| P07 | PASS | FADD 先于 FDIV 返回，flags OR |
| P08 | PASS | 8 FMADD input fire II=1 |
| P09 | PASS | 中断排空未提交流水即接受，早于 committed DIV write |
| P10 | PASS | 异常取消年轻 MUL，保留 committed DIV 一次写 |

## 6. 断言、补充测试与限制

后端仿真启用停顿方向、resp/WB 对齐、无响应不退休、held MEM/WB metadata、held WB 无控制副作用、WB kill 无年轻请求、late 反压稳定等断言。随机保持/refill 和 T12/T21/T22 覆盖 late 反压期间 S1/S2/resp 独立推进；这是仿真证据，不是形式证明。

| Suite/补充项 | 最终结果（fe7aef4） | 证据边界 |
| --- | --- | --- |
| Backend 补充 4 项 | 4/4 | 随机 seed B01、可选旁路 miss、f0/跨 bank RAW/WAW、fatal 保留 committed DIV |
| 两项 BTB 补充 | 2/2 | held WB 无训练/解除单次训练、WB fault 丢年轻 BTB |
| WritebackSpec | 2/2 | WB 优先、四源顺序、跨 bank、fatal 清位 |
| ScoreboardSpec | 3/3 | 双 bank/f0/实际 clear、CSR raw busy、FP→x0 |
| MduTimingSpec | 3/3 | MUL II=1/E+4，DIV fast/实际迭代/释放 |
| CommittedFpUnitSpec | 4/4 | 8 FMA、乱序/flags、kill/tag 回卷、FP→x0 |
| MulProtocolSpec | 4/4 | 既有 commit/kill/保持、随机 500 次 seed0x701 |
| CommittedDivProtocolSpec | 2/2 | 既有 occupied/commit/kill/背压、随机 512 次 seed0x702 |
| MduBoundarySpec | 3/3 | 空 commit 断言、迭代、reset/kill |
| BreezeFpUnitSpec | 2/2 | 旧阻塞包装算术/flags/flush，仅此旧 suite |
| HpmSpec | 1/1 | 双 bank 增量、软件写优先、旧 selector/非法编号 |
| TraceProtocolSpec | 2/2 | 独立台账，拒绝重复/缺失/普通写重叠 |

本地/Alan 冻结检查 `frozen check: OK (9 files)`；本地 `git diff --check`。完整性/静态检查不替代功能证据；没有忽略、取消、pending 或跳过合同。

**未运行**：仓库全量 `sbt test`、旧后端适配 suite、ACT4、整核/集群/L1D RTL/coherence/probe、Spike 差分、实际串联 trace runner、综合/时序/PPA、FPGA/Linux。形式化按任务书不做。本次指定 65 项合同/组件/协议验收已完成，不扩展为上述未运行范围的证据。V1-BE 到此结束。
