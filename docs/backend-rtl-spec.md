# Breeze 后端 RTL spec：T01 冻结候选

状态：**冻结候选，待用户确认**。本轮仅文档修订，不授权阶段二。依据为 [后端设计](backend-pipeline-design.md)（D）、[T01 任务书](tasks/T01-backend-scoreboard-mdu.md)（T）和 [阶段一审阅决定](tasks/T01-review.md)（R）；R 对原稿 Q01–Q18 的决定已写入规则。审阅决定与源码/其他决定的冲突统一列在第 12 节“审阅后问题”，不得自行补方案。

## 0. 范围、版本与拍的定义

- 分支 `feat/pcie-fase-20260920`；源码/审阅基线 `d73a546a9f1acd51a4c99b20d3985d451a8d8be0`。该提交相对 `d5672f5` 没有 `.scala/.py/.sv` 差异，下面源码行号对应此基线。
- 本步只实现整数 MDU 依赖检查、记分板、单写口仲裁、EX 发起/WB 提交、MUL 四级/DSP 推断、DIV 快路径/保持、HPM 11–13。访存和 FPU 仍阻塞；未来 FPU/L1D 只预留公共接口语义，不实现 FPR 记分板、MSHR/S2 提交、迟到 Load 或 hartFatal。
- N 拍使用当前寄存器/组合值，周期末上升沿更新，N+1 拍看更新值；`fire=valid&&ready`。`wbCommit` 是真实、无异常、未被让拍的一次 WB 提交；`resultWrite` 是已提交结果获 grant、握手并物理写 GPR。
- **记分板只跟踪已提交且未写回的 MDU**；未提交 EX/MEM/WB 的依赖由级间比较覆盖。MDU 不带事务身份、slot 或 epoch，结果只带 rd/data；验证台账可以用测试序号区分操作，不能把该序号加到 RTL 接口。
- 所有硬件执行在 Alan，当前编译、生成 RTL、仿真、形式化、ACT4、tandem、综合、性能测量均 **未运行**。原 Q01–Q18 不再是未决问题；第 12 节的新问题仍阻止最终冻结。

审阅决定落实索引（均已决定，冲突只列第12节）：

| 决定 | 确定规则 / 对应章节 |
| --- | --- |
| Q01 | 无事务标签；单元按发射顺序commit最老未提交项；2.1/4 |
| Q02 | WB置位、write清位，同rd不能同拍，两rd同时生效；写穿透释放；2.2/3 |
| Q03 | 无握手脉冲、先commit后kill、未提交结果不可见；按redirect级抑制/kill；4/6.3；A01/A02/A06 |
| Q04 | MUL四级末级输出、整体enable/反压、无FIFO、EX资源等待；5.1/7；A01 |
| Q05 | EX fast检测附带req，DIV ready=!occupied，释放与再接收隔拍；8 |
| Q06 | 仅普通非零GPR写WB让拍四级；中断只等未提交流水；5/6 |
| Q07 | dmem一项capture；FPU已有outReady，选择反压；5.2 |
| Q08 | decoder真实GPR名单、FP跨bank，held EX更新保持，无MDU EX旁路；3/附录A |
| Q09 | 公共interface、common事件、RF和HPM额外允许；旧MDU字段删、FP选择独立；1.3；A05 |
| Q10 | FASE empty加busy空，enter/读写/launch等后台；中断独立；9 |
| Q11 | x0 MDU不发单元，普通无写WB退休；2.2/9.1/附录A |
| Q12 | DIV>MUL>普通WB，未来L1D>DIV>MUL>FPU；外部无永久停顿；5.3/10 |
| Q13 | 事件11/12/13、双来源都计、合法上界13；11 |
| Q14 | reset全清、WFI不停后台时钟、ESTOP WB退休前busy空；3.1/6/9 |
| Q15 | 仅三类检查迁移获准，旧SignedMul65x65和其他检查保留；9.2；A03 |
| Q16 | XCKU040/DSP48E2，仅推断、允许retiming；OOC资源/整核100MHz WNS；7 |
| Q17 | 阶段二首项Alan d5672f5完整基线；无新增失败、通过数不少；9.2/测试计划6 |
| Q18 | rdPending和晚写rd/data，以rd关联；先查可复用checker，未找到则停止；9.1；A04/A05 |

## 1. 现状映射与文件边界

### 1.1 流水寄存器、控制与结果

ID/RR 为 decode/读寄存器组合逻辑，接收后进入 EX 的 `idExeReg`，见 `design/src/main/scala/backend/BreezeBackend.scala:64-75,241,337-383`。保持四级，不新增后端流水级。

| 对象与源码位置 | 现状 | 修改/保留规则 |
| --- | --- | --- |
| `idExeReg`；`design/src/main/scala/backend/BreezeBackend.scala:241,285-442`；字段 `design/src/main/scala/interface/interface.scala:358-384` | valid、指令/PC/长度/异常、控制、rs/rd、数据 | 保留；ID 通过依赖检查后进入 EX；不在 ID 建立 busy。EX req 未接收则保持，不能重复发起 |
| `idFpCtrl/Operand1..3`；`design/src/main/scala/backend/BreezeBackend.scala:198-201,444-466` | FP decode/读值侧带 | 保留，和 ID/EX 的新 enable 对齐；GPR 源检查按附录 A |
| `exeMemReg`；`design/src/main/scala/backend/BreezeBackend.scala:483,1167-1325`；字段 `design/src/main/scala/interface/interface.scala:386-432` | ALU/访存/CSR/异常/MDU 参数与 trace | 保留非 MDU 字段；MDU 在 EX 接收，MEM 只传类别、rd、valid、指令/异常等提交元数据，不等待算术结果；删除数据字段清单见 1.3 |
| `exeMemMemOp/AmoFunc/Aq/Rl`；`design/src/main/scala/backend/BreezeBackend.scala:487-490,1207-1210,1288-1292` | 原子访存侧带 | 保留；本步不改变访存/原子接口 |
| `exeFpCtrl/Operand1..3/Rm`；`design/src/main/scala/backend/BreezeBackend.scala:202-206,1327-1344` | EX/MEM FP 侧带与动态 rm | 保留，FPU 仍阻塞，内部不修改 |
| `memWbReg`；`design/src/main/scala/backend/BreezeBackend.scala:61,1378-1569`；字段 `design/src/main/scala/interface/interface.scala:434-470` | WB 元数据、异常、各类结果/CSR/trace | MDU 到 WB 即提交，不等结果；普通整数写遇后台写让拍则完整保持，不退休/不执行副作用；其他类别见第 5 节 |
| `memWbFpWrite/Data/FlagsValid/Flags`；`design/src/main/scala/backend/BreezeBackend.scala:156-169,1571-1608` | 共同 WB 的 FP 写/flags 侧带 | 保留，WB 让拍同样保持；FPR 写单独存在时不因后台 GPR 写停顿 |
| `memWaitingRespReg`；`design/src/main/scala/backend/BreezeBackend.scala:491,727-728,1346-1352` | 阻塞访存请求/响应状态 | 保留，加一项响应捕获，见 5.2 |
| `mulWaitingRespReg/divWaitingRespReg`；`design/src/main/scala/backend/BreezeBackend.scala:492-493,1354-1368` | 全单元等待，redirect 全清 | 删除旧等待与全 flush 逻辑；诊断用 MUL busy/DIV occupied 或记分板信息替代，不能作为 interrupt 的后台排空条件 |
| `fpWaitingRespReg`；`design/src/main/scala/backend/BreezeBackend.scala:494,753-771,1370-1376` | FPU 请求接收/等待 | 保留，用已有 outReady 在 MEM 不可推进时反压 |
| `architecturalNextPc/wfiSleepingReg`；`design/src/main/scala/backend/BreezeBackend.scala:531-533,561-601,1144-1154` | 退休/redirect PC 和 WFI 状态 | 保留，PC/CSR/retire 消费真实提交；WFI 不停后台时钟 |
| `fenceiFlushIssuedReg/memBtbUpdate`；`design/src/main/scala/backend/BreezeBackend.scala:261,606-677,1156-1165` | 单次 FENCE.I flush、单次 BTB 更新 | 保留，WB 让拍不得重复消费/产生；重定向级核查见 6.3 |
| `wbData/regFile.rd_en`；`design/src/main/scala/backend/BreezeBackend.scala:134-155` | ALU/MEM/CSR/MUL 共用普通 WB mux | MDU 后台结果独立仲裁，普通 WB 不使用 MDU 最终值；FP→GPR 共用 selector 改独立 FP 结果名并保留功能 |
| 整数 RF 写穿透；`design/src/main/scala/core/RegFile.scala:27-43` | 单写口、两个异步读口，同拍写读显式 forwarding | 被仲裁的真实写口作为 ID 数据来源，不增加 EX 的 MDU 结果旁路 |

