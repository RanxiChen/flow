# Breeze 核心重构清单与指令预取方案

记录日期：2026-10-19。源码基线：`f7422d30ad80e09c1c59b45d46a728f7baa66053`。

本文区分用户已确定的重构事项与待评审的实现建议。本文的设计建议不是 RTL 已完成或性能已有提升的证据；实施与验证状态单独记录。

## 1. 本次重构事项

| ID | 用户已确定的事项 | 当前状态 | 交付目标 |
|---|---|---|---|
| R1 | MMU 从 frontend 中独立出来，作为每核模块 | 已完成独立迁移及本轮仿真验收，保持内部行为与流水 | frontend 使用 iTLB；标量/向量数据侧使用 dTLB；PTW 经 L1D；后端提供 CSR 上下文并协调 fence/恢复 |
| R2 | 本次重构加入前端指令预取 | 用户决定延后到下一次，本次不实现 | 候选生成、请求、返回保存、需求消费、取消与失效完整闭环，可开关并量化收益与代价 |

“每核 MMU”不是全簇共享 MMU。保持 iTLB/dTLB 独立、共享 PTW 与现有 walk-cache 的功能分工，不因移动实例层级自动增加寄存器或改变流水拍数。

## 2. 迁移前代码事实（记录基线 f7422d30）

- `BreezeFrontendPipeline.scala:105–125`：frontend 实例化 `Sv39Mmu`、I-cache；dTLB/PTW 接口暴露给外部。
- `Sv39Mmu.scala:14–19`：iTLB、dTLB、两级非叶 walk-cache、共享 PTW 在 MMU 内。
- `BreezePipelineLink.scala:16–28`：backend 向 frontend 提供 MMU context 和经过现有寄存边界的重启/sfence 信号。
- `BreezeCluster.scala:74`：`val context = backend.io.mmuContext` 是当前未使用的 Scala 别名，不是寄存器。
- `BreezeCluster.scala:88–90`：生产连接明确关闭预取，`l1i.io.prefetch.valid := false.B`。
- `L1IClient.scala:9–20, 28–81`：已存在 demand ID=0、prefetch ID=1 的独立槽，预取返回为无 ready 的 `Valid`，不会自动写入 I-cache。
- `BreezeViptICache.scala:182–215`：当前安装路径由需求 miss 的地址/set/way 所有权驱动；不能把任意预取响应直接接入这个返回口。
- 当前 cache line 为 32 B，协议在 `BreezeMemConfig.scala` 中要求 `lineBytes == 32`。

该基线没有启用有效的指令预取机制，但有可复用的底层请求/返回接口。只解除 valid 绑零，无法形成预取闭环。

## 3. R1：每核组织与迁移建议

建议由每核集成层同时实例化 frontend、backend、MMU、L1D、L1IClient；是否正式增加 `BreezeCore` 容器名，实施前再定，不作为本次记录的既定接口。

- frontend 暴露指令翻译请求/响应，接 MMU iTLB；保留候选 PA、一热选择、kill 等现有 VIPT/PMP 旁带。
- L1D 直连 MMU dTLB；Zve 配置继续经过共享接入逻辑，标量/向量的响应归属不改变。
- MMU PTW 内存端口接 L1D 物理通道，backend 的 CSR context 直接送 MMU；frontend/I-cache 需要的权限上下文仍明确提供。
- MMU idle 与 Zve idle 汇集后送 backend；sfence、权限更新、重启、取消与未完成 walk 的排空规则保持现有契约。
- 迁移现有 `BreezePipelineLink` 中属于 MMU 的接线时，保留已存在的时序边界，不能机械地去掉 RegNext 或多打一拍。
- 独立 frontend 测试通过测试包装器提供 MMU，不为保留测试入口而继续把整个 MMU 留在生产 frontend 中。

先做预取关闭条件下的等价迁移，再加入预取；用户于 2026-10-19 明确：本次只实施 R1，R2 预取留到下一次。

## 4. R2：推荐第一版——同页下一行 + 单行预取缓冲

本节是建议，尚未作为冻结 RTL 规格。

### 4.1 为什么先做这个

当前预测器与实际取指查询相连，尚无用于 FDIP 的独立预测领先/FTQ 预取指针。下一行方案可以先复用 L1IClient 的预取槽，并保持主要取指流水不变。使用独立预取缓冲，避免预取数据一返回就抢占 I-cache 写口或淘汰需求行；这不能消除 L2/DDR 的带宽和污染代价。

第一版建议每核仅一条预取 owner（在途或可用），可用数据容量为一条 32 B line。此容量是与现有一个预取槽匹配的起点，后续依据覆盖率、及时率和硬件代价决定是否扩充，不承诺足以覆盖任意内存延迟。

### 4.2 候选与地址

