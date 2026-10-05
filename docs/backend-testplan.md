# Breeze 后端测试计划：T01 阶段一

状态：**计划稿，未冻结，所有新验证项未运行**。需求依据：[T01](tasks/T01-backend-scoreboard-mdu.md)（T）、[后端设计](backend-pipeline-design.md)（D）、[RTL spec 审阅稿](backend-rtl-spec.md)（S）。源码审查基线为 `d5672f51bf0ec67465148c02af970c70464bec68`，分支 `feat/pcie-fase-20260920`。本阶段不创建测试代码、harness 或 SBY 配置，不运行 Alan 验证，不修改已有验收。

## 0. 证据状态与执行门槛

- “现有入口”表示源码中找到 suite/检查，不表示测试运行或覆盖了新合同；表中的新 Txx/Uxx/Rxx/Pxx 是**计划编号**，不是现成测试名称。
- 每条需求都有定向、随机、形式化与回归映射；“不适用”说明原因，不能当作豁免该需求的仿真/系统验收。
- S 的 Q01–Q18 未决；依赖它们的测试先记录必须满足的可见行为，不猜队列深度、来源顺序、BMC 深度或 ABI。冻结后补明确逐拍 oracle 和已批准的测试变更。
- 所有硬件执行在 Alan。先核对分支/完整 SHA/工作区/已有任务，保留无关任务；基线、改造后使用同机同配置。每条运行保存命令、cwd、工具、配置、退出码、原始日志、seed/指令数量及匹配的源版本。
- T 第 4 节每一类验收都必须完成；模块级仿真/形式化不替代全回归、ACT4、tandem、随机程序、综合/时序或性能证据。

## 1. 现有测试入口与缺口

下表全部为**源码审查，未运行**。引用行号对应上述基线。

| 回归编号 | 已找到的源码位置 | 可复用范围 / 缺口 |
| --- | --- | --- |
| R-MUL | `design/src/test/scala/multiplier/RiscvMulUnitSpec.scala:23-64`；`design/src/test/scala/multiplier/SignedMul65x65Spec.scala:178-270,287-413` | wrapper 五种结果、flush、旧乘法连续/随机/边界与 3 拍检查；新 wrapper 为 4 拍、逐笔 kill、保持，须 Q15 批准等强迁移；旧 SignedMul65x65 参照及其检查保留 |
| R-DIV | `design/src/test/scala/divider/RiscvDivUnitSpec.scala:25-65`；`design/src/test/scala/divider/UnsignedRadix4DividerSpec.scala:43-89` | 符号/W 和 unsigned 随机/flush；新协议提交/kill/ready、保持/快结果延迟需新增，旧算术检查保留 |
| R-BE | `design/src/test/scala/backend/BreezeBackendMulSpec.scala:61-92`；`design/src/test/scala/backend/BreezeBackendDivSpec.scala:70-128` | MUL/ADD completion bypass、DIV 八种操作与快速值；当前按 memWbValid/wbData 观察 MDU 最终值，不能直接验“早提交、晚结果”；Q15/Q18 批准新观测点且保留算术期望 |
| R-REDIR | `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala:133-210` | WB fault/xRET/satp 与年轻分支/访存/sfence/flush 同拍；新增年轻 MDU req/kill 与老后台结果同拍 |
| R-CORE | `design/src/test/scala/core/breezecoreSpec.scala:900-1240,1761-1918,2299-2667` | CSR、load/store、单次退休、load-to-store、load-to-branch、目标 miss、CORE-003 handler/变体；基线真实通过数/失败项未确认 |
| R-HPM | `design/src/test/scala/core/BreezeCsrPipelineSpec.scala:47-160`；`design/src/test/scala/core/breezecoreSpec.scala:323-393` | 全 CSR 可见拍的独立计数模型、selector/inhibit/overwrite；旧测试把 11 作为非法值（前者 :130-131），若新 ID 使用 11 需 Q15 批准迁移非法值检查，不能直接删 |
| R-FP | `design/src/test/scala/backend/BreezeBackendFpSpec.scala:12-64`；`design/src/test/scala/backend/BreezeBackendFpMemorySpec.scala:32-134` | FS Off、依赖 FP/flags、FP 访存/boxing/非法对齐；新增与后台 MDU 同整数写口、FP 跨 bank RAW/WAW |
| R-PRIV | `design/src/test/scala/sim/BreezePrivilegeFlowSpec.scala:50-116,118-248,381-477`；`design/src/test/scala/sim/BreezeWfiFlowSpec.scala:29-78` | CSR 别名、MRET/中断、S/U 交互、trap frame、非法 CSR、WFI 一次睡眠/唤醒；新增后台 MDU 存活及 handler 依赖 |
| R-RF | `design/src/test/scala/core/BreezeRegisterStorageSpec.scala:10-91`；`design/src/test/scala/core/breezecoreSpec.scala:13-48` | x0、同拍 RF 写读、FPR f0；新增仲裁后真实 write-through 与 x0 MDU 生命周期 |
| R-FASE | `design/src/test/scala/fase/FaseIntegrationSpec.scala:15`（suite 定义） | 仅确认入口；对新后台操作的覆盖**未确认**，按 Q10 设计新用例后纳入完整 suite |
| R-ACT | `verification/act4/Makefile:19-34`；`verification/act4/scripts/run_linux_soc_suite.py:21-31,42-70,77-99` | build extensions、full SoC single profile、include-extension、selected/ran/summary；运行前枚举 I/M/Zmmul corpus，不能仅运行默认 I |
| R-TANDEM | trace 字段 `design/src/main/scala/interface/interface.scala:246-262`，后端输出 `design/src/main/scala/backend/BreezeBackend.scala:1735-1737` | 仅确认退休 payload；晚结果关联、完整参考比对 checker 与执行命令**未确认**，Q18；`tests/ref/spike_ref.hpp:1-11` 仅为空壳，不能作为已存在 checker 证据 |

