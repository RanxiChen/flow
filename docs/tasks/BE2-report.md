# BE2 后端解耦实施报告

2026-10-11。按任务书 §10 BE2-P 和 §11 加速模式连续实施；本文供用户最终审核。起点 `2051dd0bb9a0c701bb95fd3f1e755ae175023093`；分支 `refactor/be2-backend-decoupling`，工作区 `/home/chen/leisure/flow-be2`。主工作区及其既有未跟踪记录未修改。

生产源：`7b885d6`；后续 `2f247ca`/`8df9ec9` 仅测试驱动/诊断更改，生产 RTL 未变。R2/R3/R5/R6、统一接口及降级协处理器桩已落地；R7 的串行条件已移出 WB，保留的中断判定例外见下文。G2 的 0–999 种子已通过；用户后续明确将门禁收缩为最小必备测试集，完整 G1 延期；最小门禁不称为完整 G1 通过。用户又明确要求四核优先、单核与四核一起推进，单核从四核同一套参数派生；此最新指示替代 §11 原“单核通过再四核”的执行顺序。物理验证在最小安全门禁通过后并行单核/四核，保持 100 MHz/default 策略；四核资源或时序不通过时允许删减硬件资源，单核同步使用调整后的参数，两者重新核验。

## 实现映射

| 规则 | 实现位置和所有权 | 检查 |
|---|---|---|
| R1 | `BreezeBackend` MEM/PERM payload 每拍赋值；kill 只清 valid；`downHold` 只控制 solo WB。L1D CPU S1/S2 每拍推进，CPU S3 只为对应 solo 项保持 | N01 RTL 断言及 G2；真实 L1D 重执行测试 |
| R2 | WB 同沿更新 busy/CSR/退休/W2 和 CPU valid；MUL/DIV/FPU/copro `commitD`/`killD` 为 RegNext。结果允许寄存令牌当拍授权最老项 | 既有 T04/T05/T16/P02/P08/P10；N04；flags-only CSR 衔接测试 |
| R3 | MUL=8、DIV=1 本地 ID credit 包含已预留的 EX 项，grant、killD 或被取消的 EX 预留归还；FPU 入口=2，另有 tag 和 killDrain 资格；copro=1。EX 不看这些单元 ready | 请求接受必然成功、无重复及 credit 范围断言；T07/T14/P08；copro kill 测试 |
| R4 | MUL 运算流水无条件推进，八槽结果环；总预留包含流水和结果，最多八项。lateReg 两项无 flow/pipe 队列。FPU 原两项请求/两项返回边界不增加 | N03 八项结果及连续八拍 W2 冲突；原 MUL 算术/等价测试；late 第二槽错误 N+1 fatal |
| R5 | L1D 新增 Replay 判定、原因及寄存 replayWake；WB Replay 不提交，从 wb.pc 重定向，清本条和年轻 valid；soloArm 使重取首条在 ID 等醒后 solo 发射 | N02 实际 cache 同行测试；Replay 不分配 MSHR、不写 PS、不建 reservation、不发 MMIO 的断言；G2 原因 1/2/3/4/6 |
| R6 | 串行项在 ID 等 olderEmpty、cpuDrainedD/mmuIdleD/标量所有权；SFENCE 等待期间停止新翻译，WB 发脉冲及 redirect，无第二次等待。AMO、aq/rl 首次 solo；MMIO/LR miss/其它 Replay 重执行 solo；WFI 正常提交 | T17–T20、P05/P09；solo WB 年轻槽全空及无第二次 Replay/Mshr 断言；真实 PTW 命中/缺失对照 |
| R7 | wbCommit/wbKill 不再读 drained/idle，存储判定取 L1D S3，copro 判定取已寄存队首。保留现有 P09 中断 CSR 判定锥，详见自行裁定 | 已通过 P09 定向；综合/布线逻辑级数及 S2 尚待取得 |
| R8 | `BreezeClusterConfig.coprocessorStub: Option[Int]` 默认 None，向 Core/Backend 传递；默认关闭时模块和第五写口均不存在。未新增测试专用生产分支 | 7b885d6 两份生产 RTL 已确认无 copro 模块/第五写口/观察端口，参数一致；最小集独立入口，完整用例及 golden 不改 |

