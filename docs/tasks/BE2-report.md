# BE2 后端解耦实施报告

2026-10-11。按任务书 §10 BE2-P 和 §11 加速模式连续实施；本文供用户最终审核。起点 `2051dd0bb9a0c701bb95fd3f1e755ae175023093`；分支 `refactor/be2-backend-decoupling`，工作区 `/home/chen/leisure/flow-be2`。主工作区及其既有未跟踪记录未修改。

原标量验证生产源：`c735fbd`（部署几何来自 `558096c`，后端来自 `790c1f6`；包含单次 admission 仲裁及 BTB payload 分离）；首轮物理源为 `7b885d6`，`2f247ca`/`8df9ec9` 仅测试驱动/诊断更改。R2/R3/R5/R6、统一接口及降级协处理器桩已落地；R7 的串行条件已移出 WB，保留的中断判定例外见下文。G2 的 0–999 种子已通过；用户后续明确将门禁收缩为最小必备测试集，完整 G1 延期；最小门禁不称为完整 G1 通过。用户又明确要求四核优先、单核与四核一起推进，单核从四核同一套参数派生；此最新指示替代 §11 原“单核通过再四核”的执行顺序。物理验证在最小安全门禁通过后并行单核/四核，保持 100 MHz/default 策略；四核资源或时序不通过时允许删减硬件资源，单核同步使用调整后的参数，两者重新核验。

原标量阶段门禁状态（2026-10-11 18:51 CST，以下旧版本记录保留其原 SHA/几何范围）：

| 门禁 | 最新证据与结论 |
|---|---|
| 部署最小功能集 | c735fbd，20/20 Scala 用例、单/四核各10个ISA ELF、6个原四核系统程序，以及两几何fault/真实PTW，exit0 |
| 后端安全/G2 | 790c1f6，安全9/9；G2及独立Spike各1000/1000。c735fbd复用相同后端源，部署几何另外验证 |
| 单核 routed/S1 | c735fbd，100MHz/default，exit0，WNS +0.412ns/WHS +0.031ns |
| 四核 routed/X1 | c735fbd 最终未通过：WNS −1.842ns、WHS −0.042ns；process exit0只表示报告流程完成，不是时序通过 |
| A1 | c735fbd vs 2051dd0+同部署配置补丁，同阶段综合比较通过 |
| P1 | 同配置/ELF/退休数，原+1%门槛保留，4项中3项未通过；详细数值见末尾 |
| S2 | 精确同配置checkpoint查询进行中；无阳性对照或空source组保留未解析，不宣称整体通过 |
| 完整G1/bitstream/板上 | 完整G1按用户指示延期；未生成bitstream、未上板 |

## 实现映射

| 规则 | 实现位置和所有权 | 检查 |
|---|---|---|
| R1 | `BreezeBackend` MEM/PERM payload 每拍赋值；kill 只清 valid；`downHold` 只控制 solo WB。L1D CPU S1/S2 每拍推进，CPU S3 只为对应 solo 项保持 | N01 RTL 断言及 G2；真实 L1D 重执行测试 |
| R2 | WB 同沿更新 busy/CSR/退休/W2 和 CPU valid；MUL/DIV/FPU/copro `commitD`/`killD` 为 RegNext。结果允许寄存令牌当拍授权最老项 | 既有 T04/T05/T16/P02/P08/P10；N04；flags-only CSR 衔接测试 |
| R3 | MUL=8、DIV=1 本地 ID credit 包含已预留的 EX 项，grant、killD 或被取消的 EX 预留归还；FPU 入口=2，另有 tag 和 killDrain 资格；copro=1。EX 不看这些单元 ready | 请求接受必然成功、无重复及 credit 范围断言；T07/T14/P08；copro kill 测试 |
| R4 | MUL 运算流水无条件推进，八槽结果环；总预留包含流水和结果，最多八项。lateReg 两项无 flow/pipe 队列。FPU 原两项请求/两项返回边界不增加 | N03 八项结果及连续八拍 W2 冲突；原 MUL 算术/等价测试；late 第二槽错误 N+1 fatal |
| R5 | L1D 新增 Replay 判定、原因及寄存 replayWake；WB Replay 不提交，从 wb.pc 重定向，清本条和年轻 valid；soloArm 使重取首条在 ID 等醒后 solo 发射 | N02 实际 cache 同行测试；Replay 不分配 MSHR、不写 PS、不建 reservation、不发 MMIO 的断言；G2 原因 1/2/3/4/6 |
| R6 | 串行项在 ID 等 olderEmpty、cpuDrainedD/mmuIdleD/标量所有权；SFENCE 等待期间停止新翻译，WB 发脉冲及 redirect，无第二次等待。AMO、aq/rl 首次 solo；MMIO/LR miss/其它 Replay 重执行 solo；WFI 正常提交 | T17–T20、P05/P09；solo WB 年轻槽全空及无第二次 Replay/Mshr 断言；真实 PTW 命中/缺失对照 |
| R7 | wbCommit/wbKill 不再读 drained/idle，存储判定取 L1D S3，copro 判定取已寄存队首。保留现有 P09 中断 CSR 判定锥，详见自行裁定 | P09 定向通过；最新S2查询进行中。单核routed报告已有，不能把全CPU最差路径级数冒充WB判定锥级数 |
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