为遵守 T 0.2，改动旧检查前必须列出“旧检查→新合同→等强替代断言/观测点”的逐项审批记录。不得减少随机向量、删除旧参照算术测试、放宽错误期望、跳过 suite、换 workaround 让测试通过。源码只用于接口/现状定位，期望值来自 D/S、独立整数模型与经批准的参考模型。

## 2. 需求到验证追踪表

所有行的当前证据状态相同：**新合同未实现、测试待编写、形式化与回归未运行**。实现映射指 S 第 1 节与未来模块边界；不是已实现声明。Sxx/Fxx 见 S 第 10 节；随机 A/B/C/D 组见第 4 节；具体定向 Txx 见第 3 节。

| 需求 ID / 权威来源 | 预期可见行为 / 实现边界 | 定向测试 | 随机测试 | 形式化 / 仿真断言 | 回归项 / 依赖 |
| --- | --- | --- | --- | --- | --- |
| REQ01；D 3/4，T 1，S 1/4 | 顺序四级，MDU EX 发起、WB 一次提交；后台写不另退休；普通指令可前进 | T01、T07 | C | F07/F09，S04/S08/S09 | R-BE/R-CORE/R-TANDEM；Q01/Q04/Q06/Q18 |
| REQ02；D 5，S 2.1 | x1–31 31 位逻辑 busy，x0 特例，至多一个预约所有者 | T02、T05、T18 | A | F01/F02/F12，S01/S02 | 新记分板模块回归、R-RF；Q01/Q11/Q14 |
| REQ03；D 5，S 2.2 | ID 真离开置位；真实写回/提交前 kill 清位；提交不清 | T03、T05、T06 | A | F02/F07/F11，S02/S04/S08/S10 | 新记分板模块、R-BE；Q02/Q03 |
| REQ04；D 5，S 3 | 真实 rs1/rs2 busy 停顿，rd busy 阻止所有 GPR 写；无关指令不依赖停顿 | T01、T02、T04 | A/C | F01/F10，S03 | R-BE/R-CORE/R-FP；Q08 |
| REQ05；D 5，S 3.2 | 真实后台写回同拍 ID 旁路，未 grant 来源不旁路，下一拍可释放；普通旁路保留 | T04、T08 | A/C | F10/F11，S08/S13 | R-RF/R-CORE/R-BE；Q02/Q04/Q08 |
| REQ06；D 4/8，S 4 | 接收/提交/kill/保持按身份匹配；早算完不能提前架构写，取消永不写 | T05、T06、T09 | A/B | F03/F05/F07/F08/F11，S04/S05/S07/S10 | 新 MDU 协议模块、R-MUL/R-DIV；Q01/Q03 |
| REQ07；D 6，S 5 | 一个整数写口，长延迟优先，普通 WB 让拍完整保存、只提交一次 | T07、T08 | A/C | F04/F09，S06/S09 | 新仲裁模块、R-CORE/R-BE/R-HPM；Q04/Q06/Q07 |
| REQ08；D 6/12，S 5 | 多来源同拍到达按冻结顺序选一，其他保持，已提交结果最终写回 | T08、T09 | A/B | F04/F05/F06/F13，S06/S07 | 新仲裁+真实 MDU 集成；Q12 |
| REQ09；D 7，T 4，S 6 | trap kill 年轻未提交者、保留后台已提交者，handler 依赖等待 | T05、T10 | C | F03/F07/F13/F14，S05/S10/S13 | R-REDIR/R-PRIV/R-CORE；Q03/Q06 |
| REQ10；D 7，S 6 | 中断停止新正常发射，只排空未提交流水，不等后台结果；pc 正确 | T11 | C | F07/F10，S13/S14；全核 pc 用仿真 | R-PRIV/R-TANDEM；Q06/Q10 |
| REQ11；D 7，S 3/6 | CSR 等 busy 空，原 rd/状态/别名冒险仍有效，老 MDU 能前进 | T12 | A/C | F10/F14，S14 | R-CORE/R-HPM/R-PRIV；Q08/Q13/Q17 |
| REQ12；D 7，T 0.5，S 6/9 | FENCE.I/SFENCE/satp/xRET 原语义与老重定向优先保留，后台项存活 | T13 | C | F07，S13；外部 cache/MMU 语义用仿真 | R-REDIR/R-PRIV/完整 sbt；Q06/Q09 |
| REQ13；D 7，S 6 | WFI 一次退休，无需 busy 空，睡眠后台可写，唤醒和陷入分离 | T14 | C | F07/F12，S14；整机唤醒用仿真 | R-PRIV/R-TANDEM；Q14 |
| REQ14；D 9，T 1/4，S 7 | MUL 65×65 有符号积，4 拍、无反压 II=1，各级 tag/op/valid 对齐 | T15、U01 | B | F08/F13，S11；完整算术另用等价性 | R-MUL/新 MUL 模块/R-BE；Q04/Q16 |
| REQ15；D 9/8，S 7.2 | MUL 任一级 kill 对应身份，committed 不取消；满/反压不丢结果 | T05、T09、T15 | B | F03/F05/F08/F11/F14，S07/S10/S11 | 新 MUL 协议模块/R-BE；Q01/Q03/Q04 |
| REQ16；D 9，S 7.1 | MUL/MULH/MULHSU/MULHU/MULW 与旧单元、独立数学值一致 | U01 | B（全算术随机向量） | S11 对齐；本步不要求证明完整 65×65 算术 | R-MUL/旧 SignedMul65x65Spec；Q15 |
| REQ17；D 9，T 4，S 8.1 | divisor=0、signed min/-1（含 W）1 拍算完，授权前不写；余数/W 扩展正确 | T16、U02 | B/C | F07/F11，S12；特殊值可作组合断言 | R-DIV/R-BE；Q03/Q05 |
| REQ18；D 9，S 8 | 保留 radix-4 数学、忙时拒绝接收，结果未接收保持；延迟口径分开 | T09、T17、U02 | B | F05/F08/F06，S12 | R-DIV/新 DIV 协议模块；Q04/Q05 |
| REQ19；T 0.5，S 9 | CORE-001..004 与 CSR/访存/重定向原修复行为保留 | T19 | C | S13/S15；模块形式化不替代整核回归 | R-CORE/R-REDIR/完整 sbt；Q15/Q17 |
| REQ20；T 1/0.5，S 1/6 | memory/FPU 保留阻塞，后台 MDU 与其完成/WB 冲突不丢数据/flags | T20 | B/C | F09，S09/S15 | R-FP/R-CORE/完整 sbt；Q07/Q08 |
| REQ21；T 0.5，S 9 | FASE 注入/读写/flightEvents/empty 与后台项保持正确 | T21 | C | S14，FASE 不在 T 指定模块证明范围 | R-FASE；Q10/Q18 |
| REQ22；T 1，observability 2.6，S 11 | sb_stall_mul/div、wb_port_conflict 计数准确，旧 HPM 语义保留 | T22 | D | S16；计数算术用独立逐拍模型，非 T 模块形式化必证项 | R-HPM/完整 sbt；Q09/Q13/Q15 |
| REQ23；D 11，T 4，S 9 | 提交顺序逐条比对，迟到值关联原指令，随机程序结果/异常一致 | T23、T10、T11 | C | 不适用：参考模型逐条退休比对为系统仿真验收 | R-TANDEM/R-PRIV/随机程序；Q18 |
| REQ24；T 4 | Alan 完整 sbt test 全通过且 suite/test 通过数不少于同机改造前；RV64IM ACT4 全通过 | T19+全部定向 | C | 不适用：回归集合计数不属于 RTL 性质 | 完整 sbt、R-ACT、R-TANDEM；Q17/Q18 |
| REQ25；T 4，D 9 | 匹配单核配置 DSP/LUT 前后对比，100MHz WNS，SB/仲裁非新最差路径；两个程序测周期/计数 | P01、P02 | 不适用：固定同输入比较；随机验证另覆盖功能 | 不适用：综合/STA/性能是测量验收 | Vivado 综合/时序及性能运行；Q13/Q14/Q16 |
| REQ26；T 4，S 10.2 | 六项最低形式化性质、深度、归纳、未完成项与活性环境假设均有报告 | T01–T18 中对应的冲突场景供 cover | A/B | F01–F14，尤其 F01–F06 | 模块 SBY 回归；Q01–Q05/Q12/Q14 |
| REQ27；T 1，D 8/10，S 4 | 统一协议能表达未来 FPU bank/rm/flags、L1D tag/error；本步不激活 | 文档字段审阅；T06 用 MDU 验提交/kill/保持合同 | 不适用：后续源未实现 | F05/F07/F08 验公共合同，不声称验证未来单元 | 接口审阅；Q01/Q03/Q09，后续 FPU/L1D 另立任务 |

