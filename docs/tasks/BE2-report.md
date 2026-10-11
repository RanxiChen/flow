# BE2 后端解耦实施记录

## 状态与源版本

2026-10-11。用户授权实现 BE2，特别要求流水线逻辑划分明确；四核资源裁剪、实际 RVV 接入和 Llama 对比不进入本次实现。

- 起点：`2051dd0bb9a0c701bb95fd3f1e755ae175023093`。
- 分支：`refactor/be2-backend-decoupling`。
- 独立工作区：`/home/chen/leisure/flow-be2`。
- 第 0 步任务书提交：`238d85c`。未修改主工作区的未跟踪 `records/`。
- CVFPU 起点：`23153ba10a0ab8c6ee774b5e1a928007c3b2adcc`。
- 用户集中裁定提交：`027bda2`。B1/B2/C1/C2 采纳，B3 追加串行条件稳定性断言及证明要求。
- B3 审计测试提交：`1a4deba`、`510fab7`，尚待实际仿真结果。
- R4 首批 RTL 和定向测试已编辑，尚未编译验证。以下设计映射不代表整个 BE2 已实现或已验收。

## 流水职责与所有权合同

| 所在位置 | 拥有的事项 | 推进 / 释放事件 | 不可撤销边界 |
|---|---|---|---|
| ID | 译码、依赖判断、容量预留、solo 发射资格 | `idLeave` 预留目标单元容量；被杀的预留须回收 | 无 |
| EX | 标量计算、分支解析、地址和单元请求 | 单元请求接受与该 EX 指令推进统一定义；L1D S0 拒绝可停在 EX | 无 |
| MEM | 接收 EX 的指令元信息、普通结果和请求归属 | 每拍推进；kill 只清有效性 | 无 |
| PERM | 指令元信息传递、CSR 求值；与 L1D S2 对齐 | 每拍推进；kill 只清有效性 | 无 |
| WB | 按序做 Commit / Exception / Replay 判定 | 普通项离开；只有 solo 项可自保持，年轻 EX/MEM/PERM 必须为空 | WB 提交 |
| W2 | 已提交普通结果 | 无条件消费一次；不受新 kill/hold 撤销 | 已提交 |
| 长延迟单元 | 请求身份、未提交 / 已提交状态、结果存储 | `commitD` 授权；`killD` 仅作废未提交项；实际 grant 完成结果 | `commitD` 对应已发生的 WB 提交 |
| lateReg | 已提交 L1D 迟到结果 | 寄存容量决定接收；grant 消费并清 busy | 已提交 |

必须分别统计请求接受、WB 提交与物理完成。capacity 还须覆盖 ID 已预留但尚未被单元接受的 EX 项；它不能因单元尚未看到该项而丢失，也不能被重复回收。

## B1：冻结 R2 与保持 WB 本地提交语义的冲突

依据：任务书 `docs/tasks/BE2-backend-decoupling.md:18` 要求不改变提交点和记分板语义；`:55` 的 R2 却规定 WB 判定同拍只允许影响后端内部级寄存器 valid 与 L1D CPU valid。第 4 节没有授权更改其他提交 / 写回拍数。

起点源码：

- `design/src/main/scala/backend/BreezeBackend.scala:168`：WB 产生 `wbCommit`。
- `:199`：`scoreboard.io.set.valid := wbCommit && wbLong && wb.writes`。
- `design/src/main/scala/backend/Scoreboard.scala:50`：WB 提交同沿置 busy；kill 不清 busy。
- `BreezeBackend.scala:179` 与 `Writeback.scala:49`：WB 提交捕获普通结果，下一拍 W2 写回。
- `BreezeBackend.scala:372`：CSR 在 WB 提交生效，`:380` 退休计数接收 WB 提交。

推导：MUL 在 E 接受，WB 在 E+3 提交。E+3 结束后 WB 指令离开流水，E+4 时记分板必须接替未提交流水生产者的冒险保护。如果严格禁止 WB 对 busy 的同拍更新而把它延后一拍，必须另加所有权桥接状态才能避免 E+4 的依赖空窗；这不是当前冻结的记分板语义。普通结果也必须在 WB 拍捕获，以保持 W2=E+4；CSR 则必须保持 WB 同沿生效。三者都不是 R2 字面允许的两个 valid 清除对象。

建议最小修订见 `BE2-ruling-proposal.md`。仅明确保留 WB 本地架构动作，跨模块长延迟控制仍全部寄存。不增加流水级、不改变既有拍数、priority、golden 或时钟约束。冻结原文尚未修改。

集中提案已由用户裁定，见 `BE2-ruling-proposal.md` 与任务书第 10 节；不再以原 B1/B2/B3/C1/C2 提案等待确认。B3 的追加稳定性要求先做源端审计，不能把源端瞬时空闲直接当成已保证的排空承诺。

## 第一步：R4 容量先行（实现中，尚未验证）

