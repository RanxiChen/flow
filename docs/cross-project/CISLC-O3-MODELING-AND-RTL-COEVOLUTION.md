# CISLC-O3 分层建模、参数调优与 RTL 协同演进

记录日期：2026-10-02。状态：最新计划及首个 uBTB 模块进展见第 10 节；此前 Spike 路线保留为历史决策。

**当前生效计划（第 10 节）**：随 CISLC-O3 RTL 逐模块在 nullrvsim 与 Spike 两边独立实现 C++ 类，以 RTL 提交为版本锚点，先以 uBTB 建立模块对照闭环。两个模拟器彼此校验；Spike 暂只放类及模块级周期测试，具体怎样参与 Spike 运行等完整 SoC RTL 实现后再决定。固定时间窗口多线程和 DDR 接入也按后续需求处理。不以预先固定的正确路径 trace 作为唯一执行基础。

**仅供本地指导，不提交或推送 GitHub。** 本文记录计划及实测进展；nullrvsim 模型代码另在个人 fork 的独立分支提交。既有 Spike／DDR 工程未迁移或删除。

目标工程：`/home/chen/work/CISLC-O3`。本文暂存于 Flow，与[前端设计基线](CISLC-O3-FRONTEND-DESIGN-BASELINE.md)、[后端设计基线](CISLC-O3-BACKEND-DESIGN-BASELINE.md)配合使用。

本文记录讨论共识与实际进展；nullrvsim 侧 uBTB 类和独立核心周期顶层、Spike 侧独立 uBTB 类均已实现并在模块级验证，尚未接入 Spike 指令执行或完成性能校准。另一个 agent 正在推进 RTL，后续模块实施前应重新核对版本。独立 DDR master 的既有工作见第 10.7 节。

## 1. 目标与时间约束

当前可用时间不到一周，每次修改参数都跑 Vivado 的周转成本太高。希望在 RTL 实现过程中同步建立可以快速运行的软件模型，用于理解机制、比较架构方案、筛选参数，再把有价值的选择带回 RTL 和 FPGA 验证。

模型允许省略电路细节，但必须保留研究问题所依赖的原理，例如依赖、资源容量、竞争、回压、预测更新和错误恢复。模型给出的收益需要能够解释原因，并明确适用范围。

建模本身也是帮助人理解设计的过程：把模块的输入输出、状态、推进条件、阻塞原因和恢复行为写清楚，并通过可运行的例子检验理解。

## 2. 已确认的协同演进方式

模型随着模块实现和集成逐层完善，不要求先完成整核 RTL 再另起一个模拟器项目。实现模块代码和 testbench 时，同步实现对应的软件模拟，利用共同的测试输入相互验证。

每次提交都应检查当前模型与 RTL 是否对应。这里的“对应”以明确约定的抽象层级为准，不要求所有模型都复刻每拍 RTL。RTL 变化后，应说明模型是否同步、变化是否影响模型的结论，以及有意保留的简化。

每次提交的闭环方向如下；具体文件格式与自动化检查方式待定：

1. 明确或更新模块约定：输入输出、状态、容量、阻塞和恢复规则。
2. 更新 RTL、对应的软件模型及测试；尚未覆盖的部分明确记录。
3. 复用测试场景，在约定的观察边界比较结果。
4. 定位差异，区分实现错误、模型错误和已声明的抽象差异。
5. 记录对应版本、参数、测试证据和未覆盖范围，随提交保留。

模型可以暂时不完整，但不能把缺失的机制当作已验证，也不能让已失配的模型继续提供未经说明的调优结论。

## 3. 分层验证的含义

以下是长期可采用的层级划分，不要求为每个模块同时实现全部层级。当前按第 10 节推进模块化周期模型与 Verilator 对照，再用 FPGA 校准；不另外要求同时交付一个 nullrvsim 中间层和一个 Spike 粗模型。