### 1.2 hold、hazard、旁路映射

| 信号与位置 | 改造要求 |
| --- | --- |
| `pipelineHold`；`design/src/main/scala/backend/BreezeBackend.scala:1063-1068` | 去掉 MUL/DIV 请求/等待项，memory/FP/FENCE.I 项保留；WB 整体让拍和 EX 单元资源等待是独立 enable 条件，见 5.1 |
| `decodeReady/decodeFire`；`design/src/main/scala/backend/BreezeBackend.scala:1610-1615` | 加 EX/MEM/WB MDU RAW/WAW、有效记分板 RAW/WAW、CSR 等空；ESTOP 的等空条件在 WB 退休前检查；保留 redirect、FP、CSR、中断/WFI 控制 |
| `decodeUsesRs1/2`；`design/src/main/scala/backend/BreezeBackend.scala:225-239` | 新 MDU hazard 用附录 A 的真实整数源/目的；原 CSR 保守 hazard 的行为保留；FPR 编码不查整数记分板 |
| `loadUseHazard`；`design/src/main/scala/backend/BreezeBackend.scala:974-978` | 保留旧阻塞访存语义及计数，不实现新 L1D load-use |
| `idExePendingCsrRd/exeMemPendingCsrRd/memWbPendingCsrRd/csrUseHazard`；`design/src/main/scala/backend/BreezeBackend.scala:979-1006` | 保留 CSR rd 的 ID 源冒险 |
| `idExePendingCsrState/exeMemPendingCsrState/csrStateHazard`；`design/src/main/scala/backend/BreezeBackend.scala:1007-1014` | 保留 CSR 状态/别名/隐式使用者排空 |
| `csrRegHazard/csrHold`；`design/src/main/scala/backend/BreezeBackend.scala:1023-1038` | 保留 CSR 对普通 GPR 产生者的保守检查，加记分板清空；只停年轻 ID，不停老 MDU WB/写回 |
| `fpSourceMatches/fpRegHazard`；`design/src/main/scala/backend/BreezeBackend.scala:1040-1047` | 保留 FPR 三源 hazard，f0 可写；不加 FPR scoreboard |
| `idCsrAffectsFp/exeCsrAffectsFp/memWbCsrAffectsFp/fpCsrHazard`；`design/src/main/scala/backend/BreezeBackend.scala:1048-1061` | 保留 FP 状态 hazard/动态舍入语义 |
| `exeRs1Data/exeRs2Data`；`design/src/main/scala/backend/BreezeBackend.scala:842-885` | 普通 WB→MEM ALU→阻塞 completion 的旁路顺序保留；completion 剔除 MDU，EX 无后台 MDU bypass；held EX 更新 `design/src/main/scala/backend/BreezeBackend.scala:436-442` 不变 |
| `completionValid/Rd/Data`；`design/src/main/scala/backend/BreezeBackend.scala:825-840` | 只留阻塞访存/FP completion；MDU 改从自身 rd/data 写回。旧 completion 独热改 grant 独热+保持（已获 R/Q15 授权） |
| `mulReqIssued/mulRspFire/divReqIssued/divRspFire/divFastCompletion`；`design/src/main/scala/backend/BreezeBackend.scala:733-752` | 删除旧 MEM 发起/完成进入普通 WB 路径；改 EX req.fire、WB commit、结果仲裁 |
| `mulUnit.flush/divUnit.flush`；`design/src/main/scala/backend/BreezeBackend.scala:736,745` | 删除任意 frontendRedirect 全 flush，改 WB killUncommitted；EX/MEM redirect 的处理见 6.3 与 A02 |
| `pipelineEmpty`；`design/src/main/scala/backend/BreezeBackend.scala:596-599` | 删除 MUL/DIV 等待项，保留未提交流水/memory/FP 条件；FASE empty 另加记分板为空 |

### 1.3 MDU 现状、批准的删除清单与文件范围

现有 MUL wrapper 无 ready/rd/commit 字段，valid/op 三拍、完整积选择 64 位结果：`design/src/main/scala/multiplier/RiscvMulUnit.scala:13-63`。旧 `SignedMul65x65` 为 65×65→130 位、三个寄存边界：`design/src/main/scala/multiplier/SignedMul65x65.scala:34-49,268-271,307-319`，**原样保留及其测试**作为等价性参照。

DIV wrapper 的 magnitude/sign/word/remainder 与符号恢复见 `design/src/main/scala/divider/RiscvDivUnit.scala:7-49`；radix-4 寄存器与两位商迭代见 `design/src/main/scala/divider/UnsignedRadix4Divider.scala:25-30,38-60,62-100`。现有 fast 检测已经在 EX（`design/src/main/scala/backend/BreezeBackend.scala:905-955`）；保留检测，移动 fastData 的去向至 EX req，不称其“原先缺失”。

批准的计划删除（本阶段尚未删除）：

- EX/MEM 的 `mul_a/mul_b/mul_op`、`div_fast/div_fast_result/div_dividend_mag/div_divisor_mag/div_quotient_neg/div_remainder_neg/div_is_remainder/div_is_word`，原定义 `design/src/main/scala/interface/interface.scala:408-420`；参数于 EX 接收，不再携到 MEM。`mul_valid/div_valid` 保留为 MEM/WB 类别元数据，`rd_addr/valid` 保留。
- `mulWaitingRespReg/divWaitingRespReg` 与旧 MEM 发起/返回 wires、全 flush，位置见 1.1/1.2。EX 的 `mulOperandA/B`、DIV 预处理/fast wires保留。
- MEM/WB `mul_data` 的 MDU 用途移除（原字段 `design/src/main/scala/interface/interface.scala:464`）；当前 FP→GPR 使用 `SEL_WB.MUL`，见 `design/src/main/scala/backend/BreezeBackend.scala:1297-1300,1526-1530`，必须改独立 FP 结果字段/selector，不能删除功能。