1. 从已经完成翻译、权限检查且未取消的真实取指结果，生成注册后的旁带事件；每次首次访问一条 line 才考虑其下一行，避免每条指令重复触发。
2. 候选地址：`candidateVA = alignDown(fetchVA, lineBytes) + lineBytes`。示例：32 B 行下，`0x80000100` 的下一行是 `0x80000120`。
3. 第一版限制在同一 4 KiB 页内。通过已成功翻译的当前地址计算下一行 PA，不额外触发 iTLB 查询或 PTW；跨 4 KiB 边界则跳过。大页也先按这个保守边界处理。
4. 同页只解决 VA→PA 映射连续性，不能替代 PMP/PMA：对候选整条 line 检查取指权限、物理地址范围、可缓存/可执行/非设备属性，排除地址加法溢出。检查失败丢弃候选，不产生架构异常。
5. 第一版不借“预取”自动设置页表 A/D 位，不因它启动新的页表遍历。后续跨页/分支目标候选必须设计独立的 hit-only 翻译探测契约，再评审是否允许 PTW。
6. 对当前需求行、预取在途行、预取缓冲行和最近候选去重。可用的 taken 预测显示顺序下一行明显不合适时，可抑制该候选；不通过预取修改 GHR、RAS 或提交训练。
7. 更完整的 I-cache resident 过滤可使用空闲 tag 查询；必须带查询 owner，需求访问优先，同 set 回填/失效冲突时丢弃或重试探测。该探测不读取 data array，不插入 F0/F1 demand 的组合关键路径；具体端口/时序需在 RTL 规格中定案。

### 4.3 请求、资源与返回

- 每核建议独立小型预取控制/缓冲单元，复用 `L1IClient.prefetch` 与 `prefetchRsp`。
- 只有已保留返回存储空间，才允许 `prefetch.fire`。无 ready 的返回必须总能被接收，即使结果将被丢弃。
- 候选未交给 L1IClient 时可以取消；交给 L1IClient 后，该槽的请求/响应所有权必须保留到排空。下游 coherence 请求已展示 valid 后，遵守现有反压保持规则，不因 redirect 撤回。
- 请求发射前，需求优先。预取身份也需在 L2 资源仲裁处可辨识（现有 I-side ID=1 可作为接入依据），限制全簇预取占用，并保留需求/PTW 所需资源。每核只有一个预取槽不等于全簇不会挤满 L2。
- 预取已被 L2 接受后通常不能抢占；因此不能承诺“对需求零干扰”，要测量额外流量和阻塞。
- 正常返回先进入带 PA tag 的预取缓冲，不主动安装到 I-cache。错误或已失效的返回只丢弃，不发 instruction fault。

### 4.4 真实需求怎样消费

| 需求遇到的状态 | 建议行为 |
|---|---|
| I-cache 命中 | 正常返回；预取不改变命中路径 |
| I-cache miss，预取缓冲命中 | 需求本身重新通过翻译与权限检查后，从缓冲提供数据，进入既有需求回填安装流程，不再发同一笔 L2 读 |
| I-cache miss，同地址预取仍在途且上下文有效 | 提升为需求等待者，记录该需求 owner，预取返回后服务需求，避免重复发同一地址 |
| 被提升的预取返回 error | 不把 speculative error 直接上报；转为正式需求读取，由需求路径决定最终架构异常 |
| 预取不存在/地址不同/已失效 | 正常需求 miss；不能等无关预取完成才允许发出需求 |

缓冲命中和“在途提升”都需要 I-cache 回填接入适配：先建立正式需求的地址/set/way 所有权，再返回数据。禁止把预取的 Valid 回包直接并到需求 refillRsp，避免无 owner 响应、双重响应或错误安装。需求与预取相遇同周期的优先级须在 RTL 规格与定向测试中明确。

### 4.5 取消、失效与存储边界

- redirect：丢弃未发出的旧路径候选；第一版保守丢弃旧路径缓冲数据。已接收事务保留 owner 并排空，不能只清 busy。
- `fence.i`：清除预取可见数据、候选和待安装资格；旧响应返回不能重新变成有效行。
- `sfence`、satp/权限/PMP 更新：丢弃旧翻译/权限上下文的候选与缓冲；若在途，标为 stale，等返回后释放槽，禁止提前复用导致旧响应冒充新响应。
- 同周期 flush 与 buffer fill/install，flush 的可见性清除优先；宽数据寄存器可接收旧数据，但 valid/owner 不得授权消费。
- I-cache 本身及预取缓冲都继续遵守现有指令一致性/fence 契约；不引入“硬件自动维护全部 I/D 一致性”的新承诺。

## 5. 第二阶段方向：FDIP

FDIP 用分支预测产生的未来取指地址引导预取，能够覆盖顺序下一行之外的分支目标。但预测器必须能够领先 I-cache，且有队列、独立指针、去重、翻译探测、错误路径清理和带宽节流。

