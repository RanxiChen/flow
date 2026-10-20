# Breeze 新前端流水实现规格

2026-10-10，按当前会话确定。此文件是本次前端重构的实现依据；旧 `frontend-prediction-design.md` 和旧串行翻译通路是历史实现。后端、D-cache 和共享 PTW 协议不在本次修改范围内。新前端提供独立顶层及解析/提交反馈接口，后端重构后再连接。

## 1. 正常流水

| 阶段 | 工作 | 接收/推进 |
|---|---|---|
| F0 | 同一半字对齐 PC 启动 iTLB、ICache tag/data、双 bank BTB 和三查询 GShare；计算两个半字的 PMP 页内比较 | 共同 fire；命中路径 II=1；没有逐笔 Translate/CacheReq/CacheWait 状态机 |
| F1 | TLB tag/页权限、物理 cache tag、PMP 高位比较并行；形成预测和取指窗口；历史暂算前递；预测改写下一 PC | 同步阵列结果属于上拍请求；结果有预留容量；预测重定向阻止当前年轻 F0 接收 |
| F2 | RVC 展开、跨窗口重组、CFI 预解码、RAS、历史修复和指令入队 | 所有推测状态只在接收一次时更新；最多输出两条；后端暂只见独立指令输出接口 |

PC 不强制四字节对齐。32-bit 逻辑窗口从 PC 开始；cache line/page 最后两个字节只返回一个有效半字，下一窗口 PC+2。取指物理操作按 16-bit parcel 拆分，跨页逐页翻译，异常只附着于实际消费该 parcel 的指令。跨窗口 32-bit 指令保留首半字及 PC；跳转/全局取消清掉残片。IALIGN=16。

F1 的历史是暂算版本，用于当前 F0 的哈希及随请求保存，不重复写入持久推测历史。F1 预测旁带直接由上拍查询 owner 有效位驱动，与 cache/TLB/PMP 完成判定独立；miss 或最终权限故障也可产生短暂预测，但不授权访问。当拍有 F1 时组合前递；无新 F1 时使用保存的最后一个查询窗口的暂算历史，不能使用可能落后于队尾的 F2 队头。持久 `specGHR` 在 F2 接收时按实际条件分支移入预测方向；F2 的组前历史以已接受前缀的 specGHR 为准，查询前快照不一致也触发修复。F2/解析/全局恢复同时纠正暂算历史，取消该沿年轻查询。F2 发现历史/边界错误时，保留当前有效指令前缀，杀年轻窗口并从正确后继 PC 重取。F2 反压保持 payload，副作用不重复。

F2 的控制流分类、立即数和 x1/x5 hint 直接从原始 RV32/RVC 位段计算，与完整 RVC 展开并行；不能把完整展开输出串入重定向关键路径。RV64 的压缩控制流包括 C.J、C.BEQZ/BNEZ、C.JR/JALR，保留/EBREAK 编码不得作为跳转。

## 2. iTLB / VIPT / PMP

复用现有 `Sv39Tlb` 的同步读 F0 和 F1 命中流水，新增纯组合候选 PA/一热选择旁带，不改 PTW、页权限、sfence 或公共 PA=0 的故障优先级。候选旁带不独立授权。VIPT index+line offset 必须位于 VA[11:0]。

新前端显式开启 `pipelinedFetchKill`：F2 取消不串入 F0 阵列请求的 ready/enable；该沿可采样一个年轻请求，但同时清除 cache/iTLB valid ownership，禁止返回、创建 miss 或使用 pendingFault。已有 miss 的 grant/drain/kill 语义保持。全局/解析取消仍直接阻止 F0；默认旧 TLB 客户端仍使用 kill 阻止 fire。没有延迟清除，也不增加重定向拍数。

PMP 上下界复用 CSRFile 在合法 CSR 写入同沿寄存的 `pmpRanges`。CSR 配置修改须串行取消旧在途请求，禁止旧 F0 偏移比较与新 F1 边界组合。正常访问不重新解码 TOR/NA4/NAPOT。