`LongLatResult` 定义 rd(bank/index)/data/flags；既有 `FpResult` 继承该类型保持调用兼容。`LongLatUnit` 汇总 reserve/available/commitD/killD/returned/drained 和 Decoupled result。MUL/DIV/FPU 接入同一结果适配口，Writeback late 适配同一口；late error 在本地封装保留。优先级仍 W2 > late > DIV > MUL > FPU > 可选 copro，饥饿阈值仍 3。late 为已经提交的结果，不需要再次授权，不响应未提交 kill。

协处理器桩按 §11(6) 降级：custom-0，一项预留，PRNG 控制 0–15 拍判定等待，Ok/Exc，Exc 保留 cause/tval/vstartMarker；无真实向量功能。Serial 和标量结果延迟留给 R05'，不将不存在的结果路径计入 G2 覆盖。decision 为 occupied/known 寄存状态，killD 当拍判定无效；commitD/killD 回收一次容量。G2 每个 seed 先发不同数目的真实请求，避免每次复位重复同一段 PRNG 延迟。

## 自行裁定及合同推导

1. **容量**：MUL 八项 credit 是流水加结果的总容量，不能把三拍流水另加为十一项。没有写口延迟上界时，所有已接受项都必须能汇入八槽结果环。ID 预留的 EX 项也占同一预算。late 两槽满时不能借当拍 grant 接收第三项。
2. **T18–T20**：raw 条件 D 拍变为 1，寄存条件 D+1 可见，串行项 ID D+1，EX D+2，MEM D+3，PERM D+4，WB/动作 D+5。旧流水已空才允许采样；没有后续义务时，最后旧 WB 在 W，最早 ID=W+2。不能用 W 沿建立 PS/MSHR 之前的旧 drain 样本。SFENCE 还要求该采样拍 ID 已阻塞翻译，禁止使用阻塞之前的 idle 样本。SFENCE 的 MMU 内部阻塞承担刷新排空，后端不再二次等待。原 MMU 文件的起点 frozen hash 差异保持，未用更新 hash 掩盖它。
3. **P05**：既有 refill R+8 释放资源，原始可发 R+9，寄存醒信号使 D 在 R+10 离开 ID，S0/EX R+11，最终 S3/WB Done R+14。只提交一次、不死锁及原数据保留；不沿用旧 S2 长期保持测试。新的合同已写入 `backend-timing-contract.md`，相应 frozen hash 更新。
4. **FPU II=1 的入口归还**：仅用上一拍入口 count 会在两项边界下形成“两条后停一拍”，破坏原 P08。允许真实入口出队当拍归还 ID 入口 credit，即 `entrance < 2 || input.deq.fire`；tag/killDrain 仍受寄存状态约束，不加 FIFO，EX 不读取 ready。这是 R3“纯寄存 ID credit”的明确例外：CVFPU 的入口 ready 可经 deq.fire 到 ID 发射资格；它不来自写口 grant，也不控制 EX/MEM/PERM 推进。不能把此实现描述成完全切断 CVFPU→ID 组合路径。
5. **中断与 P09**：曾寄存 CSR interruptPending/cause，定向 P09 发现接受晚一拍。为了保持既有 golden，恢复原外部事件观察边界；`interrupt` 仍读 CSR masking/priority 的组合结果、四级 valid、mmioBusy、fatal、stopped。保留现有 CSR time/stimecmp 同拍 pending 合同。本次自行裁定允许该既有中断锥作为 R7 清单例外；不宣称其为新增的中断队首寄存器，不将未经测量的组合级数标成物理证据。
6. **FPU flags-only 的所有权交接**：x0 目的不置 GPR busy，WB 已离开而 commitD 尚未更新单元 flags-only count 时，CSR 可能提前发射。`committedFlagsOnly` 加上由已寄存 commitD 产生的 flagsSet，接住该拍所有权；原 CSR flags 值/完成边界不变。新增 NaN 转 x0 后读 fflags 测试，要求 NV=16 且 CSR ID=实际 FP 完成后一拍。
7. **solo miss 精确性**：solo load/AMO 不得以 Mshr 提前提交。L1D 保持其 CPU S3 所有权，内部 refill 重查给 Done/Exc，取消 solo late 路径，避免重复写回及将总线错误降为非精确 fatal。禁止 solo 响应 Mshr 和第二次 Replay。
8. **测试驱动**：CoreDriver 将同一逻辑事务 Replay 后的重试仍记为同一条历史项，按原顺序重取，首次重试 solo；第一次 Replay 不写 final respCycle。watchdog、原地址/数据/异常 oracle 不变。
9. **G2 reference 的范围**：后端扰动台使用独立程序顺序/算术/结果 ledger；实际 cache 副作用与精确异常另由定向集成和 G1 检查。已导出 4757d5e 的 1000 份实际 ledger，并在 Alan Spike 1.1.1-dev 完成 1000/1000 独立比对：全部提交 PC 恰好一次且顺序一致，精确寄存器写回 multiset 一致。参考侧将低地址 RAM/PC 平移到 0x80000000，并用未被程序使用的 x29 作为 load 基址；Ok custom-0 按 NOP 建模，MMIO 读值按固定 fixture 放入映射 RAM。IRQ/Exc 不属于此适配器的 Spike 比对范围，仍由原 RTL 用例检查。Spike SHA256=33a958f70b5b7bb09ca2d31ac53c218e701f35138ddf7b5c3e808e50027a16a6，原日志及每 seed ELF/ledger 均保留。