| 层级或工具 | 主要用途 | 与实现对应的内容和边界 |
|---|---|---|
| 功能模型 | 检查功能结果 | 输出、异常、架构状态；不凭此推断周期性能 |
| 简化架构性能模型 | 快速比较算法和参数 | 保留相关容量、依赖、竞争、阻塞和恢复机制；明确省略项 |
| 周期精确软件模型（按需） | 精细核对时序行为 | 在约定边界逐周期比较握手、事件和状态变化 |
| RTL 仿真／Verilator | 检验实现行为 | 基于实际 RTL 验证功能及周期行为；不能代替物理时序结果 |
| Vivado 综合与实现 | 判断资源和物理可实现性 | 资源使用、时序、可达频率等，区别于功能仿真 |
| FPGA 实际运行 | 获取实际系统证据 | 实际运行正确性、性能和系统交互，注明测试条件 |

这些证据互相补充。快速模型的预测、RTL 仿真结果、综合结果和板上实测必须分别标注。

## 4. 测试与相互验证

优先让软件模型与 testbench 使用相同的场景和输入，例如指令、请求、返回延迟和分支结果。在可比边界记录事件或状态，使差异能够定位到具体请求、状态转换或周期。

比较标准取决于模型层级：功能模型比较结果，架构性能模型比较所保留的机制与性能趋势，周期精确模型比较约定边界的逐拍行为。不能用简化模型没有表达的细节要求其逐拍一致。

相互验证还需要独立判据，避免 RTL 与模型复制同一个错误。可采用模块约定中的断言、手工可推导的小例子，以及适用时的 ISA 参考结果。具体测试框架和参考工具待定。

## 5. 示例：分支预测器逐步完善

以下用于解释演进方式，不表示已经选定首个建模模块或具体预测算法。

1. 算法阶段：使用相同分支序列，核对预测与更新状态，比较预测准确性。
2. 模块阶段：加入预测延迟、请求吞吐和更新冲突，观察前端等待。
3. 集成阶段：加入错误路径、重定向及恢复代价，研究对整体执行性能的影响。

如果 RTL 需要两拍返回预测，而模型只有一拍，该模型可以在明确条件下比较算法行为，但不能直接用其 IPC 代表最终实现。预测准确率、前端供给、整核 IPC 和实际频率下的执行时间需要分开理解。

## 6. 参数调优与模型的适用极限

先用快速模型筛选有意义的算法和参数，再通过更细的模型、RTL 或 FPGA 检查关键结论。调优结果需要保留对应的负载、配置、模型版本、假设和指标，便于解释和复现。

当各参数调整后的结果接近时，先区分：

- 当前设计对这些参数确实不敏感。
- 测试负载没有触发相应差异。
- 模型省略了关键机制，无法表达真实差别。

结果接近本身不能证明设计已经达到极限。当问题取决于模型无法覆盖的时序、资源或实际系统行为时，应转入 RTL、Vivado 或 FPGA 获取证据，避免继续进行缺乏判别力的参数扫描。

## 7. 后续逐步讨论，尚未决定

- 首个建模模块、首个需要模型回答的设计问题，以及最小交付范围。
- nullrvsim 的 CISLC-O3 模块接入、首批参数与运行用例；最新计划按第 10 节推进，Spike 既有工程保留。
- 用户以 xs-gem5 举例说明“为自己的核建立对应模型”的目标；本文未调查其实现，也不据此断言可以直接复用。
- 各模块需要保留的机制、允许省略的细节，以及层级间的观察边界。
- 参数定义、共同测试输入、事件记录、差异定位和提交检查的具体形式。
- 代表性负载、评价指标，以及从快速模型转向 RTL／FPGA 验证的条件。

后续讨论在本文增补决策，区分已确认方向、待定方案和实际完成的验证证据。

## 8. 模拟载体与备选方案（2026-10-02 补充）

本节保留早期备选调查记录，反映当时的进展，不作为当前实施指令；最新计划见第 10 节。下列链接未固定 commit，后续实施时需锁定版本并重新核对。

### 8.1 对分层目标的进一步澄清