## 3. 定向与等价性场景

所有测试在复位后初始化所需寄存器/CSR，输入遵守握手，观察每次接收、提交、kill、实际写回、busy、PC/异常与 HPM。计数和算术 oracle 独立于 DUT。不同层次分别使用模块驱动、后端假前端/阻塞内存模型、全核/SoC 软件；不能用允许多个 MDU 同拍响应的模块模型代替真实流水整合用例。

| ID | 激励 / 冲突 / 恢复 | 必须检查的结果与边界 |
| --- | --- | --- |
| T01 | DIV x5 后跟独立 ALU x6 和依赖 ADD x7，选需要多次迭代的操作数 | WB 先提交 DIV；独立指令在 DIV 结果前前进/提交；依赖者等待实际写回；提交顺序和算术值正确，无二次退休 |
| T02 | DIV/MUL 写 x5 后跟 ALU/MUL/DIV/Load/SC/AMO/FP→GPR 再写 x5；再测 rd 等于 rs1/rs2 | 所有整数写 WAW 停，老结果不覆新值；x5 写回后恢复；任意时刻一个所有者 |
| T03 | ID leave、EX not ready、WB commit、结果到达但 not granted、最后 grant 分开观察 | busy 精确从 ID 至实际 write；commit 不清；ID stall 无重复 set；不同 rd set/clear/多项 kill 同拍全生效；同 rd 优先级待 Q02 |
| T04 | MUL/DIV 各作为 rs1/rs2；用于 Store/SC/AMO 数据、branch/JALR、FP usesGpr；结果写回同拍 decode 读取 | 所有真实源不读旧值，unused 编码不误判；grant 后 ID 旁路；未获 grant 数据不放行；普通 ALU 链仍正确 |
| T05 | 在 ID 预约但 EX 未发、MUL P1/P2/P3/P4、DIV 迭代/快速结果保持期间，制造老 WB fault；另测 EX branch 取消年轻 ID | 被 kill 的对应预约/级作废、永不写；同 rd 立刻复用后旧结果不污染；多条年轻同时取消；branch 不能取消较老 MEM/WB MDU |
| T06 | 人为延迟 WB 授权，使 DIV 快结果先于 commit；再分别 commit、kill；交叉 same-edge commit/kill/done/推进 | 授权前无架构写，commit 后恰一次写，kill 后零写；字段/身份不串项；同拍 oracle 按 Q03，不用模型自行决定 |
| T07 | 普通 ALU WB 与一个已提交 MDU 结果同拍，多拍连续后台结果 | 长延迟每拍选一；普通 WB 完整保持、不 retire/CSR commit/改 PC，最后一次写/提交；MDU WB 自身与其他 MDU 写同拍按 Q06 |
| T08 | 对齐 MUL/DIV done，同时还有普通 WB，混合不同 rd 与同源连续结果 | 检查冻结来源顺序；未选 valid/tag/data 保持；grant 独热；最终两结果值正确，无 starvation；双方持续有效压力包含在随机/形式化 |
| T09 | result.ready 反压 0/1/多拍/超过流水长度；接近满容量到达、release+accept 同拍，结果已完成但未提交 | 结果稳定，所有已接收项台账守恒，不覆 P4/持有 DIV；ready 正确限制容量；解反压后最终完成；容量数值待 Q04 |
| T10 | 老已提交 DIV x5、随后 faulting load、年轻 MUL x6；trap 周期同时有 x5 result；handler 先独立工作后读 x5 | 只取消 x6，x5 存活；trap PC/cause/tval 正确；handler 读 x5 停/恢复；写回不是第二次退休，trap+write 同拍按 Q03/Q06 |
| T11 | MDU 前、EX/MEM/WB 中、commit 后及结果反压时拉 enabled interrupt；与普通 WB/redirect 同拍 | 停新发射、未提交流水排空、无需等待后台；mepc/next PC 正确；handler 使用 busy rd 等结果，后台不被取消；MMIO 在途仍等响应 |
| T12 | MUL/DIV 已提交未写回，CSR 不依赖 rd 也需等；MUL 在 EX 未授权、CSR 在 ID；最后两来源一起写回 | CSR 等空、老 MDU 继续推进/提交/写；最后清位后恢复；mstatus/sstatus/frm/fcsr 别名和 CORE-003 依赖保持；CSR 等待计数按 Q13 |
| T13 | 后台 DIV 与 FENCE/FENCE.I/SFENCE.VMA/satp 写/MRET/SRET 交织；WB trap 同拍年轻请求 | 原请求/flush/target/privilege/操作数语义保留，老后台存活；年轻取消；本步不伪造 MSHR/pending-store 或新 MMU 握手 |
| T14 | 后台 DIV 尚未完成时 WFI 退休、睡眠期间完成、后来 wake/interrupt；有挂起但不可陷入的唤醒源 | WFI 一次退休不等 busy；睡眠后台写成功；唤醒/中断区别与 PC+len 正确；睡眠时钟合同 Q14 |
| T15 | 不同 rd 的连续 MUL，至少覆盖四级同时占用并继续接收；交错 op、bubble、stall/kill；另测后端连续 MUL | 无反压 II=1/4 拍算术；每项 tag/op/data 对齐；kill 只作废对应项；反压后的延迟与正常延迟分报，不能要求满容量永远 ready |
| T16 | 全八种 DIV/REM：divisor=0、RV64 min/-1、W min/-1；W 高 32 位随机且低 32 位为特殊值 | 1 拍算术结果、无授权不写；quotient=-1/min、remainder=dividend/0；所有 W（含 unsigned）结果低 32 位符号扩展 |
| T17 | DIV 正常最大跨度、a=0、a<b、a=b、小数值、正负组合；忙时持续另一 req.valid，done 被反压 | 仅 req.fire 接收，不覆盖当前符号/tag；迭代数学正确、短路径仍正确；算术完成次数、延迟与写回延迟分别记录 |
| T18 | MUL/DIV rd=x0，多笔 x0 与普通 x0 指令、kill、reset 交织 | x0 恒零/无 busy；执行/握手/资源释放和是否占 grant 按 Q11；不借 rd=0 搞错身份 |
| T19 | 原 CORE-001..004 重现和 CSR handler 变体、load/store、adjacent load→store/branch、CSR alias/非法 CSR，加上后台 MDU 干扰 | 原算术/地址/PC/CSR 期望不变，所有旧检查继续执行；CORE-003 基线状态先报告；不能用 csrrs workaround |
| T20 | 阻塞 Load/Store/FP/FP Load 完成时 WB 正被 MDU 占口，FP→GPR WAW、GPR→FP RAW、flags 与 CSR 交织 | 脉冲响应不丢、结果/flags 一次提交，FPR f0 可写；memory/FPU 阻塞方式不变，后台 MDU 仍可继续，Q07/Q08 |
| T21 | FASE drain/enter/empty 时后台 MDU 未完成，暂停读 busy rd/host regWrite/launch 与结果同拍 | 按 Q10 冻结的等待/优先级；调试写不被老结果覆盖、读不见旧值，flightEvents 关联正确；不扩大 FASE 功能 |
| T22 | 各单独/混合 rs1/rs2/rd 来源 stall、CSR 等空、fetch invalid、memory hold 并存、连续 WB conflict；同拍 CSR 改 selector/inhibit/counter | 独立逐拍累计三个事件；重叠口径按 Q13；旧 HPM selector/inhibit/pending/write 可见性不变，后台 write 不增加 instret |
| T23 | DIV x5 提交后多条 ALU 提交、MUL x6 先写回、DIV 后写，另插入 branch/trap/同 rd 新指令 | 参考执行按提交顺序，迟到数据按原身份核对，既不提前比旧值也不多记一次退休；结尾等所有被提交结果，Q18 |
| U01 | 新 MUL 与旧 SignedMul65x65 同一接受序列，五种 op、0/1/-1/min/max/全1/交错位/幂次与其±1、随机 64 位有符号/无符号/W | 同时与独立 BigInt 数学 oracle 比值，旧 3 拍与新 4 拍按序列对齐；无 kill 对等价性，kill/反压另加身份台账；保留旧 50k 向量范围（现有测试位置见 R-MUL） |
| U02 | 八种 DIV/REM 与独立有符号向零截断/余数/无符号/W 模型；特殊值和随机符号/幅值 | 溢出/0 按 S 8.1 独立处理，不用 DUT fast 条件反向生成期望；算术与协议分别判错 |