RTL 表达式层次如下；这里的“级”是布尔表达式依赖，不等于 Vivado LUT/carry 级数，后者必须由实际网表取得。以每个多输入 AND/OR/EQ 各一层、NOT 不另计，从列出的 WB/S3/队首输入起：wbResponse=1，wbMemExc=2，wbExc=4，wbReplay=3，downHold=3，wbCanLeave=4，wbCommit 最深5，指令事件 wbKill 最深7。CSR 中断优先级锥是另列的例外，不纳入这个5/7层数；这些不是物理LUT层数。

| 输出 | 直接输入及中间判定 | 结构范围 |
|---|---|---|
| wbCommit | WB valid/solo/exception/mem/copro；L1D S3 valid/kind；copro known/exception；Writeback fatal 与 stopped。构造 wbExc/wbReplay/downHold 后 AND wbCanLeave | 不读取 drained/mmuIdle/执行单元 ready/grant |
| wbKill 的指令事件 | 上述 wbCanLeave、wbExc/wbReplay，WB fencei/sfence/mret/sret/CSR地址写属性/wfi | WB 本地事件 OR |
| wbKill 的中断事件（例外） | CSR privilege/global enable/mie/mip/mideleg priority；EX/MEM/PERM/WB valid；L1D mmioBusy；fatal/stopped | 保留既有中断组合锥，需单独列出 timing |
| 允许同拍端点 | busy set、CSR/trap/退休、W2 D、后端及 L1D CPU valid 清除、BTB 单位有效性/公开payload可见位 D、slowRedirect/HPM 事件寄存器 D | 与禁止的宽 payload CE/跨模块控制分组 |
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

以下阶段记录均按各自 SHA/几何解释。标量阶段门禁状态见首表；三SD产品的新增证据见最新追加节，P1 匹配数据及单核 routed 报告见末尾；该历史阶段没有 routed/bitstream/板上通过证据。frozen_check 起点 MMU 文件 hash 差异仍单独标记。

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

当时最低门禁已收完。该轮生产源为 7b885d6，8df9ec9 生产差异为空；同源/同配置 single/small 在 Alan 同时启动默认物理流程，准确 baseline2051dd0 四核仅综合用于 A1/S2 阳性对照。候选各4核、基线2核CPU，共10核；相同Vivado2022.2/器件/100MHz及默认指令。未加例外，未启动四核定制优化策略或板上测试。

P1 匹配数据（same ELF/退休数；功能四项通过，性能预算失败）：

| 程序 | 基线2051dd0 cycles | 候选6ac3415 cycles | retired | 增加 | +1%门槛 |
|---|---:|---:|---:|---:|---|
| lrsc_amo_single | 15768 | 15934 | 1127 | 1.053% | 未通过 |
| trap_misc | 28079 | 28222 | 2092 | 0.509% | 通过 |
| mmio_console | 47685 | 48511 | 4258 | 1.732% | 未通过 |
| fibonacci | 15654 | 17886 | 1329 | 14.258% | 未通过 |


