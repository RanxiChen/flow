# Breeze 后端测试计划：T01 冻结稿

状态：**冻结稿，待用户宣布冻结；所有新验证项未运行**。依据：[任务书](tasks/T01-backend-scoreboard-mdu.md)（T）、[后端设计](backend-pipeline-design.md)（D）、[RTL spec](backend-rtl-spec.md)（S）、[阶段一审阅决定](tasks/T01-review.md)（R）。源码基线 `d73a546a9f1acd51a4c99b20d3985d451a8d8be0`，相对 `d5672f5` 无代码变化；分支 `feat/pcie-fase-20260920`。阶段一只修文档；阶段二按本计划创建测试/harness/SBY 并在 Alan 执行。

## 0. 证据状态与执行门槛

- Q01–Q18（R 第2节）与 A01–A08（R 第4节）均已决定，下面逐项使用确定期望；本计划没有待定项。实现中遇到未覆盖的行为必须停下提问，不自行补期望。
- “现有入口”只证明源码中有 suite/检查。REQ/T/U/A–D/P/S/F 是追踪编号，不能当作新测试已存在/已执行的证据。
- 所有硬件执行在 Alan。阶段二第一件事是在 `d5672f51bf0ec67465148c02af970c70464bec68` 上完整 `sbt test`，逐 suite 记录 passed/failed/ignored。R/Q17 将回归判定改为**无新增失败、通过 suite/test 数不少于基线**；已有失败只记录、不在 T01 修复。其他验收类别仍须全部完成。
- 源 SHA、参数、工具、命令/cwd、退出码、原始日志、seed/指令数与生成RTL/harness版本逐次保存；同机同配置比较。模块仿真/形式化不能代替系统比对、ACT4、综合时序或性能。
- T01 不做参考模型比对、不自建参考模型（R §4/A04）；runner 只做 S 9.1 的协议自洽检查（A05）。取值正确性由完整 sbt 回归、MDU 自检程序和 ACT4 RV64IM 提供。

## 1. 现有入口、批准的迁移与缺口

下表为源码审查，**未运行**，行号对应上述基线。

| 回归编号 | 已找到的源码位置 | 可复用范围 / 新合同 |
| --- | --- | --- |
| R-MUL | `design/src/test/scala/multiplier/RiscvMulUnitSpec.scala:23-64`；`design/src/test/scala/multiplier/SignedMul65x65Spec.scala:178-270,287-413` | wrapper 旧3拍/flush迁移为4拍/commit/kill/保持（R/Q15已批准）；旧 SignedMul65x65 及全部测试原样保留，五种算术结果继续验证 |
| R-DIV | `design/src/test/scala/divider/RiscvDivUnitSpec.scala:25-65`；`design/src/test/scala/divider/UnsignedRadix4DividerSpec.scala:43-89` | 原unsigned算术/flush检查保留；按A03迁移：驱动在req.fire后补commit，原flush用例改为对未提交项发killUncommitted；向量/期望/次数不变；新增快结果测试，不删检查 |
| R-BE | `design/src/test/scala/backend/BreezeBackendMulSpec.scala:61-92`；`design/src/test/scala/backend/BreezeBackendDivSpec.scala:70-128` | 按A03迁移：观测点从MDU memWbValid时的wbData改为该rd的后台写回事件（或写回后RF值）；向量/期望/次数不变，不伪造WB数据 |
| R-REDIR | `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala:133-210` | 保留WB fault/xRET/satp与年轻分支/访存/sfence/flush同拍检查；新增单元取消及老后台存活 |
| R-CORE | `design/src/test/scala/core/breezecoreSpec.scala:900-1240,1761-1918,2299-2667` | 保留CSR/访存/退休/分支及CORE-003 handler变体；真实通过数与已有失败未确认，以Alan基线为准 |
| R-HPM | `design/src/test/scala/core/BreezeCsrPipelineSpec.scala:47-160`；`design/src/test/scala/core/breezecoreSpec.scala:323-393` | 原CSR可见拍计数模型/selector/inhibit/overwrite保留；前者:130-131非法selector=11改14已批准，新增11/12/13合法事件 |
| R-FP | `design/src/test/scala/backend/BreezeBackendFpSpec.scala:12-64`；`design/src/test/scala/backend/BreezeBackendFpMemorySpec.scala:32-134` | 保留FS Off/依赖/flags/FP访存；新增FP→GPR WAW、GPR→FP RAW、GPR写口冲突，不改FPU内部 |
| R-PRIV | `design/src/test/scala/sim/BreezePrivilegeFlowSpec.scala:50-116,118-248,381-477`；`design/src/test/scala/sim/BreezeWfiFlowSpec.scala:29-78` | 原trap/xRET/中断/WFI语义保留；新增后台存活、handler等待、睡眠写回 |
| R-RF | `design/src/test/scala/core/BreezeRegisterStorageSpec.scala:10-91`；`design/src/test/scala/core/breezecoreSpec.scala:13-48` | 原x0/写穿透/FPR f0检查保留；新增实际grant写穿透与MDU x0不发射 |
| R-FASE | `design/src/test/scala/fase/FaseIntegrationSpec.scala:15` | 确认suite入口，新MDU覆盖未确认；新增busy为空之前不能enter/读写/launch |
| R-ACT | `verification/act4/Makefile:19-34`；`verification/act4/scripts/run_linux_soc_suite.py:21-31,42-70,77-99` | 完整枚举I/M/Zmmul corpus、single profile及实际selected/ran/results，不仅默认I |
| R-TANDEM | `design/src/main/scala/interface/interface.scala:246-262`；`design/src/main/scala/backend/BreezeBackend.scala:1735-1737` | 退休payload加rdPending、新增后台写回事件；runner协议自洽检查5条规则（S 9.1）；不做参考比对 |