R/Q09 增加允许修改 `design/src/main/scala/interface/interface.scala`、`design/src/main/scala/core/common.scala`、`design/src/main/scala/core/RegFile.scala`、`design/src/main/scala/core/BreezePerformanceCounters.scala`，其余仍按 T 3.1。本轮不修改代码；tandem 全通路超出这些范围的问题列 A05。

## 2. 记分板与单元状态

### 2.1 寄存器与生命周期

| 状态 | 位宽/归属 | 更新 |
| --- | --- | --- |
| `busy[1..31]` | 31 bit，r 对应 bit r−1，x0 无项 | WB 提交非零 rd 的 MUL/DIV 置位；实际 resultWrite 清除；复位全清，killUncommitted 不修改 |
| `busySource[1..31]` | 每项 1 bit，0=MUL、1=DIV，仅 busy=1 有效 | 与 busy 置位同拍记录来源，用于计数；clear 后不参与判断（R/Q13） |
| EX/MEM/WB 元数据 | 每级 valid、MUL/DIV 类别、rd 5、原指令/异常信息 | 级间 RAW/WAW 比较，未提交依赖不由 busy 表示 |
| MUL P1..P4 | 每级 valid 1、committed 1、rd 5、op 3、product 130 | 接收/推进、最老未提交项 commit、全部未提交项 kill；没有事务标签、额外 FIFO |
| DIV wrapper | occupied/committed/done 各1、rd 5、result 64，以及现有 sign/word/remainder 控制 | req 接收后 occupied，commit 授权，done 保存结果，写回或未提交 kill 释放 |
| 访存 response capture | valid 1、data 64、error/pageFault/isWriteAck 各1 | 在 MEM 无法消费的响应拍捕获，MEM 推进消费；只一项 |

现有 dmem 响应字段定义见 `design/src/main/scala/interface/interface.scala:104-110`，完成/异常使用见 `design/src/main/scala/backend/BreezeBackend.scala:728,1507-1519`；捕获全部参与该完成/异常判断的字段，不只保存 data。FPU 已有稳定输出和 outReady，见 5.2，不额外加 FPU capture。

单元未提交项按发射顺序排列，至多对应 EX/MEM/WB 的 2–3 条。MUL 从 P4 向 P1 查最老的 valid && !committed 项；DIV 只有 occupied 项。`commit` 不带 rd/身份，授权该单元最老未提交项；`killUncommitted` 清所有未提交 live，已提交项保留。结果通道带 rd 5/data 64，valid 只在该结果已提交且算完时有效。

### 2.2 置位、清除与同拍方程

```text
setMask   = wbCommit && isMdu && rd!=0 ? oneHot31(rd) : 0
clearMask = resultWrite ? oneHot31(result.rd) : 0
busyNext  = (busy & ~clearMask) | setMask
assert((setMask & clearMask)==0)
```

复位优先：全部项无效、busy=0；非复位时，kill 对 busy 没有写使能。WB 的 MUL/DIV 必须已经在 EX 真正 req.fire，且自身没有取指/非法/其他 WB 异常，才产生对应 `commit` 和 setMask。rd=x0 不发单元，也不 commit/setMask（R/Q11）。

同一 rd 不会同拍 set/clear：未提交项和已提交项都受 ID WAW 保护，不能同时拥有同 rd。不同 rd 的 set/clear 同拍全部生效。**同拍允许 ID 依赖者离开**；这不是同 rd 的 WB 再提交，本拍离开 ID 的新指令以后才到 WB。

`busy[r]` 当且仅当有一个 **已提交、尚未实际写回、rd=r** 的 MDU 项。未提交项不计入此等价关系；提交周期末转入已提交/busy，写回周期末同时释放/清位。

## 3. ID 依赖检查与旁路

### 3.1 精确条件

附录 A 给出所有合法指令的整数源/目的。每项非零地址按以下两组检查：

```text
effectiveBusy = busy & ~clearMask
sbRaw = usesGprRs1 && rs1!=0 && effectiveBusy[rs1]
     || usesGprRs2 && rs2!=0 && effectiveBusy[rs2]
sbWaw = writesGpr && rd!=0 && effectiveBusy[rd]
pipeRaw/Waw = 与 EX、MEM、WB 中 valid、非零 rd 的未提交 MUL/DIV 作同样比较
idMduHazard = sbRaw || sbWaw || pipeRaw || pipeWaw
```

三个级包含本拍即将 WB commit 的 MDU：ID 在此拍仍因级间比较停顿，周期末 busy 置位，下一拍由 busy 接续；不能因“本拍要提交”提前删 WB 比较。带取指/非法异常、不发 FU 的条目没有 MDU 生产者资格；rd=x0 也不作为生产者。

ID 被级间/记分板挡住不能离开，但 EX/MEM/WB 与后台结果继续前进；不能把 hazard 变成全流水 hold。CSR 在 ID 等空；ESTOP 到 WB 后若 busy 非空，保持 WB 不退休，并阻止年轻项覆盖，后台写回继续，busy 空后才退休。两者都不能阻止老 MDU 授权与写回。旧 CSR/FP/访存 hazard 另行保留。

### 3.2 写回同拍读

已提交结果实际 grant/write 的 N 拍，clearMask 屏蔽其 busy，RF 同拍写穿透给 ID 的真实源，依赖者在 N 拍可以 decodeFire。其他未选来源的 busy 不屏蔽；若还命中级间项或其他 hold，则继续等待。

普通 EX/MEM/WB 旁路保留；不向 EX 添加后台 MDU 旁路。所有 MDU 依赖者在离开 ID 前已得到最终值。held EX 操作数更新沿用 `design/src/main/scala/backend/BreezeBackend.scala:436-442`，原普通/阻塞完成旁路见 `design/src/main/scala/backend/BreezeBackend.scala:842-885`，其中 MDU completion 的接入删除。

## 4. 统一长延迟接口与脉冲时序

### 4.1 本步信号（相对单元）

| 通道 | 方向/位宽 | 字段与条件 |
| --- | --- | --- |
| `req.valid/ready` | In/Out，各1 | EX 发起；MUL a/b 各 SInt65、op UInt3、rd UInt5；DIV magnitude 各64、quotientNeg/remainderNeg/isRemainder/isWord 各1、fastValid 1、fastData 64、rd5 |
| `commit` | In，1，单拍脉冲，无 ready | WB 本单元一条真实 MDU 提交；同拍必须接受，命中最老未提交项 |
| `killUncommitted` | In，1，单拍脉冲，无 ready | WB 发起取消时向 MUL/DIV 都发；只作废全部未提交项；各类 redirect 见 6.3 |
| `result.valid/ready` | Out/In，各1 | 已提交且 done 的结果 valid；ready 由 DIV>MUL 仲裁产生 |
| `result.rd/data` | Out，5/64 | 无事务标签；valid&&!ready 时全部字段保持 |

EX req 仅对 valid、合法 MUL/DIV、rd≠0、无取指异常/非法标记、未被更老 redirect 同拍取消的指令有效，且要求后端允许 EX 推进：WB port stall 或原 memory/FP/FENCE.I 阻塞时禁止 req.fire。单元 req.ready=0 本身不撤销 req.valid，EX 保持并等待；真正被接收才推进到 MEM，不能在保持同一 EX 项时重复接收。更老 WB kill 或 MEM 重定向抑制同拍 EX 发射；EX 分支/JALR 是同一个单发射 EX 槽，不能同时作为 MDU 发射，年轻 ID 被冲刷。