10. **多核驱动 solo 所有权**：直接 L1D 测试驱动原先允许年轻 solo 与更老的 speculative 响应重叠，错误释放 soloActive，导致“solo 再回放”误判。solo 请求现在等待旧 inPipe 全空才呈现 valid，接受判断同时读取实际 valid/ready；被重取的年轻 AMO/aq/rl 仍按译码首次 solo。原随机八组、种子 81/82、1000/2000 操作数、oracle 和 watchdog 不改。
11. **Fibonacci 原链接布局过期**：旧 linker 的 256 KiB SRAM 栈与当前平台 64 KiB SRAM 不符，基线和候选均陷入并写原 FAIL magic。P1 使用单核专用的 64 KiB 链接布局，原 C/main/runtime 与 PASS/FAIL magic 不改，两版本使用同一 ELF。旧失败证据保留，不更新平台或绕过权限检查。


12. **串行条件的最后旧 WB 交接**：新增 PS/MSHR 及首次 SFENCE 阻塞反例。cpuDrainedD 只在 olderEmpty 采样，mmuIdleD 同时要求 ID SFENCE 翻译阻塞已生效；断言检查整个合格样本的等待窗口，避免最后旧 WB 新建义务或最后一次前端翻译使条件回落。T18 无后续义务最早 W+2，T19/T20 已等待场景仍 D+1/D+5。
13. **HPM 参考范围**：BE2 增加 14–20 七项 replay 事件；CSR 独立模型显式覆盖每个选择器并驱动全部新输入，非法选择器从已合法的 14 改为 21。原 1–13 编码、延迟发布、覆盖优先级、400 次随机规模和种子不改。

14. **SFENCE 排队翻译**：FetchTlbClient 的 block 同时门控新 request 和 Send/重试的 tlb.req.valid；已有 MMU 查询继续排空，已排队查询在 WB 重定向时 kill。只门控前者会让 Send 在 mmuIdle=1 的采样边沿建立新 MMU 义务。
15. **取消与重取**：CoreDriver 的显式 kill 优先于自然 Replay；s2KillAfter 从逻辑指令第一次接受计时，重试不重置截止拍。资源等待移到 ID 时仍可取消逻辑重试，并保留已提交 miss 的 late 归属；S1 kill 清掉被杀 solo 的驱动占有权。权限上下文测试按 Replay→solo→原 Exc/Done 检查，cause/tval/无副作用仍保持。
16. **旧 D1/C1 保持场景**：分支和 FP 不再被更老 S3 等待保持，验证它们在已提交 miss outstanding 时继续并只接受一次。真实生产 skid 改为 L1D S0 拒绝正确路径 load，使 EX 保持；两条后续正确 ALU 填满原双槽，额外校验 x7=77。原 x1=0x1234/x5=55/x6=66/错误路径 x9 不写保留。更早合法 EX 训练允许一次；kill 同拍无训练仍保留。
17. **缓存清理**：Alan 磁盘降至约 6 GiB 后，清理本任务 B worktree 中超过 15 分钟、无活动 cwd、单硬链接的 .o/.a/.gch 共 5169 个，释放 4,955,308,032 分配字节。保留所有源、生成 RTL/C++、可执行仿真器、日志和结果；删除路径/大小/SHA256 在 `be2-closed-object-prune.jsonl`。