定向用例逐项包含 reset 前后、最短与较长反压、接收/结果/取消在同一边界、字段发生变化、最终排空。Q14 未冻结前不能把“reset 永远不在途”作为限制，也不能在最后退休后立即停止观测掩盖未写回结果。

## 4. 随机验证与独立模型

| 组 | 层次 / 随机维度 | 独立检查与记录 |
| --- | --- | --- |
| A | 记分板/仲裁/接口：源/目的、ID 停顿、多个来源完成、commit/kill、ready、释放/重用 | 以接受/预约/提交/取消/实际写台账作为 oracle，不复制 DUT next-state；每拍验 busy 所有权、grant/稳定性/一次提交；含非法刺激的 negative 测试须明确分开，不能把环境违规混入合法随机结果 |
| B | 真实 MUL/DIV：operand/op/tag、req bubble、result backpressure、提前/延后授权、逐级 kill | BigInt 数学 oracle + 独立生命周期模型；保持真实 4 拍 MUL 和真实 radix-4 完成规律，模型延迟随机仅用于接口层压力，不声称真实算术可任意延迟 |
| C | 程序：MUL/DIV/ALU/branch/Load/Store/CSR/fence/WFI 与异常、中断交织；内存响应延迟、输入反压随机化 | 参考模型逐条提交比对并在迟到值可用时关联验证；检查指令数、PC/trap/寄存器/内存副作用、完成台账最终空；Q18 冻结后才能实现完整 checker |
| D | HPM：事件重叠、selector 合法/非法、inhibit、counter overwrite/wrap、CSR write/trap/retire 同拍 | 独立即时计数模型对所有 CSR 可见拍；保留旧事件 ID 行为、旧写优先级；随机覆盖新 ID 与合法上界变化 |