### 4.2 周期末更新次序

1. 复位：清所有 valid/occupied/committed/done、记分板与 response capture。
2. 非复位：先对 `commit` 标记最老未提交 live 为 committed；随后 `killUncommitted` 作废仍未 committed 的项。即 commit+kill 同拍，刚提交项保留。
3. 接收/流水推进遵守 enable 和老重定向抑制；提交/kill 在 MUL 停住时也必须生效，不随数据 enable 被屏蔽。
4. 未提交结果算完先内部保存，`result.valid=0`；只在 committed 后提供输出。已提交结果获 ready 时精确一次物理写并释放。

当前中断只在未提交流水空时接受，故 WB MDU commit 与 interruptRedirect 同拍在现有接受条件下不可达（`design/src/main/scala/backend/BreezeBackend.scala:596-599`），与 R/Q03 的整核示例冲突，列 A06；commit-before-kill 仍作为统一单元接口合同测试，不擅自放宽中断规则。

未来 FPU 可复用 req/commit/kill/结果保持，增加目的 bank、EX rm 3、flagsValid/fflags 5；未来 L1D 的迟到 data/error 使用同一写口保持合同，其独立 MSHR 标识与 S2 判定按 D-cache 2.9，**不将 MDU 的无事务标签决定扩成删除 L1D 标识**。本步不连接这两类后台来源。

## 5. 写口仲裁、级间 enable 与响应保存

固定 **DIV > MUL > 普通 WB**，以后按 D 6 扩展 L1D > DIV > MUL > FPU；没有轮转。每拍至多一个后台写，未选来源 ready=0 并保持结果。

```text
wbNeedsGpr = wbValid && !wbTrap && ordinaryGprWrite && wb_en && rd!=0
wbPortStall = wbNeedsGpr && longResultActuallyGranted
```

ordinaryGprWrite 包括 ALU/Load/CSR/FP→GPR，不包括 MDU 自身 WB commit。后台写清 busy、发写回事件，不再 retire。Store/branch/rd=x0/FPR 写/MDU commit/trap/xRET/WFI 无普通整数写口需求，可与后台结果同拍按原语义提交/陷入；CSR/ESTOP 仍须额外满足等空条件。

### 5.1 流水推进表

| 本拍条件 | ID/EX/MEM/WB 行为 |
| --- | --- |
| WB port stall | 四级都保持一拍；WB valid/数据/CSR/FP flags/trace 保持，禁止普通 retire/CSR/PC/flush/train/访存等副作用；后台 resultWrite 正常发生 |
| 非 WB stall，EX 有有效 MDU 但 req.ready=0 | EX、ID 保持；MEM 在消费原项后插入气泡；MEM/WB 继续推进；同一 EX 项没有 req.fire，不重复发起 |
| 单独 ID RAW/WAW/CSR 等空 | ID 不接受新指令，老 EX/MEM/WB 继续推进 |
| 原 memory/FP/FENCE.I hold | 保留原阻塞行为；需要的响应保持见 5.2；后台已提交结果仍参与写口 |
| 老 WB kill | 抑制同拍年轻 EX/ID/其他副作用，发 killUncommitted；记分板不由 kill 改变 |

WB port stall 优先于 EX 资源停顿：只有 MEM 确实能够消费旧项时才插气泡，不能在 WB 停住时覆盖 MEM。EX/MEM/WB 控制侧带与共同寄存器采用同一个对应级 enable。

### 5.2 阻塞响应

dmem 响应为脉冲、后端在 `memWaitingRespReg && io.dmem.rsp.valid` 消费，见 `design/src/main/scala/backend/BreezeBackend.scala:727-728,1507-1519`。新加 **一项**捕获寄存器：MEM 不能推进时响应到达，锁存 valid/data/error/pageFault/isWriteAck；保持访存元数据，MEM 恢复推进时消费一次并清 valid。MEM 可以推进且未捕获时走原直接完成；不能同一响应既直接消费又置 capture。单 outstanding 原约束保留（`design/src/main/scala/backend/BreezeBackend.scala:1635-1636`），capture 有效时不再发下一访存，禁止溢出。

FPU **有 outReady，选择反压，不新加捕获寄存器**：接口与内部一项稳定响应寄存器见 `design/src/main/scala/fpu/BreezeFp.scala:343-401`；现有 backend `outReady := fpWaitingRespReg` 见 `design/src/main/scala/backend/BreezeBackend.scala:756-762`。改为仅在本项等待且 MEM 可以实际消费完成时 ready=1；WB port stall 时 ready=0，result/status 保持，恢复时消费一次。FPU 内部不修改。

### 5.3 进展

DIV 至多一项，释放和再接收不能同拍；同一 DIV 结果只能获 grant 一次。MUL 被 DIV 压住时整条单元停、不接收新 MUL；DIV 写完的下一拍没有该 DIV 的 valid，MUL 可获写口。外部无永久停顿是活性环境假设，DUT 内部优先级/ready 属于证明目标，不能 assume 每个 grant 必然发生。

## 6. 异常、CSR、重定向、中断与 WFI

异常取消年轻未提交单元项，busy 不动；已提交后台项继续算/保持/写，trap handler 依赖仍由 busy/级间比较停住。CSR v1 等全部记分板清空，本步只有整数 MDU scoreboard；同时保留旧 CSR 状态/寄存器 hazard。ESTOP 在 WB 退休前等 busy=0，阶段一只写此条件，不执行仿真结束逻辑。

FENCE 仍是旧阻塞访存流水语义；本步不接新 L1D。FENCE.I/SFENCE/satp/xRET 的原 redirect/操作数/特权规则保留；不得任意 frontendRedirect 全清 MDU。WFI 睡眠不停时钟，已提交结果继续写；中断停止正常 ID 发射，按原未提交流水空条件接受，不等后台 MDU；FASE empty 见第 9 节。

### 6.1 正常时序例（修订后）

PC `0x100: div x5,x1,x2`，`0x104: add x6,x3,x4`，`0x108: add x7,x5,x0`；无外部/写口停顿，DIV ready。

| 拍 | DIV / 独立 ADD | 依赖 ADD | x5 busy |
| --- | --- | --- | --- |
| N | DIV 离开 ID | 尚未到 ID | 0 |
| N+1 | DIV EX req.fire；独立 ADD 离开 ID | — | 0 |
| N+2 | DIV MEM；独立 ADD EX | ID 命中 MEM 的未提交 DIV rd，停 | 0 |
| N+3 | DIV WB commit；独立 ADD MEM | ID 命中 WB DIV，仍停 | 本拍0，周期末置1 |
| N+4 | DIV 后台；独立 ADD WB 可提交 | ID 命中记分板，停 | 1 |
| R（结果获 grant） | DIV resultWrite x5 | effectiveBusy 屏蔽 x5，经 RF 写穿透读值，同拍离开 ID | 本拍1，周期末清0 |
| R+1 | 已无该 DIV 项 | 依赖 ADD EX，已携最终值，不用 MDU bypass | 0 |