## 测试迁移与删除的补丁

| 测试/逻辑 | 迁移内容 | 保留的 golden |
|---|---|---|
| T07 / held-EX DIV 场景 | 第二条 DIV 等在 ID；ID=第一条物理写回，EX=ID+1 | 原商、生产者操作数和一次接受 |
| T14/P08 | 在 ID 预留两项入口，出队当拍返还 credit | 原 II=1、两项边界、FP32/64 精确延迟、所有数值 |
| T19/T20 | 持续提供 ID 指令至寄存条件满足，按 D+1/D+5 计拍 | 条件阻塞时不发射、不请求、不生效，动作只一次 |
| P05 / L1D control wait | 检查 Replay、solo 重执行，替代 obsolete hold counter | Done/数据、一次提交、无死锁；respCycle 仍仅最终响应 |
| S13 fault/resource hold | speculative 资源等待改 Replay+solo，保持故障与年轻分支重叠检查；kill-at-EX 用直接故障对齐 | 无年轻架构写、kill 同拍不训练，之前合法训练只一次 |
| FpUnit 同拍 kill 特殊刺激 | 请求先接受，下一拍 killD；保留 40 项输出及身份检查 | 未提交作废，已提交结果不丢失 |
| MUL / Writeback | MUL 总容量由旧四项改八项；late 两槽独立接收、满拒绝第三项 | 原算术规模、seed、grant 顺序、busy clear、错误结果不写 |
| B3 真实 PTW hit/miss FENCE | cpuDrained 排除 PTW，命中与缺失都正常串行提交 | raw drained 反例仍由独立测试保留，不把反例用例当单调性证明 |

已删除 FPU fire-under-kill tombstone 特殊处理；killD 拍禁止新发射，WB kill 当拍已接受项在下一拍正常作废。删除全局 MEM/PERM payload 保持、WB 串行 serialWait 和后端 SFENCE 第二等待；保留既有普通旁路、W2、late fatal 和 CSR flags 所有权保护。原 SOC-3c D3 的更早合法年轻训练不回滚，wbKill 同拍仍禁止新训练。


## R6 源端稳定性依据

以下证明针对 **olderEmpty 后的合格样本**，不把最后旧 WB 之前的瞬时空位当承诺。`BreezeBackend.scala:151` 起的 sample 资格和等待期断言配合：ID 串行未离开时没有新的 CPU S0；旧 EX/MEM/PERM/WB 已空，L1D CPU 槽与其对齐，不能随后建立新的 CPU 义务。

