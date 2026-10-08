# SOC-3c：S2 fanout 执行报告

状态：§0 完成；用户已将 D1–D4 裁定写入任务 §6、单轮及并行 Vivado 流程写入 §7。C1–C4 本地 RTL/规格及新增用例已落稿，待远端编译和动态验证；没有本任务的动态通过数或时序结果。以下 §1 保留裁定前问题记录，其建议以任务 §6 为准，尤其 D3 不采用消费拍年龄确认/取消。

## 0. SOC-3b 提交

- 实现提交：`7116e32e98de19db2d0b7ea3676cecb3f76b2534`。
- 分支：`feat/pcie-fase-20260920`；cwd：`/home/chen/leisure/flow`。
- 提交范围：SOC-3b RTL、测试（含 ClusterWbSplitSmokeSpec）、同步规格、tools/frozen.json、SOC-3b 任务/报告和 records/soc3b-*。其余已有工作区改动保留且未带入。
- 提交 SHA 已回填 SOC-3b 报告 §9 末尾；回填文本目前为该报告的工作区改动。
- `21e3b18` 为既有验证快照，不将其测试或 OOC 结果表述为本次提交的重新验证。

## 1. 集中裁定的问题记录（已由任务 §6 回应）

任务 §2 要求“冲突时停止该项并报告”，§4 只明确允许 S13 与 HPM 差 1 的修改。下列均来自当前源码/规格的静态核对，尚无新增动态失败。

### D1：C2(a) 与 S09

`BreezeBackend.scala:639–642` 的 S09 在 WB 保持时禁止 frontendRedirect.valid；backend-rtl-spec.md 的 S09/A08 仍规定 EX 控制事件只在 !downHold 发起。C2(a) 则要求 held EX 可提前纠错一次。

建议裁定：S09 保留禁止退休、CSR、训练等原副作用，明确允许由 exRedirectSent 保证只发一次的 EX 分支纠错；授权同步对应规格和 S09 断言。其余安全检查保持。未获裁定前 C2(a) 暂停。

### D2：C1 同拍 fire/kill 与 FpUnit S05

`FpUnit.scala:53–55` 的 canAllocate 含 !killUncommitted，仍把 WB kill 送进 CVFPU in_valid/req.ready；第 115 行 S05 直接断言 req.fire 与 killUncommitted 不得同拍。C1 要求该拍允许 fire、入表即作废。C1 文本允许“对应断言”改写，但 §4 的例外只点名 S13，需要统一许可范围。

建议裁定：将该 S05 一并纳入 C1 对应修订；改为验证 fire/kill 新项入表即无效、无 RF/flags/提交副作用、killDrain 阻止 tag 过早复用。保留 committed-before-kill、speculative write 等原检查，不删除安全含义。

### D3：预测训练与 S2→前端结构门槛

`BreezeBackend.scala:395–412` 的 PHT/GHR valid 由 train/exAdvance 决定；BTB 输出 valid 还直接含 !wbKill/!downHold。这与 §1 禁止 S2 派生条件穿过前端 valid/CE 的原则冲突。C2(a) 同时要求训练按原规则只在 EX 真推进时进行，不能直接取消这些推进/年龄条件。

建议裁定：后端仍在 EX 真推进拍判定并记录训练事件；经过后端年龄确认及寄存边界后，再把训练请求送入前端，允许实际训练消费拍后移，BTB/PHT/GHR 输出不再受当拍 S2 条件组合门控。保持训练内容和次数，被 WB kill 取消的年轻训练仍丢弃；更新 S09 的测量边界，区分本拍 EX 产生训练与前端消费已寄存训练。具体缓冲和取消实现由 codex 决定并验证，不能简单延迟 valid 后假定取消语义已保持。未保持的分支纠错拍及 T01–T22/P01–P10 期望不变。若要训练实际消费也严格保持原拍，则需裁定此结构路径的例外，不能同时宣称结构切断。

### D4：C4 的配置归属、覆盖语义与测试许可

`BreezePerformanceCounters.scala:75–82` 当前以事件发生拍的旧 selector/inhibit 采样，软件写 counter 优先。仅加 rawEvents 寄存器并用下一拍的当前配置选择，会改变配置切换附近的事件归属；软件覆盖还需防止旧事件在下一拍再次累加。

建议裁定：事件按发生拍的旧 selector/inhibit 归属，CSR 可见 HPM 增量晚一拍；counter 软件写当拍优先，丢弃该 counter 覆盖边界之前及覆盖同拍的尚未计入事件，之后发生的事件照常计入；mcycle/minstret 不变。

许可建议：将“差 1”明确为“延后一拍，数值差为该拍真实事件增量”；HPM13 单拍可加 2，因此 HpmSpec 第 20–21 行按一拍延迟会由 2/4 变为 0/2，数值差为 2。授权按独立的一拍延迟模型更新 HpmSpec、BreezeCsrPipelineSpec 和实际受影响的 HPM 用例，保留完整事件计数、非法 selector、inhibit、软件覆盖、复位及随机刺激检查，不放宽比对或删除检查。授权同步 backend-rtl-spec.md 对应 HPM 语义描述并重登记已有冻结文件，不增加冻结文件。

## 2. 无需新增裁定的实现细节

两轮顺序、快慢 redirect 接口拆分、2 项 skid 的具体位置和空时直通、C3 同行比较与分配转发的实现、定向 spec 选择均在现有授权内自行决定。C2(b) 的间隙取指、FENCE.I 回填与 SFENCE 结果丢弃仍需实现后的定向验证；现有 L1I flush-seen 和 translator kill 逻辑只作为审查依据，不能据此声称改动后的动态安全性已经通过。

## 3. 动态门槛状态

冻结检查、定向回归、tiny RTL 生成、Cluster OOC、结构查询、整 SoC routed 和验收段均尚未执行。未尝试主机 SSH，无主机不可用结论。执行前按当前 simulation-host.md 重新读取并做 cloud_chen→Alan 预检；Vivado 使用 Alan，timeout 1h，超时不自行重试。证据将绑定 SOC-3c 自身实现 SHA，保留原拍数、协议、约束、策略及其余停止条件。