R 若与普通整数 WB 写重合，该普通指令让拍；此时全流水保持，依赖者虽不再被 x5 busy 阻挡，也不能 decodeFire，须等 WB hold 解除。这不改变“仅依赖停顿时写回同拍可离开”的规则。

### 6.2 异常与同拍提交/取消例

已提交 DIV x5 后台运行；年轻 load 在 N 拍 WB 报错，后面 MUL x6 在 EX/MEM。N 拍发 killUncommitted，未提交 MUL 作废，从未占 busy；x5 保持。若 x5 同拍实际写回，busy 清除来自 resultWrite，**不是 kill**。handler 在未写前读 x5 停，写回拍可释放。

单元接口若同拍 commit 和 kill：先把最老未提交项授权，再丢其余未提交项。接口级用例覆盖此组合；整核中断仍保持 empty 条件，不人为制造一个当前不可达的“同拍中断提交”。

### 6.3 每种重定向的源码级核查

这里的 EX=`idExeReg`、MEM=`exeMemReg`、WB=`memWbReg`。表中“发/不发”按 R/Q03 的**级别规则**；FENCE.I 的审阅表述冲突另列 A02，未擅自搬级或选另一种方案。

| 重定向 | 源码事实：发起级/条件/target | killUncommitted 规则 |
| --- | --- | --- |
| 条件分支 | EX：`redirectDirectionMismatch/TargetMismatch` 用 idExeReg && !pipelineHold；`design/src/main/scala/backend/BreezeBackend.scala:536-554,1667`；decoder `design/src/main/scala/core/InstDecode.scala:310-322` | 不发；年轻 ID 取消；不取消老 MEM/WB 项 |
| JALR（兼列 JAL） | EX：同一 redirectNeeded/JAU/EX nextPc；`design/src/main/scala/backend/BreezeBackend.scala:477-481,536-554,1667`；decoder `design/src/main/scala/core/InstDecode.scala:282-309` | 不发；同槽不能另发 MDU，抑制年轻接受 |
| 同步异常 | WB：memWbReg.valid && wbTrap；`design/src/main/scala/backend/BreezeBackend.scala:555-560,584,1080-1126,1660` | 发给两单元；未提交项清 live，busy 不由 kill 改 |
| MRET/SRET（xRET） | WB：memWbReg.valid && is_mret/is_sret && !wbTrap；`design/src/main/scala/backend/BreezeBackend.scala:581-590,1662` | 发；抑制年轻 EX 发射 |
| FENCE.I | **MEM**：exeMemReg.fencei、已发 flush 且 dcacheFlushDone；`design/src/main/scala/backend/BreezeBackend.scala:1156-1165,1431-1434,1665`；decoder 注明 MEM `design/src/main/scala/core/InstDecode.scala:434-453` | 按 MEM 原则不发、抑制同拍 EX；但 R/Q03 把 FENCE.I 列作 WB kill，**A02 待确认** |
| SFENCE.VMA | **EX**：idExeReg.ctrl.is_sfence_vma && !pipelineHold 且合法、!wbKillsYounger/!fenceiFlush；`design/src/main/scala/backend/BreezeBackend.scala:591-593,1643-1647,1666` | 不发，不能杀老 MEM/WB 的未提交项；抑制年轻接受 |
| satp 写 | WB：memWbReg.csr_write_en && csr_addr==satp && !wbTrap；`design/src/main/scala/backend/BreezeBackend.scala:594-595,1663` | 发；target=WB nextPc |
| 中断 | 退休边界/空未提交流水：interruptPending && pipelineEmpty && !faseActive；`design/src/main/scala/backend/BreezeBackend.scala:596-601,1120-1124,1661` | 发（正常情况下没有未提交项）；busy 不需空 |
| WFI | WB：memWbReg.valid && is_wfi && !wbTrap；`design/src/main/scala/backend/BreezeBackend.scala:561-579,590,1664` | 发；保持后台已提交项，睡眠不停时钟 |

WB kill 组合为 exceptionRedirect/xretRedirect/satpCommit/interruptRedirect/wfiCommit；FENCE.I 不在已确认组合里，单列 A02，不能把未冻结的歧义隐藏成确定公式。若 WB 老重定向与 EX/MEM 重定向同拍，target 保留原年长者优先，源证据 `design/src/main/scala/backend/BreezeBackend.scala:1656-1678`；WB 让拍禁止普通副作用，trap 等无写口类别按第 5 节处理。

## 7. MUL 单元

65×65 有符号乘法、完整 product 130，后接四个寄存边界；无反压 4 拍、II=1。P1/P2/P3/P4 各保存 product/op/rd/valid/committed，P4 就是输出寄存器，不设额外 FIFO。按 R/Q04：

```text
outValid = P4.valid && P4.committed
mulEnable = !(outValid && !outReady)
所有四级数据 enable = mulEnable
req.ready = mulEnable   // reset/取消拍禁止实际接收年轻请求
```

停住期间 commit 与 kill 仍更新对应元数据；commit 最老未提交项，kill 清剩余未提交 valid，已提交项保持。**P4 已完成但未提交时，上式不能保证保留它，见 A01；本稿保留审阅公式并指出冲突，不自行改 enable、加 buffer 或提前写回。**

符号扩展沿用 `design/src/main/scala/backend/BreezeBackend.scala:887-903`：MUL/MULH 双有符号，MULHSU b 补零，MULHU 双补零，MULW 低32位符号扩展至65。result 选择低64、高127:64、W低32符号扩展，源为 `design/src/main/scala/multiplier/RiscvMulUnit.scala:46-58`；op 3位定义 `design/src/main/scala/core/common.scala:105-113`。