- PS 在 S3 接收边沿建立：`L1DCache.scala:711` 起；采样资格将此边沿排除。PS 清除与 MSHR 的新分配以同沿状态更新完成，cpuDrained 同时检查 PS 和 CPU MSHR（`:858`）。
- CPU MSHR 从 alloc 到 Replay/Late 全程 `mshrValid=1`，来源在 alloc 保存且直到 Idle 不变（`L1DMiss.scala:60–78,175–194`）。Replay 发起时 MSHR 保持，完成/释放时 internal1/2/3 的 CPU replay valid 覆盖交接（`L1DCache.scala:364–375,857–860`）。probe 使回放再次 miss 时该有效 CPU replay 或 CPU MSHR 仍属于义务；没有凭空的新 CPU 来源。
- PTW alloc 的来源固定为 Ptw（`L1DCache.scala:636`）；PS 只由 store/atomic 的 CPU 结果接收。PTW/refill/probe/writeback 不凭空新建 CPU 义务，`canPtw` 还要求 PS 空（`:122`）。本证明不以禁止完成旧义务所需的一致性/回填为前提。
- SFENCE 资格要求 ID 已阻塞；`FetchTlbClient.scala:26,31` 同时禁止新接收及排队/重试查询。既有 itlb/dtlb 的 s1/miss/pendingFault 和 PTW state 全被 `Sv39Mmu.io.idle` 覆盖（`Sv39Tlb.scala:44–46`, `Sv39Mmu.scala:30`, `Sv39Ptw.scala:19`）；因此 idle 合格采样后无新查询来源。SFENCE 自身 S0/S1 刷新忙在 WB 脉冲之后发生，MMU 在内部禁止查询，后台不再等待第二次 idle。

这是源码来源审计加等待窗口断言；真实四核随机/coherence/权限/程序回归通过后才给动态覆盖通过结论。原 B3 raw drained 的 PTW 反例保留，它不是 cpuDrained 的反例。

## WB 判定输入清单

RTL 表达式层次如下；这里的“级”是布尔表达式依赖，不等于 Vivado LUT/carry 级数，后者必须由实际网表取得。

| 输出 | 直接输入及中间判定 | 结构范围 |
|---|---|---|
| wbCommit | WB valid/solo/exception/mem/copro；L1D S3 valid/kind；copro known/exception；Writeback fatal 与 stopped。构造 wbExc/wbReplay/downHold 后 AND wbCanLeave | 不读取 drained/mmuIdle/执行单元 ready/grant |
| wbKill 的指令事件 | 上述 wbCanLeave、wbExc/wbReplay，WB fencei/sfence/mret/sret/CSR地址写属性/wfi | WB 本地事件 OR |
| wbKill 的中断事件（例外） | CSR privilege/global enable/mie/mip/mideleg priority；EX/MEM/PERM/WB valid；L1D mmioBusy；fatal/stopped | 保留既有中断组合锥，需单独列出 timing |
| 允许同拍端点 | busy set、CSR/trap/退休、W2 D、后端及 L1D CPU valid 清除、slowRedirect/HPM 事件寄存器 D | 与禁止的宽 payload CE/跨模块控制分组 |
| 跨模块端点 | MUL/DIV/FPU/copro commitD/killD，frontend slow redirect | 寄存器后的控制；S2 必须用基线阳性对照验证 |

## 主机、同步与证据

每次开始编译/仿真/RTL 生成重新读取 `/home/chen/leisure/flow/docs/cross-project/simulation-host.md`。本会话 cloud_chen SSH 有限超时 exit 255；Alan 经 clawbot/2286 正常，20 核；nice 10，每测试进程 4 核，两项合计 8 核。固件前置构建最多额外 2 核，总计不超过 10。工具 Java 11.0.32.1、Verilator 5.028、sbt 1.9.7。

同步使用本地 `127.0.0.1:7897` 代理 push，Alan 通过反向转发 `127.0.0.1:18897` fetch；不再使用 bundle。此前 bundle 尝试保留为历史，不当作后续同步方式。两个 Alan worktree 共用 Git 引用，fetch 必须顺序执行，快进及准确 SHA 检查之后才构建。代理断开的一次 stale-source 构建证据只归属于实际旧提交，不能给预期新 SHA 记通过。

所有下列路径位于 Alan `/home/chen/FUN/flow-runs/`。原 R4/B3 详细历史及旧报告保留在 Git 历史和本地 `records/be2-20261011/`，§11 后每项简记 SHA/命令/主机/计数/日志，不再归档源 tar。