## Admission 修正与首轮物理证据（历史版本）

7b885d6 的 single 默认 routed 完成，exit0，WNS=+0.030 ns、WHS=+0.028 ns、TNS/THS=0；sys_clk（100 MHz）WNS=+0.371 ns。全设计最差 setup 是 IDELAYCTRL reset 路径。原始报告及 Tcl 在 `records/be2-20261011/physical-7b885d6/single/`。这只证明该 SHA 的单核 routed，不代表新修正或四核已通过；未写 bitstream，未上板。

同配置 exact2051dd0/7b885d6 的四核综合层次面积，扣除每个 fpUnit/impl 的 CVFPU 本体后，各后端 LUT 变化 +2.156/+2.142/+1.501/-0.984%，FF +7.003/+6.769/+6.769/+6.592%；各 L1D LUT -0.108/+0.830/+0.533/+1.000%。A1 对此源/配置/综合阶段通过。原始层次表和逐行 JSON 在 `physical-7b885d6/{baseline-synth,small-synth,area-synth.json}`。顶层综合 LUT=228169，占94.13%；baseline顶层226228。同级比较，未拿 routed 与 synth 混比。

**自行裁定 18：EX admission 与取消分离。** 综合 checkpoint 用叶输出驱动作为 through 点，确证 S3 decision→EX 宽 payload CE 仍有1230端点、20条最差路径（旧版 query 的组合 from 点不足以作无路径证明，故保留失败查询并改正）。MEM/PERM 无该保持；执行单元及前端的初步无路径集合已有同方法 baseline 阳性，最终对应组结果见下文。原始残留来自 wbKill→L1D cpuKill→req.ready/TLB kill-ready→EX resourceWait。74c858e 增加 L1D/TLB canAccept，容量与实际 fire 分离：EX只读取消前的相同优先级 admission；实际 cache/TLB 请求仍在 kill 当拍不 fire、不留副作用。新增断言要求无 CPU kill 时 L1D canAccept==req.ready，新增定向例要求 older load精确13异常、年轻EX load不发请求/不提交/不写回，EX不被取消ready保持。1ce2b97 又排除 raw SFENCE 动作，TLB canAccept仅由 missValid/sfS1Valid寄存状态决定，req.ready仍抑制sfence.valid。SFENCE按R6到WB时EX已空且wbKill同时取消年轻CPU槽，因此不靠容量信号授权请求。MMU冻结文件的起点hash差异不重录掩盖；新侧带定义补入L1D接口表并只更新该文件授权hash。

该次新增修正的最小门禁结果如下。首轮四核物理继续保留作真实对照；单核首轮时序和A1不能冒充最终修正版证据。


74c858e admission 定向7/7（R1取消、R6三项、S13 kill、P09/P10），G2 0–999全部通过，整个进程exit0；实际1000份ledger再经Spike独立比对1000/1000、exit0，脚本/fixture/golden不变。参考脚本首次路径错误exit2不作为测试失败或主机失败，改用明确scp后的be2-spike-ledger.py，成功日志用-v2单独保留。1ce2b97的Backend生产代码与74c858e相同，差异仅为TLB容量中排除SFENCE动作及协议注释。

1ce2b97最低集：配置9/9、单核和四核ISA各10/10 ELF、原始四核系统程序6/6 ELF、S1双槽取消及四核fault seed91共2/2、行为PTW drain反例1/1，五suites15tests，exit0。六个四核程序cycles/各hart退休数与7b885d6完全一致，证明确实保留正常执行行为。原每核1000操作、精确异常cause/tval和tohost不变。日志在`records/be2-20261011/minimum-admission/`；真实Sv39串行PTW的两项关键用例 BE2FencePtwSpec 2/2 通过，exit0。

1ce2b97生产single/small由B工作树生成，exit0，复制准确production目录至同SHA的C工作树后由SnapshotBuilder冻结，未重新用本地或旧源生成。该SHA四核默认物理曾启动（CPU0–3/maxThreads4），单核同参数并行启动（CPU8–9/maxThreads2），均主动终止为143以替换源；线程数是运行资源上限，不改变硬件参数或实现指令。首轮7b885d6四核仍在布线，保留其源和工程作对照，没有把中间负WNS当作最终指标。