记录每组所有 seed、每 seed 接收/提交/写回数量、程序指令数、最大 hold/占用、操作/冲突/kill 交叉覆盖、失败最小重现和 waveform。执行前登记随机预算，完成后报实际种子数/指令数；本阶段没有编造已跑数量或通过率。覆盖计数不能代替逐条比对，不删失败种子；长延迟无限等待只有在环境明确不满足进展假设的测试中可作为保持检查，合法有界等待必须完成。

## 5. 形式化计划

按 S 第 10.2 节逐一实现 F01–F14，T 第 4 节六项最低必证性质为 F01–F06。建议目录沿用任务书的 `verification/formal/backend/`；当前未创建 harness/RTL/SBY 文件。

| 层次 | assert / assume / cover 边界 | 证据要求 |
| --- | --- | --- |
| 记分板 | assert 所有者唯一、busy iff 台账、ID RAW/WAW、kill 清理；assume 只约束合法输入身份/年龄及握手；cover 同拍不同 rd set/clear/多 kill、同 rd 复用 | 与真实 ID 预约定义一致，不仅抽象 FU req；BMC 深度及归纳结果分开 |
| 写口仲裁 | assert grant 独热、后台优先、WB 保持、未选结果保持；允许 MUL/DIV 同拍到达 | 不假设 result.valid 独热；内部来源公平性属于 DUT/冻结策略，不能直接 assume grant |
| MDU 生命周期 | assert commit 前不架构写、kill 后永不写、committed 不被 redirect 杀死、容量守恒；cover early done、busy/held、same-edge kill/commit/done | 输入合法约束不能排除正常 trap+result、背靠背请求或 result backpressure |
| 进展 | 已提交未完成项在 Ldone 内算完、外部写口在 Lport 内可用；另明确等待授权/老流水前进与仲裁公平性 | Ldone 包括迭代/包装的准确周期；Lport 不能掩盖内部优先级饥饿；Q05/Q12 冻结后写界值和证明界 |
| 可达性/复位 | cover F13/F14，assert reset/睡眠合同；检查 reset/assume 是否造成空洞 | 不以 cover witness 代替 eventual completion，不永久 assume reset 或 assume 没有 kill |