| SHA | 命令/范围 | 结果 | 日志 |
|---|---|---|---|
| 504a1af | BackendContract + Storage + G2 冒烟 | 43/44；G2 注入/初始 PC ledger 错误，已修复 | be2-r2.log |
| 766e27e | G2 50 seeds + Storage + Writeback | 7/7，exit 0 | be2-766e27e.log |
| 0e6df0b | 完整 BackendContract | 36/42；六处合同/实现问题，后续逐项修复 | be2-0e6df0b.log |
| 1699497 | copro + G2 50 + 实际 cache N02 | 4/5；测试错用 mtvec=0，恢复原 0x200 | be2-1699497-directed.log |
| 7b4f646 | copro + G2 + 实际 cache N02 | 4/5；TLB PTW 已被前序 Replay 等待遮蔽，调整 TLB 刺激先行 | be2-7b4f646-smoke.log |
| 7b4f646 | P05/P08/P09/ID DIV/S13 失败集复测 | 6/6，exit 0 | be2-7b4f646-contract.log |
| 6f12269 | G2 50 seeds + flags-only CSR | 2/2，exit 0 | be2-6f12269-smoke.log |
| f03d15c | BackendContract + FetchTlbBlock + FetchPmpCandidate + BreezeCsrPipeline | 49/49，4 suites，exit 0 | be2-68c-directed.log |
| 8e5bd3b | 最后旧 WB 建立 PS/MSHR 的两项反例 | 0/2；均触发原 R6 稳定性断言；f03d15c 修复通过 | be2-8e5bd3b-handoff-v2.log |
| 236cf04 | 权限上下文全 suite + 单核 miss/atomic kill 失败集 | 前两部分 9/9 + 2/2 通过；随后多核随机 1/7 通过、6 失败，整个进程 exit 1 | be2-236cf04-failed-set.log |
| 027e5b0 | G1 `test`，仅 BE2PerturbationSpec 单独在另一作业跑；其余全部 suites | 按最新用户指示终止，exit 143；已有失败及部分覆盖保留，不验收 G1 | be2-027e5b0-g1.log |
| 027e5b0 | `BE2_SEEDS=1000 testOnly flow.backend.BE2PerturbationSpec` | 1000/1000 seeds，1/1 test，exit 0 | be2-027e5b0-g2.log |

统一 Java 调用为 `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 taskset -c <0-3 或 4-7> nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar '<sbt 参数>'`。G1 cwd `/home/chen/FUN/flow-be2-b3-8ea5db5/design`，G2 cwd `/home/chen/FUN/flow-be2-1a4deba/design`。每项原始 `.exit` 随日志保留。

S1/A1/S2：单核/四核物理流程及准确基线综合正在执行；P1 已取得匹配冷启动及 64 KiB Fibonacci 的基线与候选数据，预算有失败，见下表；没有本候选 routed/bitstream/板上通过证据。frozen_check 起点 MMU 文件 hash 差异仍单独标记。

补充证据：4757d5e G2 ledger 导出 1000 seeds 全通过，`be2-4757d5e-g2.log`；Spike 1000 seeds 全通过，`be2-4757d5e-spike-v4.log` 及 `be2-4757d5e-spike-v4/result.json`。初次 ELF 缺少 section table、误关闭 boot ROM 的 reference 适配失败另存 v1/v2/v3，不记为 DUT 失败。

## 后续用户指示及最小门禁

用户将完整回归延期至 FPGA 后扩大覆盖，并指定四核为主要目标。最低门禁单独新增 `BE2MinimumSpec`，不删减 `ClusterIsaSpec` 原列表、不改原始 ISA ELF 的 tohost=1/指令数据/规模。包含 add/lw/sw/beq/mul/div/amoadd_d/lrsc/rvc/csr 十个 ELF；四核 ISA 测试由 p 环境停驻其他 hart，多核执行由六个原始四核系统程序补足。单核和四核缓存几何参数保持一致（l2BytesPerCore 相同；L2 总字节数及 sharer/端口数按核数派生），所有资源裁定同时传播给两者。