默认 XDC 中沿用 LiteX 的既有 reset synchronizer/mr_ff false_path；未新增 false_path、multicycle 或放宽 clock。原始 XDC 已归档，100 MHz 的 sys_clk 与 FPGA/default DDR 约束完整保留。

首轮7b885d6 S2严格结果：decision→payload仍有1230端点（正在以1ce2b97修正）；decision→执行CE=0（baseline870）、hold→payloadCE=0（baseline2994）、hold→执行CE=0（baseline580），这些非空source/endpoint集合有同方法阳性对照。frontend两组在baseline和candidate都为0，表述为双方均已无此路径，不能称为BE2新消除的路径。candidate grant两组为0，但baseline selected/ready别名在重建网表中为空，grant阳性对照仍未解析；wbCommit/wbKill名字也被优化掉。相应项目保持UNRESOLVED，不以空source证明通过。首次接口pin尝试同样为空，原始查询保留。


## 四核部署资源裁定与最终修正

19. **同一 admission 仲裁**：1ce2b97 的双 PriorityEncoder 使四个 L1D 综合 LUT 相对同配置起点增加 4.634/5.783/5.334/5.881%，超过 A1 的 2%。237e1f2 只选一次取消前仲裁，CPU/recheck 实际 fire 另门控 cpuKill，维护优先级保持原值。原 admission assertion/精确 fault 用例和最小门禁通过。1ce2b97 的单/四核物理被新源替代，主动终止 exit143，不当作功能失败或 routed 证据。
20. **BTB 宽 payload 捕获**：7b885d6 单核实际 routed 的 CPU 最差路径为 cpu3_paddr[7] 到 backend btb_pc 的 R，21 级逻辑。790c1f6 使宽 pc/target/predType/taken 无条件捕获，同边界一位 btbPayloadVisible=RegNext(train) 控制公开 payload 清零；用 train 而非 btb.valid 保持 train 且预测类型 NONE 时原公开字段。kill 同拍 train=false，公开 valid/字段仍按原契约。S13 精确字段清零检查、BE2R1/BE2R6/P09/P10 及 G2 1000 种子通过。
21. **共享部署缓存缩减**：7b885d6 四核综合 LUT=228169/242400（94.13%），全局布线迭代0耗时48分24秒，迭代1仍始于38771冲突节点。按用户四核优先及允许删减资源的授权，small 改为 L1 64sets/两路、L2四路/32768字节每核；single仅改核数/profile，不能单独放大缓存。标准 BreezeMemGeometry.default 及原默认/压力测试保持原几何；保留100MHz、GShare、RV64IMAFDC/Linux、MSHR1、slots2、line32、paddr32和 BE2 credit 容量。新增准确部署几何的原 seed91/每核1000操作故障测试，golden/断言/看门狗不变。此裁定需要重跑两部署最小程序及匹配配置 baseline 综合；旧 A1/P1 数字只代表旧默认几何，不冒充新配置通过。

790c1f6 的最新最小回归：config9、两配置20个原始 ISA ELF、6个原始四核系统程序、原 S1 kill/默认几何四核 fault seed91、真实 Sv39 PTW hit/miss，合计16个 Scala 用例，exit0。独立后端安全集及 G2 seed0–999 exit0，生产 single/small 生成 exit0。缓存裁定前的后端测试只在后端/执行单元源文件完全相同时复用，并明确配置范围；新增部署几何另作动态核验。


截至部署验证阶段：新增 small 精确几何四核 seed91/每核1000操作通过，oracle AMO694/load3588/LR249/SC成功223/失败43/store654，取消427、精确异常153、refill error40、PTW1873（fault220）、Inv1429/Down929/Puts2181；原默认几何同种子也通过，golden未改。790c1f6 ledgers 的独立 Spike 1000/1000 比对通过，参考配置/适配器与前述一致。旧默认四核7b885d6 在布线第1迭代剩55冲突节点时被新源替代，按精确pid/cwd终止为143，完整原日志/stop记录保留；不作为 routed 成功或失败结论。此项停止只结束被替代的构建，BE2任务继续。