R/Q16 确定 XCKU040 使用 DSP48E2，只用推断、不例化 primitive，可启用 Vivado retiming。AMD 的 [UG579](https://docs.amd.com/v/u/en-US/ug579-ultrascale-dsp) 和 [UltraScale DSP 架构说明](https://docs.amd.com/r/en-US/conversion-methodology/DSP-Slice-Architecture) 支持这一 DSP 类型/推断方向；本轮没有运行推断/综合，不报告约16DSP/节省LUT估计为实测。验收为新旧 multiplier 单独 OOC DSP/LUT 对比，以及单核整机100MHz WNS/最差路径。

## 8. DIV wrapper

保留 unsigned radix-4 算术，EX 保留 divisor=0/有符号 overflow 检测，req 附带 fastValid/fastData。N 拍 req.fire，周期末锁存 rd/符号/W/余数控制并 occupied=1；fastValid 时同时 done=1/result=fastData，N+1可见算术完成，但未 commit 时 result.valid=0。正常路径等待 unsigned 核结果后锁存 result/done，不产生单拍丢失的架构输出。

`req.ready=!occupied`；已完成但未授权/未写回也 occupied。结果写回或合法 kill 在周期末释放，下一拍才能再次接收，禁止释放与接收同拍。commit 授权本项，kill 只清未提交项；同时来先 commit 再 kill。

| 条件（W 操作先格式化有效低32位） | 商类结果 | 余数类结果 |
| --- | --- | --- |
| 有效 divisor=0 | 全1，W同样低32全1再符号扩展 | 有效 dividend，W低32再符号扩展 |
| 有符号 min / −1（64或W） | min，W为0xffffffff80000000 | 0 |
| 其他 | unsigned magnitude 迭代后恢复符号，W最终符号扩展 | 同左 |

依据 `design/src/main/scala/backend/BreezeBackend.scala:908-955`、wrapper `design/src/main/scala/divider/RiscvDivUnit.scala:28-48`。unsigned 核 a=0、a<b、a=b 的短路径保留（`design/src/main/scala/divider/UnsignedRadix4Divider.scala:72-89`）。根据 `design/src/main/scala/divider/UnsignedRadix4Divider.scala:38-60` 的两位迭代，最坏32是算术迭代拍数；接收初始化、等待WB、写口停顿分别计入 req→write 的实测延迟，不能以32作为含所有反压的写回上界。

## 9. 保留行为、FASE、tandem 与旧检查

| 项目/来源位置 | 冻结候选要求 |
| --- | --- |
| CORE-001；`docs/bugs/CORE-001.md`；`design/src/main/scala/backend/BreezeBackend.scala:727-728,1063-1068,1346-1352,1561-1569` | 请求当拍持有、等待上下文、一次完成/退休，WB 让拍捕获响应 |
| CORE-002；`docs/bugs/CORE-002.md`；回归 `design/src/test/scala/core/breezecoreSpec.scala:1761-1918` | 重定向目标 miss 不沿用旧事务/行，frontend/cache 不修改 |
| CORE-003；`docs/bugs/CORE-003.md:7-11`；`design/src/main/scala/backend/BreezeBackend.scala:1015-1038`；测试 `design/src/test/scala/core/breezecoreSpec.scala:2299-2667` | 实际状态以 Alan d5672f5 基线为准，当前未确认；T01 记录基线已有失败，不修该 bug、不改 handler workaround |
| CORE-004；`docs/bugs/CORE-004.md`；`design/src/main/scala/backend/BreezeBackend.scala:544-554,633-663` | EX 真推进才 branch resolve/train，保留 load-to-branch 数据正确性 |
| CSR；`design/src/main/scala/backend/BreezeBackend.scala:979-1038,1070-1079,1253-1259,1449-1450` | 状态/别名/rd/合法性对齐，保留全部旧 hazard并加等空 |
| trap/原子/reservation；`design/src/main/scala/backend/BreezeBackend.scala:704-725,815-823,1080-1126,1507-1516,1628-1641` | cause/tval/第二parcel、AMO Store类fault、单访存aq/rl、SC 0/1、trap reservationKill 保留 |
| FENCE.I/SFENCE/satp/xRET/中断/WFI | 源码和级别详见6.3；保留语义，新增Mdu取消不得越过年龄界 |
| FP；`design/src/main/scala/backend/BreezeBackend.scala:753-771,1327-1344,1571-1608` | 阻塞、rm、FPR/GPR、flags保留，不改FPU内部 |
| FASE；`design/src/main/scala/backend/BreezeBackend.scala:1679-1732` | `f.empty = 未提交流水空 && !fenceiPending && busy==0`；enter/host寄存器读写/launch 都等后台完；interrupt不使用f.empty。保留flightEvents，诊断旧等待字段按1.3删除清单更新 |
| HPM；`design/src/main/scala/core/BreezePerformanceCounters.scala:29-86`；`design/src/main/scala/core/RegFile.scala:216-227` | 8个计数器、写优先、旧selector/inhibit采样、可见pending不变；后台写不增加instret |
| ESTOP/debug；`design/src/main/scala/backend/BreezeBackend.scala:170,1734,1739-1779` | ESTOP WB退休前busy空，一次退出；debug的旧MDU WB最终值观测与Q15冲突列A03 |

### 9.1 tandem 决定与现有入口核查

R/Q18：WB 提交实际已发射且 rd≠0 的 MDU 时 trace 加 `rdPending=1`，原 rdData 无效；普通指令及不发单元的 rd=x0 MDU 为 rdPending=0。每次实际后台 GPR 写输出一个 valid/rd5/data64 写回事件；checker 顺序执行参考模型，在 MDU提交时以 rd 保存期望值，在晚写事件比较并移除，禁止重复/不存在pending的晚写。无事务标签，WAW 保证同rd只有一个 pending。commit与其他rd晚写可以同拍，两个事件都必须保留，不能互相替代。

源码核查：`design/src/main/scala/interface/interface.scala:246-262` 的 TracePayload无rdPending/独立晚写；`design/src/main/scala/sim/BreezeCoreTandem.scala:3-24` 只有事件/result容器；parser `design/src/main/scala/sim/BreezeCoreTandemParser.scala:23-58` 仅转成reg/mem effects；runner `design/src/main/scala/sim/BreezeCoreSimSupport.scala:301-323,453-497` 仅收集/打印；`tests/ref/spike_ref.hpp:1-11` 为空壳。`sim/breezecore/README.md:3-26` 组织资产、调用Scala runner，不能作为参考比对器证据。**上述仓库范围未找到可复用的完整逐条参考比对器**，按R/Q18停止checker工作、报告A04，不新建参考模型。增加晚写到core/runner通路的文件范围见A05。

### 9.2 已批准旧检查迁移

- `PopCount(completion)<=1`（`design/src/main/scala/backend/BreezeBackend.scala:839-840`）→ 写口grant独热+每源结果保持，允许DIV/MUL同时valid。
- `RiscvMulUnit` 三拍/全flush（`design/src/test/scala/multiplier/RiscvMulUnitSpec.scala:23-64`）→ 四拍/commit/kill/保持；旧 `SignedMul65x65` 及其tests原样保留。
- HPM非法selector=11（`design/src/test/scala/core/BreezeCsrPipelineSpec.scala:130-131`）→ 非法上界14；旧合法0–10语义保留。
- **其余旧测试/断言原样保留**。后端旧MDU测试观测与早提交冲突列A03，不能自行迁移；阶段二回归按R/Q17“无新增失败，通过数不少于d5672f5同机基线”，现有失败只记录不修复。

## 10. 仿真断言与形式化性质

### 10.1 仿真断言（冻结后实现）

| ID | 规则 |
| --- | --- |
| S01 | x0不busy、不发MDU；同rd跨未提交流水和已提交FU至多一项 |
| S02 | busy iff有已提交未写回项；WB commit置位、write清位、kill不改busy |
| S03 | RAW/WAW命中级间或effectiveBusy时ID不离开；真实写回mask只屏蔽被grant项 |
| S04 | commit脉冲到达时单元有未提交项且只授权最老一个；无事务标签 |
| S05 | kill项永不写，committed项不被killUncommitted取消；同拍先commit后kill |
| S06 | 后台grant独热、后台与普通GPR写互斥；可有多源valid |
| S07 | 每源valid&&!ready保持rd/data/valid；result.valid必有committed&&done |
| S08 | write、clearMask、RF穿透、晚写事件一致，一项一次写，后台write不再retire |
| S09 | WB port stall时四级及侧带保持、无普通退休/副作用 |
| S10 | 同rd setMask/clearMask不能同拍非零；不同rd同时更新全部生效；reset清所有 |
| S11 | MUL四级数据/op/rd/valid对齐，data enable按第7节；未提交P4保持缺口A01解决后检查 |
| S12 | DIV occupied期间ready=0，不能同拍释放再接收；fast接收后1拍done、commit前不valid |
| S13 | WB老redirect抑制年轻副作用；EX/MEM redirect不杀老未提交MDU；FENCE.I待A02 |
| S14 | CSR离开ID及ESTOP在WB退休前busy空；FASE empty含busy空；中断/WFI不要求busy空 |
| S15 | dmem capture一项不溢出/不重复消费；FPU outReady只在可消费拍；flags侧带不丢 |
| S16 | HPM11/12双来源可同拍计，各源含级间与记分板；13等于WB让拍 |

R第3节“同一rd不同拍置位与清除”按R/Q02的明确表达解释为 **同一rd不能同拍set与clear**，不是要求它们发生在相邻周期；不引入额外延迟。

### 10.2 模块级形式化（记分板、仲裁、MDU协议）

| ID | assert/cover | 环境与模式 |
| --- | --- | --- |
| F01 | 同rd任何时刻至多一个长延迟写在途（含未提交流水+已提交FU） | 不能assume无WAW代替DUT检查；BMC+归纳 |
| F02 | busy[r] iff已提交、未写回、rd=r的MDU项 | 以WB commit/write台账检查，不计未提交项；BMC+归纳 |
| F03 | 被kill项永不写回 | 允许kill+算完/复用rd；BMC+归纳 |
| F04 | 每拍至多一个后台write/grant，普通写互斥 | 多源同时valid合法；BMC+归纳 |
| F05 | valid&&!ready结果保持直至接收 | 外部valid只已提交，kill不能作稳定性的例外；BMC+归纳 |
| F06 | 每个已提交结果最终恰一次写 | 单元有界算完，外部无永久hold；内部DIV>MUL公平性/释放间隔由DUT证明；有界与无界活性分别报告 |
| F07 | commit前不能result.valid/架构写；commit-before-kill；已提交项存活 | 合法脉冲来自WB，单元无需ready；BMC+归纳 |
| F08 | req接收到kill/write计数守恒、四级/单DIV容量不溢出 | 不限制合法长反压；A01需解决，BMC+归纳 |
| F09 | WB stall完整保持、一条指令一次commit/retire | 同拍后台写、dmem/FP完成仍合法；BMC+归纳 |
| F10 | 级间RAW/WAW与effectiveBusy检查、写回同拍释放/穿透 | 不假设结果缺竞争；BMC+归纳 |
| F11 | assert同rd set/clear互斥；commit必有未提交项 | 含不同rd同时set/clear与commit+kill；BMC+归纳 |
| F12 | reset空、x0不入FU、睡眠后台继续，capture复位 | reset合同显式，不assume永久reset；BMC+归纳 |
| F13 | cover连续MUL、DIV/MUL同时valid、early done等commit、trap后后台写 | cover witness不是活性证明，A01缺口不能靠assume屏蔽 |
| F14 | cover同拍清busy/ID离开、commit+kill、WB多拍让口、CSR等待释放 | commit+中断整核不可达不要求假cover；接口组合单独cover |

六项最低性质F01–F06保留；不调小深度、不增排除合法输入的assume、不换低覆盖验收。BMC实际深度/引擎/归纳/cover及有界活性常数在阶段二运行前登记，本轮未运行；“最终无永久停顿”的公平假设与有界实验的外部hold最大值分开，不把bounded pass当无界证明。

## 11. HPM事件

| 编号/事件 | 精确事件条件 |
| --- | --- |
| 11 `SB_STALL_MUL` | ID有效、!idLeave、其真实源/目的因MUL来源effectiveBusy或EX/MEM/WB未提交MUL匹配被挡。其他hold并存仍计；若本拍clearMask已解除该依赖则不计 |
| 12 `SB_STALL_DIV` | 同上，来源DIV；两来源成立两个都计；fetch invalid不计；纯req.ready资源wait无RAW/WAW不计 |
| 13 `WB_PORT_CONFLICT` | 第5节wbPortStall，每让拍计一次；rd=x0/MDU提交/trap/FPR写等不计 |

CSR在ID等全busy为空时，只要ID有效、不离开并确因相应来源busy未清造成等待，该来源事件也成立（属于记分板冒险）；级间未提交MDU若通过已有CSR状态/源规则挡住，同样以对应来源的实际匹配计。每个事件一拍一个Bool，不按匹配rs数量累加。ESTOP在WB等空造成的整体保持本身不新增ID来源事件，仍只按ID自身的记分板/级间依赖条件判断。

现有ID0–10在 `design/src/main/scala/core/common.scala:337-349`；事件Bundle `design/src/main/scala/interface/interface.scala:340-351`。新合法上界13、非法14及以上映射NONE，软件全值先校验不截断（原行为 `design/src/main/scala/core/BreezePerformanceCounters.scala:49-64`）；selector宽 `log2Ceil(14)=4`，原 `log2Ceil(11)` 也4，需更新上界/表/三字段但不能误称物理位宽增大。保持counter+pending可见值、软件写优先、旧selector/inhibit同拍采样（`design/src/main/scala/core/BreezePerformanceCounters.scala:65-80`），8项数量不变。

## 12. 审阅后问题

原Q01–Q18的决定已落实，以下是核对这些决定后发现的新冲突/依赖；不自行修审阅文本，不作为已批准实现。

| ID | 冲突/缺口、证据 | 需要用户确认 |
| --- | --- | --- |
| A01 | R/Q03要求未提交算完结果留单元、valid只已提交；R/Q04只在outValid&&!outReady停四级。P4有未提交完成时outValid=0、enable=1，下一沿可被覆盖；例如WB被普通写口冲突拖住而MUL已到P4，或P4同拍才commit。无额外FIFO，现公式不能保留该结果 | 未提交P4的保持/推进/ready和同拍commit规则；不能擅自改enable或加缓存 |
| A02 | R/Q03将FENCE.I列为WB kill，但源码FENCE.I在MEM发redirect；证据6.3。其“MEM只抑EX、不kill”原则与括号例子冲突 | 保持MEM并不发kill，还是另批准改变发起级/取消方案；本稿不搬流水级 |
| A03 | R/Q15除三类许可外要求旧tests原样；旧后端MUL测试在MDU memWbValid时读最终wbData（`design/src/test/scala/backend/BreezeBackendMulSpec.scala:78-91`），DIV亦如此（`design/src/test/scala/backend/BreezeBackendDivSpec.scala:70-80`）。新WB早提交时最终值未到，不能保留该观测合同且实现早提交。若直接改现有DIV wrapper接口，旧测试只发in_valid后等out_valid、从不commit（`design/src/test/scala/divider/RiscvDivUnitSpec.scala:25-65`），将不符合新协议 | 是否批准迁移这些观测及DIV wrapper协议驱动，或明确保留旧wrapper测试的模块边界；全部算术/依赖期望仍保留；当前不改tests、不伪造WB data |
| A04 | 在9.1列出的仓库runner/parser/header范围没有可用完整逐条参考比对器。R/Q18要求未找到则停、不得自建 | 提供可复用比对器/入口或进一步指定查找范围；当前停止checker实现，不编造参考模型 |
| A05 | R/Q18需rdPending/晚写通路，已有core仅转TracePayload（`design/src/main/scala/core/BreezeCore.scala:153-155`），runner只收原RawCommitEvent（`design/src/main/scala/sim/BreezeCoreSimSupport.scala:453-485`）；R/Q09新增允许文件不含sim目录/core非HPM改造 | 是否批准具体core/trace runner/parser/log文件变更；事件物理承载不能自行扩任务范围 |
| A06 | R/Q03举“WB MDU提交同拍接受中断”为commit+kill例子，但R/Q06保留未提交流水为空接受中断；源码pipelineEmpty要求!memWbReg.valid（`design/src/main/scala/backend/BreezeBackend.scala:596-599`），此整核组合不可达 | 请确认该例子只用于单元接口，或另行修订中断接受条件；本稿保留先commit后kill合同，不自行改中断规则 |

上述问题阻止用户最终确认冻结；其余规则按R的决定，不恢复原Q列表或旧机制。

## 附录 A：decoder 推导的整数源/目的使用表

表中“读rs1/2”仅表示真实GPR源，非零才RAW；“写rd”非零才WAW。实际允许发射/退休还要合法性/取指异常条件。FPR源不查整数scoreboard。整数decoder由 `design/src/main/scala/core/InstDecode.scala:597-607` 直接接RV64IZicsrDecoder，表逐分支核对。

| 指令（逐条族） | GPR rs1 | GPR rs2 | GPR rd | 源码位置/说明 |
| --- | --- | --- | --- | --- |
| ADDI、SLTI、SLTIU、XORI、ORI、ANDI、SLLI、SRLI、SRAI | 读 | — | 写 | `design/src/main/scala/core/InstDecode.scala:88-119` |
| ADDIW、SLLIW、SRLIW、SRAIW | 读 | — | 写 | `design/src/main/scala/core/InstDecode.scala:120-147` |
| LUI、AUIPC | — | — | 写 | `design/src/main/scala/core/InstDecode.scala:148-167`，源为ZERO/PC |
| ADD、SUB、SLL、SLT、SLTU、XOR、SRL、SRA、OR、AND | 读 | 读 | 写 | `design/src/main/scala/core/InstDecode.scala:168-237` |
| ADDW、SUBW、SLLW、SRLW、SRAW | 读 | 读 | 写 | `design/src/main/scala/core/InstDecode.scala:238-281` |
| MUL、MULH、MULHSU、MULHU、MULW | 读 | 读 | 写 | `design/src/main/scala/core/InstDecode.scala:180,187,194,201,251`；rd=x0仍读源但不发FU |
| DIV、DIVU、REM、REMU、DIVW、DIVUW、REMW、REMUW | 读 | 读 | 写 | `design/src/main/scala/core/InstDecode.scala:208,219,226,233,268,272,275,278` |
| JAL | — | — | 写 | `design/src/main/scala/core/InstDecode.scala:282-294` |
| JALR | 读 | — | 写 | `design/src/main/scala/core/InstDecode.scala:295-309`，JAU rs1而ALU为PC+len |
| BEQ、BNE、BLT、BGE、BLTU、BGEU | 读 | 读 | — | `design/src/main/scala/core/InstDecode.scala:310-322`；BRU读两源 |
| LB、LBU、LH、LHU、LW、LWU、LD | 读 | — | 写 | `design/src/main/scala/core/InstDecode.scala:324-340` |
| SB、SH、SW、SD | 读 | 读 | — | `design/src/main/scala/core/InstDecode.scala:341-355`，rs2为store数据，虽ALU2是IMM仍读 |
| LR.W/D | 读 | — | 写 | `design/src/main/scala/core/InstDecode.scala:356-394`，合法编码rs2=0 |
| SC.W/D | 读 | 读 | 写 | `design/src/main/scala/core/InstDecode.scala:395-399` |
| AMOADD/AMOSWAP/AMOXOR/AMOOR/AMOAND/AMOMIN/AMOMAX/AMOMINU/AMOMAXU，W/D | 读 | 读 | 写 | `design/src/main/scala/core/InstDecode.scala:400-408` |
| FENCE、FENCE.I | — | — | — | `design/src/main/scala/core/InstDecode.scala:412-454` |
| ECALL、EBREAK、MRET、SRET、WFI、ESTOP | — | — | — | `design/src/main/scala/core/InstDecode.scala:456-514` |
| SFENCE.VMA | 读 | 读 | — | `design/src/main/scala/core/InstDecode.scala:515-523`；地址/ASID由后端 `design/src/main/scala/backend/BreezeBackend.scala:1643-1647`；x0表示全范围，不读GPR数据 |
| CSRRW、CSRRS、CSRRC | 读 | — | 写 | `design/src/main/scala/core/InstDecode.scala:525-557`；rs1=x0无RAW，rd=x0仍可写CSR |
| CSRRWI、CSRRSI、CSRRCI | — | — | 写 | `design/src/main/scala/core/InstDecode.scala:558-590`；字段19:15为zimm，虽sel_alu1=RS1但alu_op=RS2，立即数实际来源 `design/src/main/scala/core/FuncUnit.scala:127-136`，不能将zimm当新MDU RAW |
| FLW、FLD | 读 | — | —（写FPR） | `design/src/main/scala/fpu/BreezeFp.scala:113-120`，usesGpr1 |
| FSW、FSD | 读 | — | — | `design/src/main/scala/fpu/BreezeFp.scala:122-129`，地址为GPR rs1、数据为FPR rs2 |
| FMADD/FMSUB/FNMSUB/FNMADD，S/D | — | — | —（写FPR） | `design/src/main/scala/fpu/BreezeFp.scala:100-109,131-139`，三FPR源 |
| FADD/FSUB/FMUL/FDIV/FSQRT，S/D | — | — | —（写FPR） | `design/src/main/scala/fpu/BreezeFp.scala:143-149` |
| FSGNJ/FSGNJN/FSGNJX/FMIN/FMAX，S/D | — | — | —（写FPR） | `design/src/main/scala/fpu/BreezeFp.scala:150-164` |
| FCVT.S.D、FCVT.D.S | — | — | —（写FPR） | `design/src/main/scala/fpu/BreezeFp.scala:165-171`，rs2为fmt编码 |
| FLE、FLT、FEQ，S/D | — | — | 写 | `design/src/main/scala/fpu/BreezeFp.scala:172-180`，两个FPR输入，writesGpr |
| FCVT.W/WU/L/LU.S/D | — | — | 写 | `design/src/main/scala/fpu/BreezeFp.scala:181-189`，FPR输入，rs2是整数fmt编码 |
| FCVT.S/D.W/WU/L/LU | 读 | — | —（写FPR） | `design/src/main/scala/fpu/BreezeFp.scala:190-198`，usesGpr1，rs2是fmt编码 |
| FMV.X.W、FMV.X.D | — | — | 写 | `design/src/main/scala/fpu/BreezeFp.scala:199-205` |
| FCLASS.S、FCLASS.D | — | — | 写 | `design/src/main/scala/fpu/BreezeFp.scala:206-212` |
| FMV.W.X、FMV.D.X | 读 | — | —（写FPR） | `design/src/main/scala/fpu/BreezeFp.scala:214-222` |

由表产生新MDU RAW/WAW资格，不替换旧CSR/FP保守hazard，也不因非法编码中默认wb_en/源selector而发MDU。旧整数源组合会对CSR immediate产生保守RS1匹配，事实见 `design/src/main/scala/backend/BreezeBackend.scala:225-231`，此旧规则保留；新MDU真实源表与旧保守条件分开定义。