已批准迁移只有三类（R/Q15）：completion独热→grant独热+每源保持；MUL wrapper3拍/全flush→4拍/commit/kill；HPM非法11→14。另按R §4/A03迁移：后端MUL/DIV测试观测点、DIV wrapper驱动（见R-BE/R-DIV）。旧SignedMul65x65及其测试原样保留，其余旧断言/测试不改。每次实际迁移仍在阶段二报告列“旧检查→新检查”、源位置、执行结果；不减随机向量、不跳suite、不放宽算术期望。

## 2. 需求到验证追踪表

所有行状态：**新合同未实现，测试待编写，形式化/回归未运行**。实现边界见 S 第1–9节。S01–S16/F01–F14见S第10节；定向见第3节，随机组见第4节。行中的R/Q编号为决定来源，已不是待定条件。合法环境为握手输入、年长指令顺序提交、无永久外部停顿；违反环境的negative测试单列。

| 需求 / 来源 | 确定期望 / 实现边界 | 定向 | 随机 | 性质 / 断言 | 回归 / 新问题 |
| --- | --- | --- | --- | --- | --- |
| REQ01；D3/4，R1/Q03，S1/4 | EX接收、WB一次提交；后台写不另退休；独立指令可推进 | T01/T07 | C | F07/F09，S04/S08/S09 | R-BE/R-CORE/R-TANDEM |
| REQ02；D5，R1/Q01/Q11，S2 | busy为x1–31共31位；同rd跨未提交级间/已提交单元至多一项；x0无项 | T02/T18 | A | F01/F02/F12，S01/S02 | 新SB模块/R-RF |
| REQ03；R1/Q02/Q03，S2.2 | 只在WB commit非零MDU置位、实际写清；kill永不改busy；同rdset/clear互斥 | T03/T05/T06 | A | F02/F03/F11，S02/S05/S10 | 新SB模块/R-BE |
| REQ04；R1/Q08，S3/附录A | ID真GPR rs1/rs2 RAW、rd WAW；EX/MEM/WB未提交项与effectiveBusy两组均检查 | T01/T02/T04 | A/C | F01/F10，S03 | R-BE/R-CORE/R-FP |
| REQ05；R/Q02/Q08，S3.2 | 实际写回拍busy掩码解除、RF写穿透；未grant项不解除；无EX MDU旁路 | T04/T08 | A/C | F10/F11，S03/S08 | R-RF/R-CORE |
| REQ06；R/Q01/Q03，S4 | commit最老未提交、kill全部未提交，脉冲无ready；先commit后kill；输出只已提交，无事务标签 | T05/T06/T09 | A/B | F03/F05/F07/F08/F11，S04/S05/S07 | 新协议模块/R-MUL/R-DIV；commit+kill同拍只在单元级（A06） |
| REQ07；R/Q06/Q07，S5 | 仅普通wb_en&&rd!=0无trap整数WB让拍；四级全保持，无退休/副作用 | T07/T08 | A/C | F04/F09，S06/S09 | 新仲裁/R-CORE/R-HPM |
| REQ08；R/Q12，S5.3 | DIV>MUL>普通WB；未选保持；DIV释放/再接收隔拍、MUL阻塞停流水，内部不得饥饿 | T08/T09 | A/B | F04/F05/F06/F13，S06/S07 | 新仲裁/真实MDU |
| REQ09；D7，R/Q03/Q06，S6 | WB trap取消全部未提交MDU，保留后台，handler依赖等最终写 | T05/T10 | C | F03/F07/F13/F14，S05/S13 | R-REDIR/R-PRIV |
| REQ10；R/Q06/Q10，S6 | 中断只排空未提交流水及阻塞memory/FP，不等busy，不用FASE empty | T11 | C | F07/F10，S13/S14；PC系统仿真 | R-PRIV/R-TANDEM |
| REQ11；D7，R/Q08/Q14，S6 | CSR按csrDrainOk离开ID（busy==0且EX/MEM/WB无已发射MDU，A07），旧状态/rd/别名hazard不改；只挡年轻指令，老MDU继续前进 | T12 | A/C | F10/F14，S14 | R-CORE/R-HPM/R-PRIV |
| REQ12；R/Q03/Q09，S6.3 | EX branch/JALR/SFENCE及MEM FENCE.I不kill、只抑同拍EX，控制事件只在!downHold拍发起（A02/A08/B01）；WB异常/xRET/satp/WFI及中断发kill | T13/T05 | C | F07，S13 | R-REDIR/完整sbt |
| REQ13；R/Q14，S6 | WFI一次退休不等busy、睡眠不停时钟，后台仍写；wake和interrupt不同 | T14 | C | F07/F12，S14 | R-PRIV/R-TANDEM |
| REQ14；D9，R/Q04/Q16，S7 | 65×65积，4级末级输出，无反压II=1；op/rd/valid/committed与积对齐，无额外FIFO | T15/U01 | B | F08/F13，S11 | R-MUL/新MUL |
| REQ15；R/Q03/Q04，S7 | 四级按mulEnable=!P4.valid\|\|(P4.committed&&outReady)整停（A01）；kill停顿中仍生效且只杀未提交 | T05/T09/T15 | B | F03/F05/F08/F11/F14，S07/S10/S11 | 新MUL |
| REQ16；D9，R/Q15，S7 | 五种MUL值与原样旧单元和独立数学模型一致；旧测试原样 | U01 | B | S11；完整乘法数学另用等价性 | R-MUL/SignedMul65x65Spec |
| REQ17；R/Q05，S8 | 0除/有符号min/-1含W在req接受后1拍内部done，commit前不valid，结果规则不变 | T16/U02 | B/C | F07/F11，S12 | R-DIV/R-BE |
| REQ18；R/Q05，S8 | radix-4保留，ready=!occupied，release/accept不同拍；done等待授权/写口不丢 | T09/T17/U02 | B | F05/F08/F06，S12 | R-DIV/新DIV |
| REQ19；T0.5，R/Q15/Q17，S9 | CORE-001..004/CSR/访存/重定向行为和旧检查保留；已有失败只记录 | T19 | C | S13/S15；需整核回归 | R-CORE/R-REDIR/完整sbt |
| REQ20；R/Q07/Q08，S5.2/9 | memory仍阻塞，脉冲response一项捕获；FPU用已有outReady反压，flags一次提交 | T20 | B/C | F09，S09/S15 | R-FP/R-CORE |
| REQ21；R/Q10，S9 | FASE empty含busy空，enter/寄存器读写/launch均等后台完；中断边界独立 | T21 | C | S14；整合仿真 | R-FASE |
| REQ22；R/Q13，S11 | 11=MUL stall、12=DIV stall、13=WB让拍；双来源都计，合法上界13/非法14；selector仍4bit | T22 | D | S16；独立逐拍计数模型 | R-HPM/完整sbt |
| REQ23；R/Q18，S9.1 | 顺序退休；MDU rdPending，data无效；晚写rd/data与pending按rd做协议自洽检查，后台不另退休 | T23/T10/T11 | C | 不适用：runner检查 | R-TANDEM |
| REQ24；T4，R/Q17 | Alan d5672f5完整基线；无新增失败、通过数≥基线；ACT4 RV64IM全通过 | T19及全部定向 | C | 不适用：suite结果为运行证据 | 完整sbt/R-ACT/R-TANDEM |
| REQ25；T4，R/Q13/Q14/Q16 | 新旧乘法器OOC DSP/LUT；单核100MHz WNS/路径；两程序周期/计数且结束等busy空 | P01/P02 | 不适用：固定输入测量 | 不适用：综合/STA/性能测量 | Vivado/性能 |
| REQ26；T4，R3，S10 | F01–F06最低性质及F07–F14扩展，报告实际深度/归纳/假设/未完成 | T01–T18冲突cover | A/B | F01–F14/S01–S16 | 模块SBY |
| REQ27；D8/10，R/Q01/Q03，S4 | 公共commit/kill/保持可扩展FPU bank/rm/flags、L1D标签/error；MDU无事务标签不删除L1D标识 | 接口审阅/T06 | 不适用：未实现未来源 | F05/F07/F08仅验证本步公共合同 | 后续FPU/L1D另立任务 |