P1 的主要成本有契约原因：旧匹配数据中 Fibonacci 有120次原因4（MMIO）Replay，MMIO首次推测响应必须Replay后按solo重取，独占WB724拍；原 mmio_console 为41次原因4、solo307拍。+1%门槛原样保留，不用调整固件、减少输出或更改退休数隐藏开销。新几何仍用同一 ELF再量测。


c735fbd 的部署最小集最终 exit0：六 suites/20 Scala 用例，无失败/cancel/ignore，含两部署各10 ISA、6四核系统 ELF、S1 kill +原默认/新部署四核fault共3项、real Sv39 hit/miss×两几何共4项及P1四原始程序1项。新配置 P1 同固件/同退休数：

| 程序 | 2051dd0+仅共享配置补丁 cycles | c735fbd cycles | retired | 增加 | 原+1% |
|---|---:|---:|---:|---:|---|
| lrsc_amo_single | 15768 | 15934 | 1127 | 1.053% | 未通过 |
| trap_misc | 28108 | 28252 | 2092 | 0.512% | 通过 |
| mmio_console | 47685 | 48511 | 4258 | 1.732% | 未通过 |
| fibonacci | 15654 | 17886 | 1329 | 14.258% | 未通过 |

对应replay[0..6]/soloWB：lrsc 0/0/3/2/0/1/0及266；trap 0/0/5/2/0/0/0及67；MMIO 0/0/3/2/41/0/0及307；Fibonacci 0/0/4/0/120/0/0及724。新配置的single/small默认完整物理已同时启动，每项4CPU/maxThreads4，baseline仅综合2CPU，亲和性集合最多10CPU；Vivado2022.2/xcku040-ffva1156-2-e/100MHz/no新时序例外。原始最小日志在 `records/be2-20261011/minimum-c735fbd/`，精确命令在同目录上级的c735fbd脚本。


22. **D5 重建层次选择器**：新几何 baseline/c735fbd 均完成 synth_design、写出原checkpoint后，旧 D5 因固定 `$home/data/*` 未命中而 exit1。原日志确证32个RAMB36E2数据阵列在 `cluster/data/ram/*`，RTL_RAM_BITS=1048576（128KiB），元数据/plru仍在L2Home。修正只在固定路径为空时检查唯一同级同名array/ram，并要求所有读/写地址 startpoint（除GND/VCC）均属于对应L2Home，且集合非空；三组数组仍必须有RAMB，LUT<100000不变，资源总量计入已验证搬出的阵列。基线和候选在各自原DCP上的正向验证/错误owner负向拒绝均exit0，源码/网表/clock未改。四核从原synth DCP恢复原Tcl的opt/place/route/phys_opt default尾部；原失败退出码和日志不覆盖。单核旧D5已通过且继续原完整流程。最终RTL证据仍绑定c735fbd，checker源码另绑定本修正提交，不将门禁选择器变化伪装成RTL重测。

c735fbd 对 2051dd0+同几何配置补丁的 A1 综合比较通过：backend扣除CVFPU后，各hart LUT +1.877/+2.082/+1.466/-2.161%，FF +6.184/+5.923/+5.923/+5.746%；L1D LUT +0.080/-5.988/-6.488/+1.893%。顶层209547 LUT（86.447%）、108510 FF、107 RAMB36+1 RAMB18、92DSP。原始hierarchical_synth.rpt和逐行area-synth.json在`records/be2-20261011/physical-c735fbd/`。这只是同配置综合面积，不是routed timing通过。


c735fbd 单核最终routed exit0：全设计WNS=+0.412ns/WHS=+0.031ns，TNS/THS=0，100MHz sys_clk WNS=+0.559ns。LUT59167、FF34563、BRAM52tiles（51RAMB36+2RAMB18）、DSP23。报告/Tcl/XDC/源文件hash清单在`physical-c735fbd/single/`。布局/布线使用default，无新例外，无bitstream/板上证据。单核仅改核数/profile；其四核配对最终时序失败，见下方准确数值。