- d5a44fb 的另一轮旧全量回归因 Alan 无剩余磁盘空间而 exit 1；该轮混有测试失败与环境失败，不能归类为 RTL 全失败或全通过。027e5b0 作业终止后，其 shell 未能写退出文件（磁盘满），已按实际 TERM 手动记录 exit 143。
- 清理范围仍仅三个 BE2 worktree 的 .o/.a/.gch，先检查没有活跃编译进程；磁盘满时 bootstrap 删除记录先输出到本地 JSONL，再删可再生成 gch。源码、生成 RTL/C++、仿真可执行文件、日志、结果全部保留。恢复约 7 GiB 空间，路径/hash/字节数见清理清单。
- f5dbb56 修复 CoreDriver 排队但未接受的 presentingRetry 身份丢失：重取仍保留同一 CoreTxn，不转成 fresh CoreOp；solo refill error 严格要求原 cause=5/7、原 tval、无 oracle 写入。它不是允许任意 Exc。
- 6ac3415 的旧 WB fault/BTB 场景只校正已存在的寄存输出观测拍号：EX=3 作出一次合法训练，BTB=4 可见，WB kill=4 不产生新训练，5 拍无额外事件。训练次数、目标、错误路径不提交均不放宽。
- 6ac3415 单核最小 ISA 10/10、原单核系统程序 7/7、P1 原程序 4/4 均通过，`be2-6ac3415-minimum-cluster.log/.exit` exit 0。P1 原值如下：lrsc 15934/1127、trap 28222/2092、MMIO 48511/4258、Fibonacci 17886/1329（cycles/retired）；相较匹配基线，部分仍不满足原 +1% 预算，不因程序功能通过而宣称 P1 通过。

- 7b885d6 配置核验 9/9 tests、单核/四核最小 ISA 各 10/10 ELF、四核原始系统程序 6/6 ELF，3 suites 共 12 tests 通过，exit 0；日志 `be2-7b885d6-four-minimum.log`。原四核 lock/LRSC/message/IPI/Sv39/FENCE.I 的各 hart 原 tohost=1 均满足。
- 7b885d6 的 `runMain flow.top.GenerateBreezeCluster small gshare linux` 和 `single gshare linux` 均 exit 0，`be2-7b885d6-rtl.log`。两份 profile 均 production/tandem=false/debug=false/GShare/Linux/10000000 watchdog，同样 lineBytes=32、l1Sets=128、L1D/I ways=4、l2Ways=8、l2BytesPerCore=65536、l2Slots=2。
- 6ac3415 安全集的 BackendSoc3c 4/4、选中安全合同 7/7、G2 1000/1000 seeds 已通过；随后 fault seed91 进程无结束且背景流量持续，主动 TERM（exit 143）增加诊断，不当成全部测试通过。Spike 对该次 1000 ledgers 全通过，`be2-6ac3415-spike.log/.exit`。
- 2f247ca 诊断保留原 RNG/1000 操作/4000000 cycle limit，显示 cycle 20000–330000 两个 CPU 均停在 fired=12980/13315、原 pending 保持，但 L1D drained 可以为真；TERM 后保留日志。根因是旧驱动按 S1Kill 只取消一条，而 L1D permission split 的既有合同 §附录及源码会清年轻 S1/S2。8df9ec9 按接受拍号清两槽，保留最终 S3 更老项，并新增 directed 用例要求旧 load Done、年轻 store/load canceled、另一核仍读原值。不是放宽 no-response 规则：被 kill 请求原来就不应响应，驱动必须停止等待这些请求。
- Alan 空间在另一次复查恢复到 34–39 GiB；本次对象缓存清理只释放记录中的字节数，不能把全部空间变化归因于本任务清理。物理构建前已预留空间。
- 物理 runner 用已有 KCU105/LiteX SnapshotBuilder 冻结准确生产 filelist；默认 synth/opt/place/route/post-route phys_opt，100 MHz，maxThreads=4。安装的 LiteX 不识别 build_bitstream=False，故先生成原默认 Tcl，仅去掉 write_bitstream 命令。此调整仅限制产物范围，不加时序例外或更换实现策略。当前已生成单核/四核工程和原 BIOS，尚未给 routed 通过结论。