## 3. 定向与等价性场景

N拍指当前组合值，末沿更新，N+1观察更新。初始化寄存器/CSR；记录req.fire、单元commit/kill、WB退休、结果grant/实际写、busy、异常PC及HPM。模块台账可用测试序号，RTL不带该序号。期望来自R/S及独立数学模型，不能复制DUT实现。

| ID | 激励 / 边界 | 精确期望 |
| --- | --- | --- |
| T01 | DIV x5，独立ADD x6，依赖ADD x7；正常多迭代 | 按S6.1：N ID、N+1 EX接收、N+2 MEM、N+3 WB提交末置busy；依赖ADD在MEM/WB由级间hazard停，此后busy停；实际write拍仅依赖阻挡可同拍离开ID。DIV只一次退休 |
| T02 | MUL/DIV写x5，后跟ALU/MUL/DIV/Load/SC/AMO/CSR/FP→GPR写x5，覆盖rd=rs1/2 | 老MDU在EX/MEM/WB及已提交busy期间都WAW停，实际写拍可释放；同rd跨两类状态始终最多一项，不等FU算术done就解除 |
| T03 | 分别观察ID离开、EX notready/req.fire、WB commit、done未grant、实际write；交叉不同rdset/clear | ID/EX接收不置busy；WB commit末置、真实write末清，kill不改；不同rd同拍均生效；断言同rdset/clear互斥，不能编优先级掩盖违规 |
| T04 | 逐行遍历S附录A：两GPR源、store/atomic/sfence、JALR/branch、CSR immediate、FP跨bank、FPR编码 | 真实非零GPR源RAW/目的WAW；zimm/FPR编码不产生新MDU hazard；旧CSR保守检查仍保留。只有grant项同拍write-through释放，EX无MDU旁路 |
| T05 | 在未接收EX、MUL四级、DIV迭代/早完成等阶段发WB kill；另EX branch/SFENCE与老MEM/WB MDU同拍 | WB kill未提交项永不写、从未占busy；已提交项保留且busy仅真实写可清；EX/MEM redirect不kill老项，抑同拍EX req；rd复用不受旧项污染。FENCE.I在MEM不发kill（A02） |
| T06 | fast DIV早done后延迟commit；分别commit/kill；同拍commit+kill、done+kill；单元停顿时发脉冲 | 输出valid只已提交；commit总被同拍接受且命中最老未提交，先commit后kill保留该项；无ready/标签。负测空单元commit触发断言；整核不可达，不强造（A06） |
| T07 | 已提交MDU write与普通ALU/Load/CSR/FP→GPR WB写相撞；另Store/branch/x0/FPR/MDUcommit/trap/xRET/WFI | 前组四级整停，无普通retire/副作用，解除后一次提交；后组不因整数口停，后台write仍一次。CSR另受busy空条件；MDUcommit与不同rdwrite同拍set/clear成立 |
| T08 | DIV/MUL均已提交、同拍valid并有普通WB整数写；连续MUL结果 | DIV先write，MUL保持rd/data/valid且整流水停；DIV释放拍不接下一DIV，下一拍MUL可write；普通WB每次仅必要让拍，grant独热，不假设valid独热 |
| T09 | ready反压0/1/多拍/超过MUL长度，已算完未commit，release与req并列 | 已提交结果直到接收保持；DIV occupied时ready=0，release沿后下一拍才接受；MUL阻塞四级不推进/不ready但kill生效。WB让拍拖住使P4未提交：四级停住、P4不被覆盖，随后commit则输出、kill则丢弃（A01） |
| T10 | 已提交DIV x5，年轻load fault WB、未提交MUL x6；trap与x5 write同拍，handler随后读x5 | x6取消且不改busy，x5存活/实际write清；trap cause/tval/PC正确，handler只在真正结果到达后读，后台无二次退休 |
| T11 | interrupt在MDU EX/MEM/WB及commit后/结果反压时出现，混合MMIO/FP | 停新正常发射，等待EX/MEM/WB及阻塞memory/FP空，删除MUL/DIV wait条件；不等busy，handler读busy rd仍等待；不使用FASE empty。WB仍有效则本拍不能接受interrupt |
| T12 | 已提交MDU未write后接CSR；MDU仍在EX/MEM/WB未提交时CSR已到ID（busy此时为0）；CSR别名/rd hazard；最后一次write | CSR按csrDrainOk等空：后一情形busy为0也不得离开ID，直到该MDU提交并写回（A07）；普通CSR保守hazard原样；不挡老MDUcommit/write，RF更新后继续；mstatus/sstatus/frm/fcsr别名及CORE-003期望保留 |
| T13 | 分支/JALR/SFENCE.EX，FENCE.I.MEM，trap/xRET/satp.WB，与后台MDU及年轻req交织 | 按S6.3逐种核查target/级/kill；EX/MEM不误杀MEM/WB老项，WB kill未提交项。FENCE.I在MEM发起、不发kill、只抑同拍EX（A02）；EX/MEM控制事件在wbPortStall/estopWait期间不发、结束后只发一次；dmem/flush请求在MEM保持期间也只发一次，响应经捕获/反压不丢（A08/B01） |
| T14 | 未完成DIV时WFI WB，睡眠写回，唤醒源/中断资格分开；另ESTOP/复位在途 | WFI不等busy、只退休一次且后台时钟不停；ESTOP在WB退休前等busy=0；reset清所有live/busy/capture，复位后不出现旧结果 |
| T15 | 连续不同rd MUL，使四级占用；交错五种op/bubble/反压/commit/kill，后端连续MUL | 无反压4拍、II=1，op/rd/valid/committed对齐，无FIFO；停顿期间commit/kill仍更新，已提交不丢；P4未提交停住按A01 |
| T16 | 八种DIV/REM：除0、64/W min/-1；W低32特殊、高32随机 | req.fire后下一拍fast内部done，commit前valid=0；除0商全1、余数有效dividend，溢出商min余数0；所有W最终低32符号扩展，分记算术/req→write延迟 |
| T17 | 正常最大跨度、a=0/a<b/a=b、正负/小值；忙时req.valid持续，done反压 | radix-4数学和短路径保留；仅req.fire接收，不覆符号/rd；occupied直到write或未提交kill，释放拍不能同拍接下一笔 |
| T18 | MUL/DIV rd=x0与非零源hazard、kill/reset混合 | true源依赖仍检查，但x0不发FU、不commit单元、不setbusy、不占grant；作为无GPR写普通指令WB退休且rdPending=0，x0恒零 |
| T19 | CORE-001..004、CSR handler变体、访存/branch、非法CSR加后台干扰 | 旧期望原样，逐suite与d5672f5基线对比无新增失败，通过数不少；旧已有失败记录不修，不用handler workaround |
| T20 | WB整数写让拍时阻塞访存response脉冲到达、FP完成保持，FP跨bank与flags | dmem一项捕获所有结果/异常字段，MEM可消费时一次清；不重复发请求/消费；FPU outReady=0保持，不加capture，恢复一次消费，flags/FP结果不丢 |
| T21 | FASE enter/drain/empty、host读写/launch请求在busy非空及最后write拍 | busy非空不能宣告empty或进行enter/读写/launch；后台完成后按原FASE握手继续，host写不被老结果覆盖；interrupt不因此多等；flightEvents保留 |
| T22 | MUL/DIV源/目的级间/记分板hazard重叠、ID invalid、纯req资源等待、CSR按csrDrainOk等空（含EX/MEM/WB已发射MDU，A07）、其他hold并存；selector/inhibit/overwrite | 11/12按S11逐拍各计一次，双方都成立两个都计；13严格等WB让拍；grant屏蔽后该依赖不计，旧CSR采样/写优先不变，14及以上非法，物理selector仍4bit |
| T23 | DIV x5先提交、普通指令继续退休、MUL x6先write；同拍MDUcommit/另一rdwrite及trap | rdPending=1时rdData无效；runner按S 9.1五条规则检查（无pending的晚写、重复晚写、同rd重复pending、pending期间普通写、结尾残留pending均报错），并对每条规则各写一个负测确认会报错；后台不再退休 |
| U01 | 五种MUL边界/随机与旧SignedMul65x65及独立BigInt比较 | 旧3拍与新4拍按接收序列对齐；算术值不因早提交改变。旧单元/测试原样，现50k随机范围见 `design/src/test/scala/multiplier/SignedMul65x65Spec.scala:223-245`；kill/反压另用台账 |
| U02 | 八种DIV/REM独立向零截断/余数/unsigned/W模型，符号/幅值随机及特殊值 | 独立判除0/溢出、符号恢复/W扩展，不从DUT fast条件生成期望；算术与生命周期错误分开 |