23. **追加单核RVV及SD自动启动交付**：用户明确要求三种产品：四核基础版、同配置普通单核版、在普通单核上挂载RVV的版本，以及对应BIOS和可后期独立编译更新的SD软件。已核对flow-rvv/cc0be2f现有R05任务书及设计文档未提交补充，原工作树修改保留；接入采用BE2 §7落点。继承四核基础参数不意味着强迫RVV保留写回L2：该预设按已有方案启用写直通L1D、dataless home及宽AXI，标量/向量/SD DMA排序与作废一并覆盖；其余两个产品保留原缓存策略。

追加范围初查时 BE2 工程尚未接通SD：`target.py`未提供`--with-sdcard`，旧`build_sd.sh`却传此选项，旧SD文档还描述已废止的Wishbone/coherent-dma入口；旧`prepare_sd_boot.py`限定历史软件hash且直接修改旧DTB内存，不能当作当前三产品的软件包。已有可复用的软件基线则是README和`linux/sd-gapbs/`记录的29ee514硬件/b2a895b软件：用户JTAG加载.bit，BIOS通过sdcard_init/sdcardboot从FAT按boot.json加载Image/OpenSBI/DTB/跳板，进入内嵌initramfs的Buildroot Linux，再运行gapbs-smoke。六个小图自检的板级结果记录在`docs/benchmarks/breeze-gapbs-20260918.md`，仅代表该旧产品，不冒充BE2验证。后续三产品沿用这条SD加载Linux的链路，更新构建入口、缓存/DMA接线、匹配BIOS/DTB及自动SD软件启动；不追加配置Flash镜像交付，SD也不存或加载bitstream。软件后续通过`linux/sd-gapbs/build.sh`独立编译，兼容更新只替换SD文件。该项已加入任务书§12；本段是范围与现状核查，不是新RVV、SD或启动通过证据。

## 三 SD 产品最新执行阶段（2026-10-11 20:15 CST）

当前交付仍未完成，尚无三个可交付 bitstream。原首表只描述 c735fbd 标量阶段，不代表新 SD/RVV 产品通过。三产品范围、构建和软件包用法见 `fpga/kcu105/sd-bringup.md`；以下按各自准确源记录。

- 5ab7e64 SD 普通单/四核：新桥3项和实际Home/独立GoldenMem集成1项通过，四核和普通单核production/coherent-dma RTL及BIOS生成成功，四核default全物理在Alan CPU4–7布线；四核结束后同runner顺序构建同配置单核。SD BIOS约39.94KiB，自动FAT软件启动，不包含Flash镜像。原97545a7另外14/14模块与配置用例保留。
- R04本体来源：flow-rvv/cc0be2f九个RTL模块及当前R05设计导入，原工作树未改；2663770原前端8项+三种VLEN/DLEN compile smoke，共11/11。新增 `vmv.v.i` 是Linux6.18.7上下文discard所需立即数广播，保留原测试。
- a70fec1、08248df：64/512-bit实际write-through L1D+Home各1项通过，使用原CoreDriver/GoldenMem/4000拍watchdog，不更改冻结的全行AxiMemory；新增宽总线masked memory是独立模型，覆盖byte mask、AMO、LR/SC、外部Inv、B错误原精确store cause7以及失败不改cache/DRAM。a70第一次整机生成因Bool.pad宽度克隆失败，082使用Cat显式扩展后RVV整机及BIOS生成exit0。
- d3469ce 整机首先在原amoadd ELF触发内部完成容量断言。e663e5e修正WT CPU等待不能覆盖内部refill/probe/PTW/Recheck的判定，不放宽断言；保留失败日志。随后新增程序trap入口未四字节对齐，f879ef9只修复新增汇编入口，不改RTL或期望值。
- f879ef9 RVV整机：原10个最小RV64I/M/A/C/CSR ELF签名全部tohost=1，新增向量内存程序tohost=1/1509cycles/87retired；独立检查输出8个64-bit元素均为12，检查VS=Off非法异常的精确mepc/mtval。
- 4ae968a：同10个原ISA再次通过，扩展程序3763cycles/107retired，新增立即数广播、LMUL8的512B寄存器组store/load和vlenb检查通过；新AMO miss写穿回归及原前端/三种参数smoke共13/13，无failed/canceled/ignored。CPU8–9/maxThreads2的RVV default物理已进入综合。该快照尚未包含后续silicon busFailure保护，不能作为最终该保护的验证结果。
- 4e1f86d软件：从保留的GAPBS Buildroot SDK和initramfs在新目录编译Linux6.18.7，应用两个MMC补丁，SMP4、MMC_LITEX、V用户上下文支持；kernel vector usercopy阈值设为INT_MAX，避免R05临时Serial路径被内核自动选作可故障恢复的copy。完整新编软件的Buildroot三份配方可后续独立执行；本次复用已编译rootfs/OpenSBI必须与新kernel区分并记录其输入hash。当前编译中，未声明Linux已启动。

