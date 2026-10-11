# B3 附加保护：源端反例与修订提案

状态：**历史反例与提案**。后续任务书 §10 的 B3-S、BE2-P 及 §11 已裁定并替代下文待审核流程；当前实现和验收见 `BE2-report.md`。保留反例原始证据，下文拍号与停止流程只描述当时状态。

## 停止依据

冻结任务书 `BE2-backend-decoupling.md` §4 T18–T20 要求原始条件从采样为真到串行动作一直为真，并要求证明串行期间 drained/mmuIdle 一旦为真不回落；明确写了“证明不了就停下报告”。

现有 drained 的冻结含义仅为 MSHR 与 pending-store 均空（`docs/l1d-rtl-spec.md:86`），实现是 `L1DCache.scala:844`。它没有覆盖正在内部流水中、尚未分配 MSHR 的已接受 PTW。CPU kill 不撤销已接受 PTW（L1D spec §3）；PTW 在 S3 缺失后仍能分配 MSHR（`L1DCache.scala:616–624`）。串行指令关闭年轻 CPU 请求不能消除这种已接受项。

## 可重复的反例

源提交 `b01d51db379de493a5cbaf47f06941beffd4efed`，Alan，命令和原始输出见 `/home/chen/FUN/flow-runs/be2-b01d51d-b3-real-ptw/{command.json,result.json,run.log,junit}`。

最新 RTL 提交 `34cd93d45c2dbec51c13f8794dce0bf653929c1e` 上原样复现 3/3、exit 0：`/home/chen/FUN/flow-runs/be2-34cd93d-late-fatal-and-b3/`。报告提交只增补文档，不把审计通过标记为 B3 合同通过。

`BE2FencePtwSpec` 连接生产 Backend、L1D、Sv39Mmu 和 Sv39Ptw；通过真实 CSRW/MRET 配置 PMP、Sv39、S 模式。iTLB miss 产生物理地址 `0x80000008` 的真实 PTW 读请求，在 PTW 被 L1D S0 接受的同沿让 FENCE.I 离开 ID；其后没有 CPU 新请求。两例仅通过预热根页表缓存行改变 PTW 的命中/缺失。

| 拍 | FENCE.I / PTW 位置 | PTW 缺失例 | PTW 命中例 |
|---|---|---|---|
| D | PERM / L1D S3 | raw drained=1，尚未分配 MSHR | raw drained=1 |
| D→D+1 的沿 | 进入 WB / S3 完成 | 分配 MSHR | 直接完成 PTE 读 |
| D+1 | WB，无 CPU 请求 | raw drained=0，sampled drained=1，sampled mmuIdle=0 | raw drained=1，sampled drained=1，sampled mmuIdle=0 |
| 原实现的 WB 动作 | 直接检查 raw drained | 等待；不提交 | 当拍提交 |

审计 3/3 通过、exit 0 的含义是**反例已复现**，不是 B3 保护通过。L1D 单独审计也复现 raw drained 的 1→0。

两个 WB 场景的这两项寄存输入相同，因此只加 `RegNext(drained)` 不能区分它们。给 FENCE.I 额外加 mmuIdle 等待会同时挡住命中例，改变原有 T18 行为，不能私自采用。单核已有反例，现阶段不能宣称四核稳定性成立；probe/writeback 和 SFENCE 两阶段还没有取得稳定性验证结果。

结论范围是**现有源端接口与直接寄存方案**，不声称所有 BE2 实现都无解。提前建立串行所有者、在 ID 前排空翻译等办法也可能改变该场景的可达性，但需要逐项说明新增等待、已接受请求的处理以及 T18–T20 场景仍可达，不能仅把反例挡在 ID 外就当作完成了原始条件稳定性证明。尤其 T20 仍要求 SFENCE 到 WB 后等待真实 MMU 忙碌结束。

## 建议裁定：增加源端串行排空承诺

建议保留“WB 只读寄存条件”，把排空条件从瞬时统计改成有串行所有者的源端承诺。需显式修订以下条款后才能实施：

1. 保留原有 `core.drained` 的 MSHR/PS 含义；新增串行请求/就绪接口。就绪必须覆盖可能建立新等待义务的已接受项，包括 PTW 内部流水、MSHR/PS，以及实际审计确认需纳入的其他内部项。不能把原来的空位统计改名后当作承诺。
2. 请求期间阻止新的年轻 CPU/翻译发起，允许完成已有排空义务所需的 PTW、refill、probe、writeback 前进。源端只有确认相应等待义务已清除，并能保证到动作沿不会重新出现时，才返回寄存的就绪；就绪保持到对应动作/释放。不得用禁止一致性完成的方法制造稳定性。
3. T18–T20 的 D 改为上述**串行就绪承诺**首次成立的拍，不再以瞬时 `!MSHR && !PS` 的首次高电平计拍。WB 已有有效承诺时保持 T18 的当拍完成；等待承诺时动作在承诺采样后一拍。允许源端排空实际已接受项所需的等待，不能承诺所有情况只比原实现多一拍。
4. SFENCE 发出前和发出后的等待是不同阶段；第二阶段重新请求/采样 MMU idle 就绪，不能复用发出前的 idle。稳定性断言只对本阶段有效的就绪承诺生效，原始状态、承诺和动作分别观察。

这会触及 L1D 栅栏接口（当前 §11 规定“L1D 无额外动作”）和任务书 T18–T20 的计拍前提，因此本报告不自行修改冻结任务书、时序合同或哈希，也不提前实现该协议。

## 修订后必须覆盖的检查

- 上述真实 PTW hit/miss 两例：缺失时不得消费过期承诺；命中和稳定空闲路径逐拍检查新合同。
- 采样沿/动作沿交界的 PTW 接收、内部 miss 分配、refill、PS 清除，以及四核 probe/writeback；检查承诺保持、无重复响应与协议前进。
- SFENCE 两阶段、年轻翻译请求禁止、kill/异常退出后的承诺释放。
- 断言：有效承诺期间对应原始排空义务不重新出现；动作必须具有本阶段有效承诺。保留原有 busy、提交和写回断言。
- 同配置全量功能回归与 S2 结构取证；只有前置门槛满足后才运行后续 Vivado。当前结果不覆盖这些检查。