每项覆盖reset在途、字段变化、最短/较长反压、同拍接收/提交/取消/写以及最终排空；不以“永远不在途reset”简化环境，不在MDU提交后立即停观测。

## 4. 随机验证与独立模型

| 组 | 层次 / 随机维度 | 独立检查与记录 |
| --- | --- | --- |
| A | SB/仲裁/接口：rd/source、级间占用、WBcommit/kill、grant/ready、释放/重用 | 台账区分未提交级间与已提交未写回；busy只后者。接收不置busy、kill不改busy；同rdset/clear互斥、实际grant RF数据正确 |
| B | 真实MUL/DIV：operand/op/rd、req bubble、早done/延commit、反压、各级kill | BigInt数学+按接受顺序未提交列表；commit最老、kill全部未提交、已提交输出稳定。真实4拍MUL与radix-4不能随机伪造成任意算术延迟；含WB让拍使P4未提交的情形 |
| C | 程序：MDU/ALU/FP跨bank/branch/访存/CSR/fence/WFI/异常/中断，memory延迟 | 自检程序：程序自行计算期望并比较最终寄存器/内存，经ESTOP/tohost报告；runner协议自洽检查全程开启；不建参考模型 |
| D | HPM：双来源/级间/忙表/其他hold重叠，11/12/13/非法14，inhibit/overwrite/wrap | 独立CSR可见拍模型，旧0–10事件与原写优先保留，后台write不增加instret；不把ID无效/纯资源wait当来源依赖事件 |