24. **接入保守裁定**：首版标量访存在ID等所有向量排空，未启用逐PA conflictQuery优化，保留接口；向量之间仍有32项VIQ/独立请求游标，不把整后端收紧成单条在途。现有LiteX AXI路由读/写各一在途限制实际性能，不能复用R04独立模型的带宽数字。两位long-source计数暂把向量标量结果归入FPU，不改变寄存器bank或结果所有权。
25. **总线错误硬件保护**：R04对Ok后的AXI错误仅断言，综合去断言后不应静默继续。增加sticky RVV busFailure→hartFatal，阻止错误load写VRF与后续正常提交；早期翻译、VS/vill/Serial的精确异常合同保留。Ok之后不可恢复总线错误不冒充早期精确Exc。该新增保护仍需最小回归、重新生成/冻结最终RVV物理源。
26. **SD软件接口核对**：5ab生成CSR实际PHY/core/reader/writer/IRQ偏移与DTB/两个MMC驱动补丁一致；打包器进一步核对块偏移、CSR宽度和hart数，仅页base相同不足以说明匹配。SD/card/mux外部时序和新板上启动均为OPEN/BOARD_NOT_RUN，不借用旧29ee514的GAPBS板级结果。

主机规则本阶段每次重新读取共享配置并预检；cloud_chen有限5秒SSH连续timeout255，按当前配置回退Alan，记录工具Java11.0.32.1/Verilator5.028/Vivado2022.2及实际cwd/SHA。本地反向HTTP代理18897可用，所有新源通过精确提交fetch/ff或隔离worktree同步，未使用bundle。MMU原冻结hash差异仍保留，新增后端/L1D/Home合同仅按用户授权范围更新。

20:18 CST：c735fbd四核最终报告process exit0，但timing FAIL，WNS−1.842ns/WHS−0.042ns/TNS−12403.845ns/THS−0.088ns，19057个setup失败端点。最差20级probe address→WB cancel反馈→L1D tag WE，11.276ns数据延迟中8.737ns为routing。a367267加入early/local side-effect cancel分离及精确无副作用断言，当前最小回归进行中。4e1f86d Linux Image编译exit0；三套FAT文件已经打包并验证实际CSR ABI和hart数，当前绑定先前硬件快照，最终硬件确定后重新绑定。尚无三bitstream/新板上证据。

20:33 CST：a367267 的 early/local cancel 修正最小回归最终 exit0，六 suites/20 Scala 用例，无失败/cancel/ignore。两配置20原 ISA ELF、六四核程序、原 S1 kill/默认及部署 seed91 每核1000故障、四个真实Sv39 PTW、原P1均保留；P1四程序cycles/retired与c735fbd相同，原+1%预算仍三项不通过。新标量SD工程在隔离工作树生成，避免与RVV sbt输出冲突。RVV最小集增加独立非恒等Sv39数据映射、原rv64uf/ud的ldst/fadd共四个ELF，原期望tohost=1/20k进展watchdog/2M总上限不变。

4ae968a RVV综合完成：LUT117214/242400、FF56664、BRAM242.5tiles、DSP177；VRF为192 RAMB36（24bank×8），映射门禁通过，无L2持久数据、real dataless Home。该物理快照未含a367267修正，已在完整synth DCP及报告保存后按精确PID/cwd主动终止，exit1由SIGTERM产生，不作为时序失败或成功；停止审计在rvv-physical-4ae968a/superseded-stop.json。旧SD四核5ab7e64也已route完成但仍为被替代源；中间WNS−1.688ns不冒充最终报告。
