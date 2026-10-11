# Breeze/Spike 周期模型：MMU、存储接口与 DDR 接入建议

记录日期：2026-10-04。性质：设计建议，供后续讨论和逐模块实现使用；本文不是已完成的 Spike 集成或性能验证报告。

## 1. 目标与当前依据

目标是在独立的 Breeze Spike fork 中，随新版 Breeze RTL 逐模块建立可推进周期的 C++ 模型，用共同输入比较模块行为；随后接成可运行的核心模型，用于参数调优，并以 Verilator、FPGA 结果检查误差。模型保留请求、背压、资源占用、恢复和完成时序。各模块是否已经实现、是否与 RTL 对齐，必须分别记录。

- Spike 工作树：`/home/chen/work/breeze-spike-simulator`，分支 `breeze/simulator`。本次查看的 HEAD 为 `76ce016b6765d66c93522b0ea9a16a44841cb331`；`sim/breeze/core.h` 中的 `BreezeCore::step()` 仍只是空壳，尚未接入 `sim_t`。
- 本文核对的 Flow HEAD 为 `805612a8061e5de62de55760bd920f5978931efb`。新版 MMU 以 [`breeze-mmu-rtl-spec.md`](../breeze-mmu-rtl-spec.md) 为 RTL 规格。新版 D-cache/L2 接口尚在演进；[`dcache-pipeline-design.md`](../dcache-pipeline-design.md) 的 L2 流水及慢事务部分是建议起点，不等于已集成的新 SoC。
- 目前已连接的**旧版**路径可用来辨认板上出口：[`BreezeMulticoreClusterWishbone.scala`](../../design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala) 把 L1I、`BreezeDCache` 接到共享 `BreezeL2Home`，后者输出 64 位 Wishbone；[`litex_wrapper/flow/core.py`](../../litex_wrapper/flow/core.py) 将其送入 LiteX；[`fpga/kcu105/target.py`](../../fpga/kcu105/target.py) 用 LiteDRAM 提供 `main_ram`。旧 Wishbone 端口和 L2 几何并非新版必须沿用的内部合同。

## 2. 建议的模块边界

```text
                          每个 BreezeCore 按核心周期 step
                            |                    |
                    取指/访存请求             CSR、kill、sfence
                            |                    |
                   I-cache / D-cache <---- BreezeMmu
                            |               iTLB / dTLB
                            |               walk-cache / PTW
                            |                    |
                            +<--- PTW 物理 PTE 读-+
                            |
                         共享 L2/Home
                            |
                    物理内存事务端口
                            |
                总线/DDR 时序 adapter
                            |
                    功能数据后备存储

       Spike processor_t + 原有 mmu_t：独立的 ISA 语义参考端
       ELF 装载、tohost 轮询：宿主侧功能访问，不代表 CPU/DDR 时序
```

三个边界分别承担不同责任：

1. **核心到 MMU**：按 RTL 的 iTLB/dTLB `req.valid/ready`、下一拍 `resp.valid`、`kill` 和 `sfence` 建模。MMU 输出 PA 或 fault；请求方随后访问 I/D cache。MMU 不直接调用 DDR。
2. **PTW 到 D-cache**：PTW 发 8 B 对齐的物理 PTE 读，等待 D-cache 返回数据或 access fault。PTE 读可以命中 D-cache，也可以经 L2 到 DDR。这个端口已有明确的 RTL 合同，见 MMU 规格第 4.5 节。
3. **L2 到下级内存**：只传物理内存事务，主要是 cache line 读取和脏行写回。L1 命中不产生 DDR 请求；L2 的目录、probe 和 owner 数据回收在此端口上方完成。MMIO 走独立的设备路径；一致性 DMA 应先进入 L2/Home 仲裁。

### 2.1 建议的 L2 下行接口

先定义一个与 Wishbone 无关的请求/完成合同。建议字段如下，确切宽度及是否支持多笔在途须随新版 L2 RTL 冻结。

| 方向 | 字段 | 原因 |
| --- | --- | --- |
| 请求 | `id`、物理地址、读/写、长度 | 匹配完成事件；长度由新版 cache line 和可能的细分传输决定 |
| 写请求 | 数据、byte mask | 支持脏行写回及以后需要的部分写入 |
| 握手 | `valid/ready` | 区分“尚未被接收”和“已接收但尚未完成” |
| 响应 | `id`、读数据、错误、完成有效 | L2 等待真实完成后再安装/释放资源；错误不能当作成功 refill |