阶段二运行前登记预算；结果报告所有seed、实际接收/提交/kill/write数量、指令数、最大hold/占用、操作/冲突交叉覆盖、最小失败重现与waveform。不删失败seed，不编本阶段通过率。有限等待压力应排空；故意永久反压只能验证保持，不能声称证明进展。

## 5. 断言与形式化计划

实现S第10节S01–S16/F01–F14，最低F01–F06；目录建议 `verification/formal/backend/`，当前未创建文件。

| 边界 | assert / assume / cover | 证明与冲突 |
| --- | --- | --- |
| 记分板/级间 | busy iff已提交未write；RAW/WAW；x0；set/clear同rd互斥；kill不改busy；cover WBcommit转busy、不同rdset/clear、写穿透释放 | 不能assume无WAW代替ID检查；台账只在WB commit建立busy关联。F01/F02/F10/F11/F12 |
| 仲裁/流水 | grant独热，允许多源valid；DIV>MUL；普通整数WB让拍四级保持/无副作用；其他WB类别不因口让拍 | 不assume result.valid独热或grant公平；F04/F05/F09，S06/S09/S15 |
| 单元生命周期 | commit必有最老未提交项；无ready且同拍接受；先commit后kill；被kill永不write；committed不被kill；req计数守恒 | 允许早done、长反压、kill+done、commit+kill；F03/F07/F08；含P4未提交长时间停住 |
| 结果保持 | valid只committed&&done；valid&&!ready保持rd/data/valid，无kill例外 | F05/F07，S07；未提交内部保存仍是必须验证的合同，不因为外部valid=0而遗漏 |
| 进展 | committed项有界算完、外部无永久hold；DIV一项且释放间隔、MUL阻塞停接收由DUT证明 | F06；实际Ldone/Lport、BMC深度/活性模式运行前登记。有限hold实验与无界公平证明分开，cover不代替eventually |
| 可达性/复位 | cover连续MUL、双源valid、early done、trap后台write、不同rdcommit/write、CSR等待解除；reset清live/busy/capture | F12–F14；commit+kill只在单元接口cover，整核不可达（A06），不伪造cover |