早期设想以组内的 [nullrvsim](https://github.com/meng-cz/nullrvsim) 作为接近 Verilator 的次一级模型，选择性保持信号和周期对应，用更高效的软件计算替代内部电路细节。后续用户明确该层延后，不作为七天内交付依赖。具体对齐机制仍需深入源码核对。其公开 README 描述的是周期驱动 RV64GC 用户态模拟器，不能据此认定已有完整的 Linux 特权态、MMU 和设备验证能力。

用户指出：nullrvsim 即使比 Verilator 快，绝对运行时间仍可能偏长；更粗一级需要快速执行完整程序，只对研究所需的事件补充时间模型。该层可以是 Spike 等功能执行器加简化时序模型，不限定为 trace 回放。速度关系是用户反馈与设计目标，本轮没有对比实测。

最小设想是先增加 DDR 时序，再按问题需要增加 Cache、依赖、有限并发访存和分支代价。低精度对应关注相同程序和配置下的关键事件、瓶颈与性能趋势；不要求逐信号一致。

上游 Spike 的执行循环按退休指令数增加 `mcycle`，源码明确采用 CPI=1；具有周期计数不等于已经具备 DDR 等待或乱序时序模型。参考：[execute.cc](https://github.com/riscv-software-src/riscv-isa-sim/blob/master/riscv/execute.cc)。

### 8.2 保留的候选

| 方案 | 已查到的基本结构 | 对本项目的参考价值与待核对边界 |
|---|---|---|
| Coyote | Spike 提供功能执行与 L1 建模，扩展 RAW 依赖跟踪和指令延迟；Sparta 建模从 L2 开始的存储层次 | 最贴近当前设想；可优先研究执行器与时序模型的接口，但不能认定其 HPC 模型已经对应本项目的标量乱序核 |
| Stake / SST | Spike 与 SST 的访存生成、存储系统模型结合；SST 有相关接入源码与构建选项 | 快速指令执行加存储实验的先例；请求依赖、完成反馈、CPU 推进规则和版本兼容性待核对 |
| Sniper | interval simulation 用关键 miss 事件与指令窗口估计执行时间和延迟重叠，省略逐拍流水级跟踪 | 借鉴低成本表达乱序与停顿重叠的方法；尚未确认可直接用于本项目 RISC-V 环境 |
| gem5 TimingSimpleCPU | 使用 timing 访存，请求后等待存储系统响应再继续 | 可作为明确的阻塞式基线；不代表乱序核的访存并行能力，速度需实测；与使用延迟估计的 AtomicSimpleCPU 区分 |
| R2VM | 使用二进制翻译的 RISC-V 全系统、多核、周期层次模拟器 | 当指令执行器本身成为瓶颈时的备选；支持范围、维护成本与本项目适配尚未验证，论文速度不能当作本项目结果 |

来源入口：

- Coyote：[项目](https://github.com/MEEPproject/coyote)、[DATE 2021 论文](https://past.date-conference.com/proceedings-archive/2021/pdf/18002.pdf)。
- Stake / SST：[SST 13 构建说明](https://sst-simulator.org/SSTPages/SSTBuildAndInstall_13dot0dot0_SeriesDetailedBuildInstructions/)、[Stake generator 源码](https://github.com/sstsimulator/sst-elements/blob/master/src/sst/elements/miranda/generators/stake.cc)。构建说明是指定版本资料，不保证适用于最新版本。
- Sniper：[Interval Simulation 方法说明](https://snipersim.org/w/Interval_Model)。
- gem5：[SimpleCPU 官方说明](https://www.gem5.org/documentation/general_docs/cpu_models/SimpleCPU)。
- R2VM：[项目](https://github.com/nbdd0121/r2vm)、[论文](https://arxiv.org/abs/2005.11357)。

### 8.3 后续评估的关键问题

选型阶段提出“功能执行器 + 轻量依赖/时间模型 + Cache/DDR 模型”，并建议优先细读 Coyote。该阶段仅确认保留备选；随后完成源码调查并形成第 9 节的当前决定。

- 时间推进：每笔访存都阻塞，还是允许有限未完成请求及计算重叠？不能把所有 DDR 延迟简单累加后直接用于估计乱序核性能。
- 请求来源：直接将每条 load/store 送到 DDR，与经过 Cache 后送出 miss/writeback 是不同假设，需明确选用哪一种。
- 存储精度：可先用延迟、带宽、队列模型；研究 bank、行命中或控制器调度时再考虑详细 DRAM 模型。详细 DDR 模型也可能成为仿真速度瓶颈。
- 加速收益：在代表性程序上分别衡量执行器和时序模型开销，再决定是否需要二进制翻译等更复杂底座。
- 对齐证据：当前直接用 Verilator RTL 的事件和停顿结果检查简化模型，记录趋势是否一致、误差及不适用范围；nullrvsim 留待后续。

## 9. 历史决策：Spike 粗模型与 Verilator 两层闭环（已由第 10 节调整）

本节保留当时的决定及论据；其中“当前”“尚未实施”等表述均指该次讨论时点。功能先行、单线程调度和 nullrvsim 延后等要求已被后续讨论调整，不应据此启动新的实现。

### 9.1 决策与交付边界

2026-10-02 用户确认：“定下来技术路线，就这么做，就用 spike，先固定到文档里面”。

当前路线为：**复用 Spike 功能执行，自建对应 CISLC-O3 的简化 C++ 核心时序模型与事务级 Cache，DDR 接 DRAMsim3；直接与 Verilator RTL 仿真对照。**

- 七天内优先完成上述两层可运行、可对照的最小闭环；不把所有性能机制完善或整核 Linux 性能校准当作已经可以保证的交付。
- nullrvsim 延后，不作为当前交付依赖。
- Coyote／Stake 等用于组织方式、机制与接口参考，保留备选，不整体继承其工程作为当前底座。
- 专用 agent／PI 二次开发暂缓；采用现有开发工具和普通实验脚本。
- 本次授权动作是记录路线；尚未克隆、patch、构建、仿真或测速。

### 9.2 技术栈与责任分工

| 部分 | 已选路线 | 责任与边界 |
|---|---|---|
| 指令功能执行 | 固定版本 Spike | 复用指令语义和架构状态；不从头重写 RV64GC 执行器。具体 commit 待锁定 |
| Spike 适配层 | 集中维护的小范围 C++ 修改 | 提供动态指令及访存事件；具体字段、取指／访存观察点和异常边界待定 |
| 核心性能模型 | 自建 C++ 简化模型 | 按本核机制表达动态依赖、有限指令窗口、吞吐与延迟、未完成请求和有序退休 |
| Cache 层次 | 自建 C++ 事务级时序模型 | tag、替换、命中延迟、有限 MSHR、同 line miss 合并、回填生效和写回队列；逐步补齐 |
| DDR | DRAMsim3 | 保留简单延迟模式作为对照；粗模型与 RTL 可通过各自适配器复用同一种后端及配置 |
| 时间调度 | 单线程，时间戳／事件推进 | 核心避免逐拍遍历所有内部模块；DDR 后端仍按其时钟接口推进，不假定可任意跳过 tick |
| 构建与实验 | CMake + Python 脚本 | 自建部分用 CMake；Spike 保留其原生构建方式，通过脚本或外部依赖集成。Python 承担参数扫描和结果汇总 |
| 实现对照 | Verilator | 同一程序、输入及统计区间下，检查功能结果、关键事件和性能趋势 |

C++ 标准、依赖版本和目录布局尚未冻结。优先使用内存中的紧凑事件，文本逐指令日志用于调试，不作为默认性能运行通道。

### 9.3 功能执行与时序执行分离

```text
Spike：按程序顺序计算真实结果与执行路径
                  ↓ 有限缓冲中的动态指令／访存记录
自建核心模型：依赖、窗口、执行资源、完成与退休时间
                  ↕ 带时间及身份的请求／完成反馈
事务级 Cache 层次 → DRAMsim3（或简单延迟基线）
```

Spike 算出结果不代表该结果在时序模型中已可使用。load 的消费者必须等待模型中的完成事件；访问地址即使已知，也必须等地址生产者及发射条件满足才能形成时序请求。至少保留动态生产者依赖、有限窗口、资源吞吐、未完成访存和退休限制，才能研究乱序执行的延迟隐藏。

功能层与时序层之间的缓冲上限、数据及地址对应、异常／串行化边界、store 顺序和时间相关软件行为仍需具体设计。不能直接把 Spike 默认 `mcycle` 当作性能模型结果；初版估计周期独立统计，是否反馈至 guest 可见计时状态待定。

### 9.4 首版抽象及适用范围

- 采用正确路径的功能先行模型；分支预测模型按约定更新，并表达解析和恢复代价。首版不真实执行错误路径，因此不能完整评估错误路径取指／访存污染。
- 首批用例采用两层均能运行的单核裸机测试或计算片段，明确输入、初始化和统计区间，先建立普通可缓存访存闭环。
- Spike 已有指令支持不代表对应机制的时间行为已建模。MMIO、中断、页表遍历、原子操作和完整 Linux 时序评估的覆盖需逐项说明。
- 不将“每次访存都阻塞”的简单模型称作本核乱序模型；它只可作为调试或对照模式。
- DRAMsim3 负责时序，功能数据由各自执行环境维护。请求身份、相同地址的多笔请求、背压、CPU／DDR 时钟换算及完成事件对应由适配层明确处理。
- 粗模型与 Verilator 共用 DDR 配置有助于对照，但不等于已复现 FPGA MIG 控制器和板级行为。

### 9.5 为什么采用此路线

本次源码调查得到以下依据，属于接口与工程复杂度判断，不是构建成功或性能实测结论：

- Coyote 已有 RAW 停顿、MSHR 限制、请求完成和核心恢复逻辑，值得参考；同时依赖定制 Spike、SPARTA、BookSim，Cache 与框架端口、事件、统计耦合。整体替换核心存在额外适配成本。见 [构建文件](https://github.com/MEEPproject/coyote/blob/main/Coyote/CMakeLists.txt)、[SPARTA 固定版本](https://github.com/MEEPproject/coyote/blob/main/install_sparta.sh)、[执行调度](https://github.com/MEEPproject/coyote/blob/main/Coyote/src/ExecutionDrivenSimulationOrchestrator.cpp)。
- 查到的 Stake generator 在 `generate()` 内调用 Spike 运行并收集请求，`completed()` 为空；不能据此当作现成的核心—存储完成反馈闭环。此结论限于该接入代码，不概括 SST 所有组件。见 [Stake 源码](https://github.com/sstsimulator/sst-elements/blob/master/src/sst/elements/miranda/generators/stake.cc)。
- Spike 的 `cachesim` 提供 tag／miss／写回统计，访问接口没有完成时间，不能直接替代所需的非阻塞时序 Cache。见 [cachesim.cc](https://github.com/riscv-software-src/riscv-isa-sim/blob/master/riscv/cachesim.cc)。
- Spike 官方不保证内部 C++ API 稳定，因此锁定提交并集中维护适配层。见 [API 说明](https://github.com/riscv-software-src/riscv-isa-sim#versioning-and-apis)。
- DRAMsim3 提供 `WillAcceptTransaction`、`AddTransaction`、`ClockTick` 和完成回调，便于两层各自接入。见 [接口](https://github.com/umd-memsys/DRAMsim3/blob/master/src/dramsim3.h)。Ramulator 2.1 保留为替代项，当前不并行集成两种 DDR 后端。

### 9.6 最小闭环的验收顺序

1. **功能对应**：同一短程序下，比较退休 PC／指令及架构结果；访存按对应指令核对地址与数据，不要求乱序发出顺序与 Spike 一致。
2. **机制对应**：用依赖 load、独立 load、Cache 冲突、请求队列耗尽等小例子，解释两层的等待和重叠；保留独立断言与可推导结果。
3. **参数有效**：每次改变一个参数，比较性能变化方向及误差；只对模型覆盖且 RTL 可配置的范围作结论。
4. **速度有效**：测实际墙钟时间和吞吐，区分功能执行、核心时序、Cache／DDR、日志开销。快于 Verilator 是目标，尚无测量证据。

首个里程碑是一个真实可运行、能够定位差异并回答一项调优问题的闭环。实现顺序、首批程序、事件格式、模型误差判据和提交检查脚本在后续讨论中细化；本节固定路线，不将未完成工作记为已验证。

## 10. 当前计划：复用 nullrvsim，随 RTL 逐模块建模

### 10.1 目标与取舍

用户确认：一周以内做出能用来选配置、能够接受 FPGA 评估的产品；不追求全局最优、通用框架或企业级完整验证体系。先用粗模型寻找较好的可实现配置，再上 FPGA 比较实际性能；误差可接受就使用，发现影响选择的系统偏差再改进。

模型仍按周期推进，粗的是模块内部细节和暂未覆盖的机制。随着 RTL 增加 BTB 等模块，同步实现对应 C++ 类、读取／更新接口和周期状态变化。不得将独立生成的正确路径 trace 作为唯一执行基础，以免限制以后增加反馈、恢复和更精细行为。

在 nullrvsim 和 Spike 仓库分别新增 CISLC-O3 模块类，保持两份实现独立，以减少共用实现错误。Spike 侧目前仅实现类和模块级周期测试；用户明确要求完整 SoC RTL 出来之前不接入 Spike 指令执行，也暂不确定其运行调度方式。两套执行状态不互相嵌入。

### 10.2 源码调查依据与边界

调查版本：[meng-cz/nullrvsim @ 8a45625153ddf52631bae7f27c765977b4afce7b](https://github.com/meng-cz/nullrvsim/commit/8a45625153ddf52631bae7f27c765977b4afce7b)。本轮只读源码，未修改、构建、运行或测速。结论是结构适配判断，不能当作运行正确性或加速比证据。

- `src/common.h` 的 `SimObject` 已有 `on_current_tick()` 和 `apply_next_tick()`；周期队列及寄存器类支持当前／下一状态，适合逐模块扩充。
- 当前 `cpu/xiangshan` 核心内部逐个调用 BPU、队列、寄存器、执行单元与 LSU；新增模型不要求先实现全部信号细节。
- 现用 BPU 位于 `cpu/xiangshan/xsbpu.*`，与 FTQ、训练、恢复相连。`xsv3sys/bpu/ubtb.hpp` 有独立 MicroBTB，但在本次检查的启动路径中未看到接入；不能把新目录当作已运行的完整前端。
- `CPUInterface`、`CPUSystemInterface` 及启动代码提供新增核心的接入位置。现有香山式核心与 CISLC-O3 并不等价，修改配置不能代替结构对应。
- 当前公开工程是用户态模拟器；特权态、完整 Linux 启动、真实页表遍历不计作已有能力。

源码入口（均固定同一提交）：

- [模块基础接口](https://github.com/meng-cz/nullrvsim/blob/8a45625153ddf52631bae7f27c765977b4afce7b/src/common.h)
- [调度器](https://github.com/meng-cz/nullrvsim/blob/8a45625153ddf52631bae7f27c765977b4afce7b/src/simroot.cpp)
- [现有核心](https://github.com/meng-cz/nullrvsim/blob/8a45625153ddf52631bae7f27c765977b4afce7b/src/cpu/xiangshan/xiangshan.cpp)
- [现有 BPU](https://github.com/meng-cz/nullrvsim/blob/8a45625153ddf52631bae7f27c765977b4afce7b/src/cpu/xiangshan/xsbpu.cpp)
- [独立 MicroBTB](https://github.com/meng-cz/nullrvsim/blob/8a45625153ddf52631bae7f27c765977b4afce7b/src/xsv3sys/bpu/ubtb.hpp)
- [CPU 接口](https://github.com/meng-cz/nullrvsim/blob/8a45625153ddf52631bae7f27c765977b4afce7b/src/cpu/cpuinterface.h)

### 10.3 第一项工作：CISLC-O3 uBTB 小闭环

建议新增独立 `cislc` 模型目录，具体路径在实施时确认，避免直接把组内现有核心改成另一种含义。

1. 重新核对当前 `rtl/frontend/ubtb.sv`、相关类型和 `sim/cocotb/ubtb/ubtb_model.py`，记录对应版本。
2. 按 CISLC-O3 合同写 C++ uBTB 类，提供查询、训练及周期更新；接口名称不要求照搬其他模型。
3. 复用模块测试场景，比较同周期查询结果、边沿训练后状态、stall、复位、替换与别名行为；保留独立的小例子作为判据。
4. 接入顶层周期调用；再接前端查询、提交训练、FTQ 与恢复，使模型真正影响执行和统计。
5. 测代表性运行的时间开销，再决定优化哪些类或调用路径。

本次读取的 CISLC-O3 uBTB 是同周期组合查询、边沿提交训练、空项优先及 round-robin 替换；nullrvsim 新 MicroBTB 的查询流水级、训练策略和 PLRU 不同。应复用框架，模块行为以 CISLC-O3 为准。RTL 正在变化，以上是调查时点事实。

模块状态分别记录为“类已实现并独立验证”“已接入运行”“已与 RTL 对照”，不能混为一项完成标记。尚未实现的部分可临时借用已有模型，但其结构差异应明确，不能将借用后端所得 IPC 直接标为 CISLC-O3 性能。

### 10.4 周期与并行：先保留边界，再按开销改进

目标方式是 CPU、DDR 等线程在固定模拟时间窗口内各自逐周期运行，在窗口边界同步并交换消息；后续可能把核心内部工作再拆到多个线程或进程。当前不提前实现所有拆分。

现有调度器每个全局 tick 执行 `on_current_tick → barrier → apply_next_tick → barrier`，对象可按整数 tick 间隔推进；它尚不是多周期窗口调度。检查的 `mpl3`／`mpl1l2` 启动路径使用普通对象注册，不能仅增大 `thread_num` 就认为模块自动分配到了不同线程。

阶段安排：

- 初期沿用周期接口，模块内部可由所属核心直接调用，不强制每个小类成为独立线程任务。
- 当核心需要 DDR 或测得类调用成本较高时，测量计算、队列、日志与同步开销，再选择并行边界。
- 窗口化时增加明确的跨线程消息缓冲与时间戳；不能只删除现有 barrier，让不同周期的模块直接访问共享状态。
- 初版允许窗口边界交付带来的可说明延迟近似；窗口长度可调，额外延迟及配置排名变化需与 FPGA 结果一起评估，不预设误差阈值。
- CPU／DDR 以共同模拟时间描述各自周期，不能默认 tick 一一对应。独立进程所需的共享内存／消息传输留到实际需要时处理。

以上是后续目标，不宣称已实现多线程加速或跨时钟同步。

### 10.5 DDR：保留现有主存，按需要接 DRAMsim3

本次检查的公开版本有简化主存模型，没有看到 DRAMsim3／Ramulator 或完整 DDR 时序后端接入：

- `cache/moesi/MemoryNode`：有请求缓冲，按 `dwidth` 每周期搬运一部分 cache line，完成后经总线响应。
- `SimpleCoherentLLCWithMem`：LLC miss 叠加固定 `memory_latency` 及 cache／传输延迟。
- 上述模型不表达 DDR 的 bank／row 状态、行命中／冲突、刷新和命令时序约束。

参考：[MemoryNode](https://github.com/meng-cz/nullrvsim/blob/8a45625153ddf52631bae7f27c765977b4afce7b/src/cache/moesi/memnode.cpp)、[固定延迟主存](https://github.com/meng-cz/nullrvsim/blob/8a45625153ddf52631bae7f27c765977b4afce7b/src/cache/simplecc/sccllc.cpp)。

当 CISLC-O3 的实现推进到需要 DDR 时，再增加或替换主存节点的时序部分：

```text
cache／总线
    ↓
主存节点：功能数据、事务身份、请求缓冲、响应与背压
    ↕
DRAMsim3 adapter：时钟推进、请求接受、完成回调
```

DRAMsim3 决定服务时序，功能数据仍由主存模型维护。需要明确相同地址多请求的身份对应、读完成、写缓冲接受与物理写完成的区别，以及响应阻塞时的容量；不能把所有写回调都视为 DRAM 物理写完。此接入不是现有配置开关，也不要求因此重写核心。

### 10.6 交付判断、来源与本地文档约束

- 最小交付目标：能运行目标计算区间、能比较少量实际可实现参数、能用 FPGA 数据判断结果是否可接受。
- 先比较总周期；必要时借助分支误预测、cache miss、访存等待等少数计数定位差异。尽量对照一个有代表性的参数变化，检查性能趋势。
- 不追求全部信号严格对齐；每次模块提交检查约定边界是否与 RTL 对应，未覆盖与有意近似写清楚。
- 明确这是基于 nullrvsim 增加 CISLC-O3 模型，保留原代码来源与声明；贡献集中于本核建模、RTL 对照和 FPGA 校准，不包装为独立发明框架。
- 本指导文档仅在本地维护，不加入提交、不推送 GitHub。模型代码在独立仓库和分支中提交，具体状态见第 10.7 节。

### 10.7 既有工作保留与下一步

既有 Spike 工程位于 `/home/chen/work/cislc-spike`。独立 DDR master 和 DRAMsim3 fork 的工作不作废；先保留，后续可复用其请求／回调适配经验和测试场景，不在此次记录中迁移、删除或重新运行。

已有 DDR 实现与运行证据详见本地文件 `/home/chen/work/cislc-spike-notes/DDR-FIRST-RUN.md` 及其旁的 `ddr-initial/`。这些记录只证明独立 DDR master 的对应结果，不证明 nullrvsim 集成已完成。

2026-10-02 已建立 WSL `/home/chen/work/cislc-nullrvsim` 与 alan `/home/chen/work/cislc-nullrvsim` 两份工作副本，均在 `cislc/model` 分支、提交 `cd3e391c9d67237fd827fec106404e5748f33c6d`；该分支已推送到个人 fork `RanxiChen/nullrvsim`。分支从上游 `8a45625153ddf52631bae7f27c765977b4afce7b` 建立，原 fork `master` 保留。

首个模型位于 `src/cislc/`：`Ubtb` 类和独立 `Core` 周期顶层，不修改已有五级流水核或香山式核。模型声明对应 CISLC-O3 **RTL 提交** `257df8d62ed2b58d95b66194fa008d71f89a840b`；独立的 cocotb／Python 参考模型提交 `2e25a3acb7d52b0d00a5d8177fa3e194be947578` 仅记作验证来源。当前 uBTB 源文件与该 RTL 提交的文件 SHA-256 相同。

验证证据：本地和 alan 均通过 CMake 构建独立 uBTB 测试目标及 nullrvsim 主程序、CTest 合同测试；本地 C++ 模型与 O3 cocotb 所用 Python 参考模型对照 1549 周期，逐周期比较边沿前／边沿后输出，通过；从固定 O3 提交导出的 RTL 在 `/tmp` 用 Verilator/cocotb 跑 `directed_contract` 与 `seeded_transactions`，2/2 通过。上述证明 uBTB 模块边界的对应，不证明其已参与整核执行、IPC 调优或 FPGA 校准。测试产物留在各自构建目录，未提交 O3 仓库。

后续随 RTL 完善继续增加独立模块，每次 O3 RTL 改动以 RTL commit 为版本标注，cocotb commit 另列为验证来源。Spike 侧运行接入明确推迟到完整 SoC RTL 形成之后再讨论；DDR、窗口调度和进程拆分继续按实际需要推进。

### 10.8 Spike 侧独立 uBTB 类（2026-10-02）

Spike fork 的 `cislc/perf-model` 分支新增 `sim/cislc/`，提交 `339807b9`；WSL `/home/chen/work/cislc-spike` 与 alan 同步。`Ubtb::observe()` 给出当前组合输出，`Ubtb::tick()` 推进一个边沿，类内部实现与 nullrvsim 侧分开。其行为对应 O3 **RTL 提交** `257df8d62ed2b58d95b66194fa008d71f89a840b`；cocotb 参考模型提交 `2e25a3acb7d52b0d00a5d8177fa3e194be947578` 仅为验证来源。

通过独立 CMake 目标在 WSL 和 alan 编译；两边使用 O3 cocotb 参考模型对照 1228 个周期的边沿前／边沿后输出，全部通过。用同一场景运行 nullrvsim 侧 runner 也通过。没有修改 Spike 的取指／执行循环，未实现 Spike 运行时的周期调度或 memory 后处理；这些工作待完整 SoC RTL 形成后讨论。此结果只证明模块合同，不是整核性能证据。