第一版可按当前 L2 设计建议只允许一个下行事务在途，同时让不冲突的 L2 命中继续前进。模拟器后端可先用固定延迟和有限队列验证握手，再替换为 DDR 时序模型；FPGA 侧则由 adapter 将事务转换成实际总线访问。adapter 必须计入仲裁、拍数和排队时间，不能把 DDR 芯片延迟直接当作 CPU 可见的总延迟。若新版 RTL 最终采用直接 Wishbone/AXI 出口，应在该出口与模型端口之间记录精确映射，必要时把模型细化到 beat 级。

DDR 模型负责**何时完成**；后备存储负责**返回什么数据**。请求接收、数据生效和响应可见的先后顺序要定义清楚，尤其是脏行写回、同地址后续读取和错误响应。CPU 时钟与 DDR 控制器接口时钟分别推进，用确定性的时间换算连接；不能把 DDR 物理传输速率直接当作一个 `BreezeCore::step()` 对应的 DDR tick。先跑通单线程确定性调度，再评估把核心与 DDR 分线程的收益，并保留相同的事务边界。

## 3. Spike 指令语义怎样复用

Spike 的指令文件是同步语义：例如 [`lw.h`](/home/chen/work/breeze-spike-simulator/riscv/insns/lw.h) 调用 `MMU.load<int32_t>()`，得到值后立即 `WRITE_RD`；[`decode_macros.h`](/home/chen/work/breeze-spike-simulator/riscv/decode_macros.h) 将 `MMU` 定义为 `*p->get_mmu()`。`processor_t::step(n)` 的主循环取指、执行和更新 PC/退休计数，没有可在一次 `MMU.load()` 中暂停并于后来周期恢复的接口。Spike 的 `mmu_t` 还会直接读取/写入宿主 RAM 指针；其页表遍历也同步读 PTE。它是 ISA 功能模型，不是 Breeze TLB/PTW 的一拍或多拍实现。

因此建议：

- **保留原 Spike `processor_t + mmu_t` 作为架构参考端**。利用既有指令实现检查退休后的寄存器、PC、异常和 CSR 结果；必要时比较翻译 PA/fault。参考端的 TLB 命中和 walk 次数不用于性能统计。
- **在 `sim/breeze/` 独立实现 `BreezeMmu` 周期类**，由 `BreezeCore::step()` 调用。它保存 iTLB、dTLB、walk-cache、PTW 和请求/响应状态，并使用与 RTL 相同的复位、资源数、优先级和冲刷规则。它才是模拟器访存时序的来源。
- `BreezeCore` 不能仅通过继承 `processor_t` 并替换一个指针完成整合：当前 `processor_t` 构造时直接创建 `mmu_t`，指令宏同步调用非虚模板 `load/store`；即使替换对象，也缺少暂停、重试和投机撤销边界。架构参考与周期状态先用组合关系连接。

**整核运行尚有一个独立的缺口**：只在退休时调用 Spike，无法替 Breeze 的取指/执行级计算早期分支结果和访存地址。新核心的执行模型必须根据其 RTL 管线产生这些事件，并保留操作数依赖、等待和恢复。可以复用 Spike 的 `insn_t` 字段解码和指令语义作参考；若要让同一套宏直接参与周期执行，需要先设计可挂起的访存接口及无副作用的执行/提交边界，这不是简单替换 `mmu_t`。此决定应等新版核心执行接口明确后单独处理，不能将“MMU 模块模型完成”写成“完整程序周期执行已完成”。

参考端必须有独立或受控的内存视图：模拟器中的 Store、MMIO、AMO 只能按架构规定产生一次外部副作用。若 Spike 参考执行再次写入同一设备或同一份时序后备存储，会造成双写；加载程序时可将相同初始镜像装入两个视图。设备输入、DMA 写和时钟相关 CSR 也需要显式同步后才能声称整机差分正确。

### 3.1 一次 dTLB miss 的具体推进

按当前 MMU RTL 规格，以 PTW 中层 walk-cache 命中、一次 PTE 读取为例：