在Alan先核查Yosys/SBY/solver与Chisel生成RTL前端支持。缺工具按T3.2报告安装方案等待授权，本轮不安装。每条性质报告源/RTL/harness/SBY SHA、参数、模式/引擎/命令、实际BMC深度、归纳结果、assume、cover witness、原日志及fail/unknown/timeout/未完成。当前均未运行；禁止减深度、增不合理assume或更改阈值。

## 6. Alan 基线、全回归、ACT4 与退休比对

以下为**冻结后阶段二计划，未执行**。Alan checkout/cwd/工具wrapper须现场确认，保留其他任务和工作区；本阶段不连Alan跑构建。

| 类别 | 计划入口 | 判定 |
| --- | --- | --- |
| 阶段二首项基线 | Alan已核实 `d5672f51bf0ec67465148c02af970c70464bec68`，`design/` 内 `sbt test` | 每suite/test passed/failed/ignored及log/exit，CORE-003以实际结果为准；已有失败只记录 |
| 模块快速回归 | `design/` 内 `sbt 'testOnly flow.multiplier.* flow.divider.* flow.backend.* flow.core.* flow.sim.BreezePrivilegeFlowSpec flow.sim.BreezeWfiFlowSpec flow.fase.*'` | 冻结后确认新增suite实际被包含；中间结果不代替全量 |
| 改造后完整回归 | Alan同机同配置 `design/` 内 `sbt test` | 无新增失败，suite/test通过数各不少于基线；ignored/skipped不算通过，已批准迁移（Q15三类+A03）逐项说明 |
| ACT4 corpus | 根目录 `make -C verification/act4 build EXTENSIONS=I,M,Zmmul` | manifest/版本及RV64IM完整相关集合；源码入口 `verification/act4/Makefile:19-25`，未备齐先按现有fetch流程核实 |
| ACT4运行 | 根目录 `python3 verification/act4/scripts/run_linux_soc_suite.py --profile single --elf-dir <完整ELF目录> --output-dir <该SHA全新目录> --include-extension I --include-extension M --include-extension Zmmul --fresh-build` | selected==ran且全部PASS，无FAIL/TIMEOUT/INFRA_ERROR；筛选/统计源码 `verification/act4/scripts/run_linux_soc_suite.py:23-31,49-70,77-99` |
| trace协议检查 | 阶段二在runner实现后登记实际命令 | S 9.1五条规则全程开启，所有程序运行0报错；每条规则的负测确认能报错。T01不做参考比对（A04） |
| 随机程序 | C组自检程序generator/loader，阶段二登记入口 | 程序内自检最终寄存器/内存+runner协议检查；报seed/指令/trap/interrupt与pending数量 |