8df9ec9 最低故障集 4/4 tests（S1 双年轻槽取消、两核 seeds 91/92、四核 seed91，原每核1000操作），SB+fence seed35 1/1 test 通过，整个进程 exit 0，`be2-8df9ec9-fault-minimum.log/.exit`。四核 oracle AMO=720/load=3471/LR=243/SC成功226/失败32/store=670，取消426、精确异常171、refill error31；litmus 445 rounds/89独立点/5遍，禁止结果零，必需结果分别78/76次。两个 TERM 诊断进程独立保留，不能与这次 exit0 混记。

最低门禁已收完。生产源仍为 7b885d6，8df9ec9 生产差异为空；同源/同配置 single/small 在 Alan 同时启动默认物理流程，准确 baseline2051dd0 四核仅综合用于 A1/S2 阳性对照。候选各4核、基线2核CPU，共10核；相同Vivado2022.2/器件/100MHz及默认指令。未加例外，未启动四核定制优化策略或板上测试。

P1 匹配数据（same ELF/退休数；功能四项通过，性能预算失败）：

| 程序 | 基线2051dd0 cycles | 候选6ac3415 cycles | retired | 增加 | +1%门槛 |
|---|---:|---:|---:|---:|---|
| lrsc_amo_single | 15768 | 15934 | 1127 | 1.053% | 未通过 |
| trap_misc | 28079 | 28222 | 2092 | 0.509% | 通过 |
| mmio_console | 47685 | 48511 | 4258 | 1.732% | 未通过 |
| fibonacci | 15654 | 17886 | 1329 | 14.258% | 未通过 |


## Admission 修正与首轮物理证据（进行中）

7b885d6 的 single 默认 routed 完成，exit0，WNS=+0.030 ns、WHS=+0.028 ns、TNS/THS=0；sys_clk（100 MHz）WNS=+0.371 ns。全设计最差 setup 是 IDELAYCTRL reset 路径。原始报告及 Tcl 在 `records/be2-20261011/physical-7b885d6/single/`。这只证明该 SHA 的单核 routed，不代表新修正或四核已通过；未写 bitstream，未上板。

同配置 exact2051dd0/7b885d6 的四核综合层次面积，扣除每个 fpUnit/impl 的 CVFPU 本体后，各后端 LUT 变化 +2.156/+2.142/+1.501/-0.984%，FF +7.003/+6.769/+6.769/+6.592%；各 L1D LUT -0.108/+0.830/+0.533/+1.000%。A1 对此源/配置/综合阶段通过。原始层次表和逐行 JSON 在 `physical-7b885d6/{baseline-synth,small-synth,area-synth.json}`。顶层综合 LUT=228169，占94.13%；baseline顶层226228。同级比较，未拿 routed 与 synth 混比。

**自行裁定 18：EX admission 与取消分离。** 综合 checkpoint 用叶输出驱动作为 through 点，确证 S3 decision→EX 宽 payload CE 仍有1230端点、20条最差路径（旧版 query 的组合 from 点不足以作无路径证明，故保留失败查询并改正）。MEM/PERM 无该保持；执行单元及前端的初步无路径集合已有同方法 baseline 阳性，但完整查询仍在运行。原始残留来自 wbKill→L1D cpuKill→req.ready/TLB kill-ready→EX resourceWait。74c858e 增加 L1D/TLB canAccept，容量与实际 fire 分离：EX只读取消前的相同优先级 admission；实际 cache/TLB 请求仍在 kill 当拍不 fire、不留副作用。新增断言要求无 CPU kill 时 L1D canAccept==req.ready，新增定向例要求 older load精确13异常、年轻EX load不发请求/不提交/不写回，EX不被取消ready保持。1ce2b97 又排除 raw SFENCE 动作，TLB canAccept仅由 missValid/sfS1Valid寄存状态决定，req.ready仍抑制sfence.valid。SFENCE按R6到WB时EX已空且wbKill同时取消年轻CPU槽，因此不靠容量信号授权请求。MMU冻结文件的起点hash差异不重录掩盖；新侧带定义补入L1D接口表并只更新该文件授权hash。

新增修正正在重跑相关最小门禁。首轮四核物理继续保留作真实对照；单核首轮时序和A1不能冒充最终修正版证据。