验证前在 Alan 检查 Yosys/SBY/solver/前端支持。缺工具先报告安装方案并等明确授权，不能本轮安装。对每条性质报告：源 SHA、参数、生成 RTL SHA、harness/SBY SHA、模式/引擎/命令、实际 BMC 深度、归纳是否完成、活性环境假设、cover witness、原日志和失败/unknown/timeout/未完成项。**当前这些数值与运行结果均未确认/未运行**；冻结配置后不能为了通过减深度、增不合理 assume 或改阈值。

## 6. Alan 全回归、ACT4 与退休比对

以下是阶段二的**计划命令/工作流，未执行**。实际 Alan checkout 路径、工具环境/兼容 wrapper 与 source/config 必须现场核实；不能把本地路径自动当 Alan 路径。阶段一没有跑改动前 sbt 基线。

| 类别 | 计划入口 / 所在 cwd | 判定 |
| --- | --- | --- |
| 改造前完整基线 | 对已核实的基线 SHA，在 Alan `design/` 执行 `sbt test` | 保存 suites/tests passed/failed/ignored、全 log/退出码；源码统计 test 名称不是基线通过数；基线失败先提交问题，不免除后续全通过 |
| 相关模块快速回归 | Alan `design/`，`sbt 'testOnly flow.multiplier.* flow.divider.* flow.backend.* flow.core.* flow.sim.BreezePrivilegeFlowSpec flow.sim.BreezeWfiFlowSpec flow.fase.*'` | 在冻结后确认实际新增 suite 已包括；仅是中间检查，不能替代完整 sbt test |
| 改造后完整回归 | Alan 同配置 `design/`，`sbt test` | 全部通过；通过 suite 与 test 数各不小于基线；逐项解释经批准的迁移，ignored/skipped 不算通过 |
| ACT4 corpus | Alan 仓库根 `make -C verification/act4 build EXTENSIONS=I,M,Zmmul`，若 corpus 未备齐则先按现有 fetch 流程 | 明确版本/corpus manifest 和实际 RV64IM 相关全集，不减少测试或仅默认 I；相关构建入口见 `verification/act4/Makefile:19-25` |
| ACT4 执行 | 根目录 `python3 verification/act4/scripts/run_linux_soc_suite.py --profile single --elf-dir <已核实完整ELF目录> --output-dir <该SHA全新输出目录> --include-extension I --include-extension M --include-extension Zmmul --fresh-build` | selected==ran 且全部 PASS，0 FAIL/TIMEOUT/INFRA_ERROR；筛选功能源码 `verification/act4/scripts/run_linux_soc_suite.py:23-31,49-70`，统计见 :77-99；可跑更广 corpus，但单列 I/M/Zmmul 结果 |
| tandem | Q18 确认的 checker/参考模型/完整逐条比对入口 | 退休顺序及迟到结果正确关联、结尾排空，日志/差异 0；当前命令**未确认**，不得编造“已存在的 Spike 回归” |
| 随机程序 | C 组生成器/loader/checker，入口在 Q18 后补 | 逐条比对通过，报告实际 seed/指令数、trap/interrupt 数量、完成未决台账；既不只比最终内存签名也不把 ACT4 PASS 当随机程序证明 |