| 周期 | 周期模型的事件 |
| --- | --- |
| N | dTLB `req.fire`，锁存虚拟地址和访问类型 |
| N+1 | dTLB 返回 `miss`，记录等待请求；PTW 尚未直接得出 PA |
| N+2、N+3 | PTW grant，查询 walk-cache |
| N+4 起 | PTW 向 D-cache 发物理 PTE 读；D-cache/L2/DDR 延迟共同决定响应周期 |
| 响应后 | PTW 检查 PTE：有效叶项 refill TLB，fault 则保存待交付状态；请求方按原次序重发，下一拍得到 `hit` 或 fault |

规格中的 `L` 是 **D-cache 接受 PTE 请求到响应的总延迟**，并非恒定 DDR 延迟。PTE 在 D-cache 命中时没有 DDR 事务。无 walk-cache 命中时可读三级 PTE，不能把三次读取合并成一次固定等待。具体拍数见 MMU 规格第 9 节。

## 4. 冲突、恢复和精确状态

- iTLB、dTLB 共用一个 PTW；当前规格规定两侧同时 miss 时 D 侧优先，miss 阻塞，PTW 单 walk 在途。模型的队列容量和仲裁必须一致。
- `kill` 在 PTW grant 前可取消 miss；grant 后 walk 继续，但被杀请求的 fault 不交付。后续 PTE 读取仍可能占用 D-cache/L2/DDR，不能把 kill 误作下级事务立即消失。
- `sfence.vma` 由核心串行化：冲刷前端、等 store buffer 排空、等 MMU idle、发一次 sfence、再等 idle，之后重新取指。Spike 指令实现中的 `MMU.flush_tlb()` 只提供功能参考，不代替上述周期过程。
- 页错误与 access fault 必须关联原始取指/Load/Store；被冲刷的年轻请求不产生架构异常。精确异常和退休接口须随新版核心定案。
- PTW 的 PTE 读是物理访问，但 D-cache 端仍按规格做 S 模式 PMP/PMA 检查；不让其重新经过 dTLB，避免递归翻译。

## 5. 分步实施和验证门槛

| 步骤 | 最小交付 | 完成时可声称什么 |
| --- | --- | --- |
| 1. 固定版本与端口 | 记录新版 RTL SHA、MMU 参数、C++ 请求/响应字段 | 模型有明确对照版本 |
| 2. 独立 `BreezeMmu` | TLB、walk-cache、PTW、`step()`；可注入 PTW PTE 响应 | 对 MMU 模块逐拍比较 hit/miss/fault/kill/sfence；不声称 DDR 或整核完成 |
| 3. 存储链路 | PTW→D-cache→L2→物理内存事务端口；先接固定延迟后端 | PTE 访问会受缓存、仲裁和下级等待影响 |
| 4. DDR 后端 | 接收/完成、数据后备存储、时钟换算、错误与背压 | 指定配置下能测完整下行延迟；尚需 FPGA 校准 |
| 5. 整核执行/退休 | Breeze 管线事件与 Spike ISA 参考端对照 | 指定程序范围内的功能与周期统计；需列出尚未覆盖的设备/异常 |
| 6. 对照与调参 | 同一镜像、RTL SHA、参数和计数口径的 Verilator/FPGA 结果 | 在已测工作负载上评估模型误差和参数趋势 |

每次模块提交记录：对应 RTL SHA、接口与参数、共同测试向量、逐拍比较结果、未覆盖场景和运行地点。构建、较大仿真和性能实验按现有工作约定放在 Alan；本文件只作设计记录。本次编写文档没有运行构建、测试或 FPGA 实验。

## 6. 新版 RTL 到来后优先确认的事项

1. 新版 D-cache 的 PTW 物理读端口是否仍按 MMU 规格第 4.5 节提供 `req/resp`，以及在普通 miss、probe、store 排空下的前进保证。
2. 新版 L2 下行是整行事务还是显式 beat；是否只有一笔内存事务在途；写回完成与 refill 安装的顺序。
3. 新版核心何时产生取指、Load/Store 的 VA、访问类型与 operand，何时允许提交 Store/AMO，精确异常和 `sfence.vma` 的退休边界是什么。
4. 模型第一批要覆盖的程序与 ISA/特权功能，以及 Spike 参考内存、设备和 `mcycle/mtime` 如何同步。

这些问题回答前，先实施已定案的独立 MMU 模块模型和它的逐拍对照，暂不把 Spike 的同步 `mmu_t` 改成周期 MMU，也不把旧 Wishbone 端口固定为新版 L2 的内部接口。