F0 对两个 2-byte parcel，比较 VA 页内起点/末字节与寄存边界低 12 位。F1 对各 TLB 候选的 PA 高位做分块、均衡比较，与 VPN/ASID hit 判定并行；命中后选择比较标志，再与 F0 低位结果组合。仅高位比较并行展开，不复制整套 PMP。最低编号 overlapping entry 必须完整包含该 parcel；M-mode 未锁定项的权限规则、无匹配规则与原 checker 相同。PMA 独立判断，高 PA 不得截断造成低地址别名。

两项返回容量覆盖 F1 不可反压响应和 F2 阻塞。miss 当拍至多额外接受一个年轻查询；现有 TLB 会丢掉它，新 cache 记录并按序重查。TLB miss 重查必须同时重读 cache 阵列，不能用旧 snapshot 配晚到 PA。Cache miss 使用单个 refill owner，禁止另发 miss；已发出的 refill 即使取消仍排空，不向新请求投递旧数据。分支取消保留缓存行；fence.i 单独失效缓存行。

Refill 返回接收沿确定 killed/error/同沿取消，并锁存合格的整行数据；下一沿完成 SRAM 写入，该周期不查阵列。接收沿以后到来的普通取消保留已接受的 cache fill，fence.i 仍优先清 valid。增加的是一次 miss 的安装拍，正常 F0/F1/F2 命中路径不增加拍数。

## 3. 分支预测

- BTB：两 bank（event PC bit1），每 bank 32 sets、2 ways，128 entries；同步查表，target/type，invalid-first + 每组轮转替换。条件分支仅实际 T 新分配，NT 保留已有项。32-bit CFI 的 event PC 为指令 PC+2，16-bit CFI 为指令 PC。
- GShare：10-bit history、1024 个两位计数器；A=PC[10:1]^H，B=(PC+2)[10:1]^H，C=(PC+2)[10:1]^append(H,0)。slot0 条件 NT 用 C，否则用 B；slot0 T/jump 丢弃 slot1。
- PHT 初始实现为 4 bank、每 bank 单预测读口 + 写口的通用同步存储；同索引广播，冲突优先 A、B、C，缺失候选默认 NT，不伪造实际 SRAM 返回。bank 数可参数化，但三个逻辑候选规则固定。每 entry 有初始化有效位，未训练默认 weak NT，不复位 SRAM 数据位。
- PHT 仅按有效解析的实际 T/NT、保存的预测索引训练。四项训练队列有 ready；当前计数器 RMW，训练占用目标 bank 的读口，冲突预测可用标志清零。读写同址旁路/抑制防止未定义数据；不丢已接收训练。
- BTB 解析更新优先 F2 修复。F2 可填 direct JAL 或失效虚假 CFI；修复表写冲突可丢，但不能丢 PC/历史纠正。普通 JALR 等实际目标由解析提供。
- F2 新发现条件分支复用历史匹配的 A/B/C；没有对应候选则 NT；不重新查询 PHT。PHT 训练索引仍保存实际预测上下文的索引。

## 4. 恢复与 RAS

每窗口保存查询前历史；每条件分支输出保存历史前快照及预测索引。解析正确不重复更新 specGHR；解析错误恢复 before-history 并加入当前实际方向（仅条件分支）。提交侧维护 committedGHR，全局异常/串行取消恢复它。JAL/JALR 不占 GHR。

RAS 深度 8，推测/解析两份。F2 对有效完整 call/return 做一次推测动作；解析按程序顺序做实际动作。解析错误从包含当前动作的解析 next-state 恢复推测栈。按 x1/x5 的 JAL/JALR hint 处理 push、pop、pop+push；空栈返回退回 BTB/解析。全局取消清空两份，正常预测重定向不清空。F2 对 32-bit call 压 PC+4，RVC call 压 PC+2。

目标未知的 indirect jump 可继续顺序预测第二槽。此时两个槽的 RAS hint 按程序顺序暂算，第二槽 return 可使用第一槽刚压入的地址；两项动作统一在该 F2 组 fire 时落入推测栈，stall 不重复执行。