若现有退出/trace/API 无法表达新行为，依据 Q09/Q14/Q15/Q18 先提交所需文件、接口与等强验证变更，不绕过范围限制。ACT4 产品路径对内存层次的覆盖与模块假内存回归分开报告。

## 7. 资源、时序与性能

| ID | 比较配置 / 激励 | 测量与验收 |
| --- | --- | --- |
| P01 | 同 Alan、Vivado 版本、单核顶层/config、器件、100MHz 约束、综合/retiming 设置，基线与改造后各一份 | multiplier hierarchy 的 DSP/LUT、总核 DSP/LUT/FF、100MHz WNS/最差路径；列出 SB/仲裁路径是否成为新最差路径、startpoint/endpoint/logic levels。DSP 类型/配置 Q16；不把约16 DSP/省6k LUT 估计写成实测 |
| P02 | 一个除法密集、一个乘法密集程序，固定可校验输入/输出、相同 binary/布局/config/内存模型 | 改造前后完整周期数、instret、sb_stall_mul/div 和 wb_port_conflict；纯依赖链与独立操作块分别解释瓶颈；程序结束按 Q14 等后台写完，不能早退出虚报加速 |

基线没有新 sb_stall_* 事件，旧版本该字段报告“未实现/不可用”，不能填 0 或假装直接比较。可以比较周期数，改造后给出新事件及相同口径的独立模型验证。性能软件/计数读取入口及命令在冻结后按实际可用环境补，本轮未创建或执行。

综合与时序命令**未确认**，Q16 要指定顶层/约束及匹配报告。单独报告综合 timing 与实现后 routed timing，T 本步要求的综合资源/100MHz WNS不能借其他历史配置完成。若 SB/仲裁成为新最差路径或 WNS 不满足目标，保留真实路径/失败结果，不自行加时序例外、降频或改接口。

## 8. 阶段一检查与阶段二完成条件

阶段一检查：T 2.1 的章节均有对应 spec；T 4 的所有验收类别均进入 REQ 表；S01–S16/F01–F14 有可追踪测试；源码描述附文件/行号；未覆盖的行为全部链接 Q01–Q18；硬件证据标为未运行。这只是文档静态完整性检查。

阶段二必须等用户明确宣布 spec 冻结，解决冻结阻塞项并记录旧检查迁移许可后才能开始。最终 T01 完成需要 T 4 全部证据，形式化有界/归纳边界、回归数、ACT4 manifest、tandem 差异、随机种子/指令、PPA/timing、性能周期均可核查；任何未完成项不得改为推断通过。