建议当前先给预取请求生成与返回保存设计清楚接口，后续可更换候选来源：顺序下一行 → 预测目标 → 完整 FTQ/预测领先 FDIP。仅从当前预测器接一个 target 信号，不等于实现完整 FDIP。是否在本次直接实施 FDIP，尚未确定。

## 6. 开源实现：看什么与不直接照搬什么

1. **Rocket Chip `ICache.scala`**：可选 next-line prefetch，`s2_prefetch` 与 miss 关联；通过 TileLink Hint 发出预取。适合看下一行候选、页边界与 outstanding 管理。它的 Hint 请求不等于 Breeze 当前返回数据的 `Read`：不能复制接口后声称数据会进入 Breeze L1I。
   https://github.com/chipsalliance/rocket-chip/blob/master/src/main/scala/rocket/ICache.scala
2. **XiangShan 昆明湖 V2R2 ICache**：官方文档列出 MainPipe、IPrefetchPipe、WayLookup、MissUnit，描述 FTQ 驱动的 FDIP 和多个 miss owner。适合看预取与需求分工、预测领先和去重/回填组织；其大核多流水/多 MSHR 结构不宜整体照搬到 Breeze。
   https://docs.xiangshan.cc/projects/design/en/kunminghu-v2/frontend/ICache/
   文档标注 commit `4b2c87ba1d7965f6f2b0a396be707a6e2f6fb345`。调研的是该版本，不能把其他在建版本的描述当作同一份已实现 RTL。

## 7. 论文阅读顺序

- **Jouppi, ISCA 1990, Improving Direct-Mapped Cache Performance by the Addition of a Small Fully-Associative Cache and Prefetch Buffers**：先理解预取缓冲与把预取直接放入主 cache 的区别。本方案借鉴缓冲思想，不声称与原论文所有 stream-buffer 算法一致。
  https://doi.org/10.1145/325164.325162
- **Reinman, Calder, Austin, MICRO 1999, Fetch Directed Instruction Prefetching**：预测器与 I-cache 解耦，FTQ 提供预取地址；比较 next-line、stream buffer 和 FDIP，并研究空闲 cache 端口过滤。
  https://cseweb.ucsd.edu/~calder/papers/MICRO-99-FDP.pdf
- **Asheim, Kumar, Grot, 2020, Fetch-Directed Instruction Prefetching Revisited**：重点理解 FDIP 对 BTB 容量/覆盖范围的依赖，以及存储预算下的组织取舍。其结果不能作为 Breeze 的性能预测。
  https://arxiv.org/abs/2006.13547

## 8. 实施顺序与验收

1. R1：预取关闭条件下完成 MMU 独立化；验证取指/数据翻译、跨页 RVC、sfence/CSR/PMP 更新、Zve 共享、PTW/L1D 反压与 kill/排空。
2. R2：确定下一行候选、缓冲 owner、回填接入、同地址提升、flush 优先级与全簇资源节流的 RTL 规格，再实现。
3. 定向验证：同页顺序代码、分支跳走、跨页边界、PMP 边界/设备地址、候选重复、在途提升、error fallback、同周期需求/预取返回、随机反压、fence/权限更新与旧响应返回。
4. 多核与 Zve 并发：预取不能耗尽需求/PTW 的前进资源；记录可复现的反压场景。
5. 同 SHA/配置/程序与工具比较 prefetch off/on：cycles/retired inst、取指等待周期、I-cache miss、buffer hit、在途提升等待、预取发出/使用/丢弃/错误、额外 L2/DDR 流量。buffer 命中可能降低 miss penalty 而不改变原始 tag miss 率，不能只看 miss 数。
6. 若测试程序完全驻留 I-cache、或额外带宽让性能倒退，如实报告。资源/频率影响分别通过后续匹配综合和实现证据评价，不用功能仿真结果推断 Fmax。

当前已确定总体 R1/R2 范围；本次只实施 R1，R2 留到下一次。下一行算法、单行容量、触发时机、过滤/提升机制与 FDIP 分阶段方案为本次建议，待评审后再冻结实施。

## 9. R1 实施记录（2026-10-19）

用户指定本次只独立 MMU，预取下次实现。`BreezeFrontendPipeline` 只保留带 PA 候选/独热选择/kill 的 iTLB 接口，不再实例化 MMU，也不暴露 dTLB/PTW/sfence。每核集成层实例化原参数的 `Sv39Mmu`；dTLB、PTW、CSR 和 idle 从集成层直连，Zve/RVV 共享接入保持。原 restart/sfence 的 RegNext 不变，MMU 内部无修改、无新增流水级。frontend idle 改为局部排空，独立包装器/前端 L2 系统在边界合并 MMU idle。独立前端测试与 timing harness 使用 `BreezeFrontendMmuHarness`。

验证及源快照信息见 `docs/tasks/MMU-extraction-20261019.md`；只有通过的项目才计入证据。预取仍绑零，未执行 Vivado 或板上验证。