重定向优先级：全局/解析 > F2 > F1 > 顺序。恢复优先年轻推测动作。对外解析接口为 Decoupled，fire 才接收训练/恢复；提交接口为 Valid，来源须是确实提交的条件分支。

## 5. 验证与时序

独立前端顶层连接真实 Sv39 MMU/PTW，PTW 内存和 ICache refill 在外部。后端仅由反馈驱动器和指令消费者代替。测试包括连续命中 II=1、随机反压、TLB/cache miss 与年轻查询重放、kill/同地址重取、sfence、CSR/PMP 边界及优先级、跨行/跨页 RVC、双槽预测、PHT bank 冲突/训练、GHR 修复、RAS 和错误 BTB。

100 MHz 使用 KCU105 xcku040-ffva1156-2-e 的独立前端注册输入/输出 harness，完整 synth/place/route；两项边界 FIFO 用显式寄存器存储，避免外部数据直接进入 LUTRAM 写口。harness 内置实际 BUFGCE，正常模块 clock 接口不变；OOC 数据端口指定放置逻辑附近的 INT 虚拟引脚，保留 10 ns、0.1 ns uncertainty、I/O max 2 ns/min 0.2 ns 约束，让端口数据布线和全局时钟网络进入分析。报告内部路径、wrapper 边界和 setup/hold，不能把无 I/O 约束或未路由端口的 OOC hold 当作整个系统闭合。此物理上下文只供独立前端验证，之后集成须重新检查。测试通过后运行，不使用 false path/multicycle 放松真实路径。若 F1 无法满足 10 ns，允许增加真实判定寄存级，保持命中 II=1，并同步调整规格和测试，不改为命中 FSM。

物理验证壳的外部端口以 root clock 为参考，内部使用真实 BUFG 分发时钟。为处理两者的相位差，在验证环境输入侧显式负沿采样，再交给已有正沿边界寄存器；该采样不属于前端本体，不增加 F0/F1/F2 的流水级。保留原 I/O max 2 ns/min 0.2 ns 约束，另以随机到达时间、payload 保持和多种分发延迟验证其周期对应关系。实际违例短路径通过接收寄存器的放置调整修复，逻辑值及周期不变；记录移动清单和检查点来源。

当前状态：新独立前端和真实 iTLB/VIPT/PMP 路径已实现，62/62 功能用例通过；独立 100 MHz routed timing 验收通过，WNS +0.143 ns、WHS +0.028 ns。最终短路径修复只移动接收寄存器，没有采用恒等 LUT ECO。证据见 [验收报告](tasks/frontend-pipeline-20261010-report.md)。该独立验证不代表后端集成、全 SoC 或上板通过。

## 6. 2026-10-11 新前端与 L2 接线

新增 `BreezeFrontendL2System`：每 hart 的新前端 refill 经 `L1IClient` 接入共享真实 `L2Home`，下级暴露 AXI4；指令、解析/提交和控制直接暴露给后续新后端。需求请求捕获、L2 反压、完整行/error 返回和取消排空沿用现有协议，不改变本体命中流水，不开启预取。PTW 内存口和 L1D 一致性口仍在集成边界外。本阶段按用户要求只完成 RTL 接线，不运行编译/仿真/物理验证；以上独立前端验收不覆盖该集成顶层。接口与证据范围见 [frontend-l2-integration.md](frontend-l2-integration.md)。

## 2026-10-19 MMU 独立迁移

按用户决定，frontend 不再拥有 MMU；生产每核集成层实例化原参数的 `Sv39Mmu`，通过完整 iTLB 接口连接 frontend 的 VIPT ICache。CSR context 仍供 frontend 的 PMP/权限逻辑使用；sfence、dTLB、PTW 和 MMU idle 归集成层。F0/F1/F2、候选 PA/独热选择/kill、restart/sfence 的既有寄存边界不变。独立测试通过 `BreezeFrontendMmuHarness` 提供真实 MMU/PTW。frontend `idle` 为本地排空，包装器显式合并 MMU idle。本次不实现预取。验证证据见 [实施记录](tasks/MMU-extraction-20261019.md)。