MUL 共有八个已接受项的容量，包含运算流水中的项。结果存储有八槽，DSP 运算与元信息每拍推进；原来的第四沿 CPA 结果寄存器由输出环形存储承担，避免额外增加无冲突 E+4 的延迟。运算流水不是另外三个可独立累加的容量槽。

容量不变量：`结果槽占用 + PopCount(运算流水 valid) <= 8`。无 grant 时流水中的项最终全部汇入八槽存储，因此不会因写口长时间不可得而丢失。接收只检查已寄存的占用状态；同沿提交先授权最老未提交项，kill 保留已提交前缀，删除未提交尾部。实际 grant 才删除结果。

late 使用两项无 flow/pipe 队列，队首仍在接收后下一拍提供给 Writeback，ready 只来自队列寄存计数；队列满时不能同拍借用 grant 接收新项。W2 无条件消费、写口优先级和阈值 3 保留。

测试迁移：MulProtocolSpec 原来的“第四项填满流水”改为八项填满总容量，保留 oldest commit+kill、已提交结果抗 kill、背压稳定、500 项算术参照及 1000 项原乘法器等价检查；新增八项延后一拍授权、停止 grant 后按序排空。WritebackSpec 按已授权的两项 late 容量检查 A/B 缓存、C 背压及满队列不借用同拍 grant；物理写回值、顺序和 W2 完成断言保留。

## B3：稳定性保护的源端审计（尚未取得结果）

现有 `L1DCache.io.core.drained` 只表示 MSHR 和 PS 空。已接收 PTW 可以正在内部 S1/S2/S3 中，随后才分配 MSHR；CPU 没有新请求不足以证明 raw drained 单调。新增审计使用实际 L1D，以及实际 Backend→L1D 的 FENCE.I WB 边界，检查 PTW 在采样到动作之间是否使条件过期。审计用例通过意味着复现源端反例，不能算作 BE2 的稳定性要求通过。

若确认该反例，R7 需要源端串行排空机制，覆盖已接受的内部请求，并阻止采样窗口中的新发起；不能只加 RegNext。SFENCE 自身产生的 MMU 忙碌分属两个等待阶段，第二阶段必须重新采样，不能复用发 sfence 时的 idle。

## 容量与步骤依赖：尚需实施推导

1. 自由推进 MUL 的流水槽会最终排入结果缓冲。没有 grant 延迟上界时，容量不能只按流水槽和输出槽简单相加；要保证全部已预留的有效结果都能存住。第 3 节容量公式属于可调整的实现细节，需在实现时给出确切不变量和逐拍推导。
2. FPU 入口两项请求队列、tag、killDrain 与结果队列是不同资源。总 tag credit 不能直接替代入口容量预留；具体入口保证须保持 T14 / P08，不暗加额外 FIFO。
3. 现有 MUL 的输出保持会阻塞整条流水。R3 删除 EX ready 前必须先获得 R4 的容量保证。实现提交的边界需说明这一依赖，不能在中间状态强行忽略 req.ready。
4. 保留现有 priority 和阈值 3。来源之间的公平性限制单独记录；不以本次实现授权自行更改冻结仲裁顺序。

## 执行预检

每次执行前重新读取主工作区 `docs/cross-project/simulation-host.md`。

- cloud_chen：按当前配置以 BatchMode / ConnectTimeout=8 连接 `cloud_chen@47.96.71.231:22`；exit 255，`Connection timed out`。连接不可用，不是 RTL / golden 失败。
- Alan：按当前配置经 `clawbot` 连接 `chen@localhost:2286`；成功，hostname `chen-System-Product-Name`。20 核，内存 available 52 GiB，磁盘 available 21 GiB。存在其他项目 Vivado 与仿真作业，不操作其进程或输出。
- Alan 的现有 coursier sbt 脚本指向不存在的文件；第一次审计命令退出 127，未编译或仿真。为本任务独立下载 sbt 1.9.7 launcher，SHA256 `5244e932db91ea6bd4d1ffb7f9bc7c451c277ed552b94f266a03c09200a56ed4`，没有修改全局工具脚本。
- Alan 独立工作区 `/home/chen/FUN/flow-be2-1a4deba` 实际 HEAD 已更新为 `510fab70833cef646f56fd1d0986e7bd98fe255e`；目录名不是源码版本依据。递归子模块与起点准确版本匹配。尝试重命名被 Git 拒绝（含子模块），原目录完整保留，无验证作业受影响。
- 审计 runner 选用实时 affinity 中的 CPU 0–3、nice 10、Java ActiveProcessorCount=4，低于现场 20 核的一半；实际命令和 source/submodule/runner 摘要逐项归档。
- 未运行本地硬件编译 / RTL 生成 / 仿真；没有新 Vivado 作业。

## 验收状态

G1/G2/G3/S1/S2/A1/P1/X1 均未执行。起点 MMU frozen hash 差异保持不动。通过已有起点的任何历史报告不能作为本分支的新验证结果。