Q18要求的现有入口已核查：`design/src/main/scala/sim/BreezeCoreTandem.scala:3-24`仅数据容器；`design/src/main/scala/sim/BreezeCoreTandemParser.scala:23-58`转换事件；`design/src/main/scala/sim/BreezeCoreTandemLog.scala:1-44`格式化；`design/src/main/scala/sim/BreezeCoreSimSupport.scala:301-323,453-497`收集日志；`tests/ref/spike_ref.hpp:1-11`空壳；`sim/breezecore/README.md:3-26`为资产/runner说明。上述范围未找到完整可复用参考执行比对器，按审阅决定停下报告。core目前仅转发trace（`design/src/main/scala/core/BreezeCore.scala:153-155`），rdPending/晚写贯穿core/sim超出Q09文件范围，不能静默扩范围。

## 7. 资源、时序与性能

| ID | 配置 / 激励 | 测量与验收 |
| --- | --- | --- |
| P01 | 新旧乘法器各OOC，同Alan/Vivado/器件/约束/retiming；另单核整机100MHz | R/Q16：XCKU040/DSP48E2、仅推断不例化；允许retiming。OOC报告DSP/LUT对比，整核报告资源/WNS/最差路径及SB/仲裁startpoint/endpoint/levels，不能用OOC WNS替代整机 |
| P02 | 一个除法密集、一个乘法密集程序，相同可核查输入/输出/binary/布局/config/内存 | 前后周期/instret；新版本sb_stall_mul/div、wb_port_conflict及独立计数核对；依赖链/独立块分别记录；ESTOP退休前等busy空，不能提早结束虚报性能 |

旧基线没有新事件时其值写“未实现/不可用”，不填0。综合/时序/性能命令及实际顶层/config路径**未确认**，阶段二按可用环境绑定并报告；本轮没有测量数字。综合timing与实现后routed timing分开；SB/仲裁成为新最差路径或100MHz不满足时保留真实失败/路径，不降频或擅加例外。

## 8. 阶段一检查与停止条件

阶段一静态核查只检验文档：REQ01–27覆盖T4全部类别；T01–23/U01–02/A–D/P01–02与S/F追踪完整；源引用附文件行号；Q01–Q18与A01–A08已经写为规则。静态检查不代表RTL/测试/形式化通过。

本稿为冻结稿，用户宣布冻结后进入阶段二。最终T01硬件验收仍需各类Alan证据；任何未运行/未完成项不能推断通过。
