# 新前端接入BE2后端：接口所有权与删除范围

用户2026-10-12授权：生产顶层改为最近完成的流水前端及BE2-P重构后端，删除旧前端模块及接线路径。工作分支refactor/be2-backend-decoupling，集成起点2746032。历史板上bit及证据保留；源码删除不删除已有交付文件。

## 接线与生命周期

| 接口 | 所有者与规则 |
|---|---|
| F0/F1/F2及instruction队列 | BreezeFrontendPipeline；命中继续流水，指令payload保持到backend ID接收，global/EX错误解析清年轻owner |
| ID/EX/MEM/PERM/WB及结果后台 | 当前BreezeBackend（BE2-P）；ID预留，EX只允许L1D入口不接收导致等待，MEM/PERM无条件推进，WB有界判定，W2/长结果自主保持 |
| 指令预测旁带 | eventPc为末parcel地址，10位historyBefore/phtIndex、RAS push/pop及预测方向/目标随原始指令进入EX，不能用解析当时的前端当前历史替代 |
| EX解析及训练 | ID从4项反馈容量预留；EX解析fire立即处理错误路径和GHR/RAS恢复，训练记录保持到PHT/BTB均可接收；训练队列满只挡新ID，不挡EX或WB |
| 反馈取消 | 全局恢复释放尚未到EX的预留；已经合法接受的训练保留。与旧C2一致，EX同拍WB kill不产生新的解析记录 |
| 提交GHR | 只使用WB确实提交的条件分支及保存的actual taken，不用nextPC与fallthrough是否相等猜实际方向 |
| 全局重启 | 后端给出异常/中断/replay/xret/SATP/FENCE.I/SFENCE及权限CSR写的重启；跨一个寄存边界，与新CSR context及permissionEvent同拍到前端 |
| FENCE.I及SFENCE | 明确restart PC；后端SFENCE观察脉冲仍在WB，生产MMU刷新与全局restart一起寄存一拍；前端block覆盖retry/deferred翻译准入，维持BE2-P条件排空；不改变MMU内部刷新与PTW drain规则 |
| MMU/iTLB/dTLB/PTW | 每核使用新前端内的一份Sv39Mmu；导出真实dTLB及独立mmuIdle。标量L1D与RVV翻译仲裁器接其dTLB；PTW经实际L1D物理读口，不能旁路到L2 |
| 指令refill | 新VIPT ICache→原L1IClient→共享L2或RVV dataless home；保持已接受miss归属和错误返回、owner probe语义，预取关闭 |
| HPM | F0 accepted query计ICache access，真实demand refill脉冲计miss；L1D及BE2事件继续接原CSR HPM |

正常路径是前端instruction握手进入ID，下一拍到EX，随后MEM/PERM/WB，算术及命中指令可以交叠。多个条件分支使训练端饱和时，已分配反馈记录继续被EX填写，PHT/BTB最终释放记录；后续ID等待注册的可用容量，不给主流水级添加状态机或全局保持。

自行裁定：使用4项注册反馈记录和ID预留来适配新前端Decoupled训练接口，避免把ready接到EX；权限CSR写时增加前端全局restart，避免旧权限查询残留。保留已有后端时序测试的只读观察输出，生产接线使用新resolve/restart/committedBranch，不再使用旧BTB/PHT/GHR脉冲或旧frontend boundary。

新的默认GShare history为冻结前端设计的10位；所有产品共用该配置。配置断言从旧默认8更新为10，属于获授权的新前端配置接入，不改变程序/golden/seed/watchdog。显式baseline配置的功能与新接口宽度仍须验证。

## 删除范围与验证

生产接线删除旧BreezeFrontend、BreezeInstrRealigner、BreezeFetchTranslator、FetchTlbClient、FetchBuffer及BreezeFrontendBoundary，及仅为这些旧模块存在的生成/测试入口。旧L1ICache/BTB/PHT在确认无新路径引用后删除；保留L1IClient、新VIPT cache/new predictor、RVC decoder和共用算术/存储原语。当前唯一后端为重构后的BreezeBackend，不另留旧执行后端。

先执行整核吞吐、后端定向合同与必要ISA/异常/kill/Sv39、多核及RVV访存门禁；不放宽前一轮新增0.5 IPC诊断门槛或已有golden。三配置的RTL/BIOS、routed timing、bitstream与上板结果分别记录，旧时序不能算作新顶层通过。

删除覆盖映射：旧前端/realigner/FetchTlbClient/cache/predictor的14份模块测试随9份废弃RTL移除；混合RVC、跨页第二parcel异常、SFENCE重映射、PMP、权限变化、取消中的refill及历史/RAS恢复由保留的`BreezeFrontendPipelineSpec`覆盖，预测存储/训练冲突由`BreezePipelinePredictorSpec`覆盖，MMU/PMP本体测试保留。`L1IRegisteredReturnSpec`迁移到真实新前端+原L1IClient，仍核对原始地址/指令数据和flush不安装旧返回；仅将被删cache的内部固定拍数替换为新F0/F1/F2的有界交付等待，L1IClient的E+1注册返回断言未变。另移除`BackendSoc3cSpec`中唯一仅实例化旧两项skid的入口（其余三项后端D1/C1测试保留）：新前端的`instruction output remains stable while stalled and resumes in order`、随机反压/RVC及真实整核分支/访存程序承担输出保持、顺序及恢复检查；旧skid的两项容量和内部拍数不适用于新八项instruction队列。未删除或放宽ISA/程序golden、后端合同、seed、scale及watchdog。新增`BreezeResolutionReservationSpec`直接压满ID反馈预留，持续条件分支造成实际训练反压，并核对global取消只释放未到EX的预留、已经接受的训练只消费一次。

## 首轮生产接入证据

源SHA `d71b63836ab381be5393125a8bd59870ff65d786`，实际主机Alan（首选cloud连接超时后完成Alan工具/资源/代理预检），cwd `/home/chen/FUN/flow-pipeline-integration-20261012`。执行`bash /home/chen/FUN/flow-runs/be2-env.sh bash records/be2-pipeline-probe-20261012/run.sh <SHA> <cwd> /home/chen/FUN/flow-runs/pipeline-integration-d71b638-20261012`，exit=0。两个suite共2个test，failure/error/skip均为0；[完整日志与CSV/XML/ELF](../../records/be2-pipeline-probe-20261012/d71b638)保留，[审计](../../records/be2-pipeline-probe-20261012/d71b638/analysis.json)核对每条暖窗口写回值、PC顺序及EX/WB不同指令同拍交叠。

| 测量窗口（两次预热后64条） | 退休/窗口周期 | IPC | 不同指令EX/提交交叠次数 |
|---|---:|---:|---:|
| 独立32位ALU | 128/128 | 1.0 | 122 |
| RAW依赖32位ALU | 128/128 | 1.0 | 122 |
| 真实L1D命中load（四寄存器重复，含WAW） | 128/158 | 0.81012658 | 92 |
| 16位C.ADDI | 128/128 | 1.0 | 122 |

后端原生三项64条定向流均II=1/退休间隔1拍；整核原0.5IPC门槛未改而全部通过，暖窗口无cache miss/replay/L2/外部内存访问。此证据只覆盖1核Linux/RVC/MMU能力配置运行的M/Bare自检程序，不代表Linux用户态、四核、RVV、删除后最终源码或FPGA时序通过。后续删除后门禁单独补记。

删除后首轮编译源`f70f35664c471fb89b39edd6313450b64973ff8c`在Alan exit=1：`BackendSoc3cSpec`遗留一个旧`Soc3cSkidBackendFixture`引用。失败日志保留于`/home/chen/FUN/flow-runs/pipeline-minimum-f70f356-20261012.console`；这是删除遗漏，尚未执行运行时测试，不是主机不可用。后续源清理该入口后重跑。

接线复查补充裁定：预测训练和EX恢复必须分别持有自己的`kind`。引入训练FIFO后，原独立前端共用的`conditional`读取FIFO队头，不再代表当前EX解析指令。回归源`d6eb39e56fe0de0261f72f7e4827a0df5f9e407e`在Alan的独立cwd `/home/chen/FUN/flow-pipeline-recovery-repro-20261012`已复现：较老JAL训练排队、当前条件分支实际taken且before-history=5，恢复应为11，却得到5。原始失败保留`/home/chen/FUN/flow-runs/pipeline-recovery-repro-d6eb39e-20261012/`。修正训练类型只用于PHT/BTB入队，EX类型只用于即拍GHR恢复；追加反向条件分支→JAL恢复检查。保持FIFO容量、ID/EX协议及既有golden不变，后续同一回归核对修正。

部署裁定：保留用户已经要求的单核ILA、tandem及总线监控器。`build_sd.sh single <fresh-output> --prepare-only --debug`生成相应新流水前端的调试单核；四核和RVV使用原生产观察配置。收集器只在普通单核明确debug=true/tandem=true且匹配LTX/probe-map存在时允许这两个观察标记与其他产品不同，所有核心/缓存/时钟等资源字段继续严格一致；不更改CPU ISA/golden或时序门禁。新`bind-production.py`逐项核对不可变RTL快照的hash、必须出现新前端与BE2后端、不得含旧前端模块、SD coherent DMA及真实BIOS/ROM字节绑定，避免使用之前生成的旧CPU RTL。

## 删除后最低门禁与第一次物理结果

Alan源`5b2e9dc058f14bbadf4a3147adc60f2e5dd4e2b8`：`integration-minimum.sh <SHA> /home/chen/FUN/flow-pipeline-integration-20261012 /home/chen/FUN/flow-runs/pipeline-minimum-5b2e9dc-20261012`，exit=0，58/58，无failure/error/skip。[原XML与日志](../../records/be2-pipeline-probe-20261012/minimum-5b2e9dc)。其中B3审计用例通过表示仍可复现raw drained的历史反例，不能单独标为新CPU drain合同证明；真正的新路径依据生产串行断言和整核FENCE/SFENCE程序。

EX恢复修正源`feb0fca7240d6755634641f2056eaea64be05748`：同一reservation回归2/2 PASS，[修正前2/2 FAIL](../../records/be2-pipeline-probe-20261012/recovery-d6eb39e)与[修正后](../../records/be2-pipeline-probe-20261012/recovery-feb0fca)均保留。`system-minimum.sh <SHA> /home/chen/FUN/flow-pipeline-recovery-repro-20261012 /home/chen/FUN/flow-runs/pipeline-system-minimum-feb0fca-20261012`，exit=0，11/11；包含整核吞吐2项、保留的后端SOC3c 3项、单核/四核最小ISA各10个程序、单核7个系统程序及四核6个系统程序（含seed=7 AXI反压再执行一次，共19），RVV配置14个原标量ISA程序及3个向量程序。所有原tohost/内存值/console/seed/watchdog保持。[原证据](../../records/be2-pipeline-probe-20261012/system-feb0fca)及[吞吐审计](../../records/be2-pipeline-probe-20261012/system-feb0fca/throughput/analysis.json)确认1.0/1.0/0.81012658/1.0 IPC与首轮一致。旧58项中的standalone/后端源保持不变，新增连接模式修正独立复测；不把不同源的测试合并成同一SHA全量通过。

物理输入源`ab5c0b2d3289c457169ced0813597f0313e61557`与feb0fca的CPU RTL/config/LiteX桥/target差异为空，仅交付脚本改变。Alan cwd `/home/chen/FUN/flow-pipeline-integration-20261012`，输出 `/home/chen/FUN/flow-runs/pipeline-ab5c0b2-20261012/{four,single,single-rvv}`；三套RTL/BIOS生成和逐项快照/ROM绑定exit=0。随后`build_sd.sh four <four> --vivado-only`、`build_sd.sh single <single> --vivado-only --debug`在Vivado2022.2/100MHz/xcku040-ffva1156-2-e执行：四核综合LUT260026/242400（107.27%），FF114058、BRAM108.5、DSP92，D5 PASS但place前UTLZ-1拒绝、exit=1、没有bit；单核综合LUT86114（35.53%）、FF58760、BRAM264.5、DSP23，D5查找不到L2 data的原层级、exit=1、没有bit，也无routed时序结论。旧DCP/日志保留。单核DCP只读查询确认8个data BRAM被重建到设计根`data/ram/mem_reg_0..7`；追加根路径探测仍调用原exclusive registered address-owner检查，不改变BRAM门槛。

构建入口后续源72676d3防止旧生成物误用：新一次生成在目标目录的独立emitted/UUID内输出，manifest仅列本次实际SV；LiteX拒绝缺少新前端/BE2后端或混入旧阻塞前端。Alan `python -m unittest discover -s sim/litex -p test_breeze_cpu_wrapper.py -v`，7/7 PASS、exit=0，日志`/home/chen/FUN/flow-runs/pipeline-wrapper-72676d3-20261012/`。这是构建入口验证，不能当作完整SoC仿真。

### 保留原 TLB 的四核收敛方案评估（用户暂停构建后，只读分析）

本节是后续候选方案，不代表已经修改 RTL 或恢复 TLB 参数。当前硬件候选仍是 36fddea；暂停后没有启动新的综合/布局布线。使用 Vivado 2022.2 在 Alan 只读打开 ab5c0b2 的四核 synth DCP，执行 `flow-four-resource-audit-20261012.tcl`，exit=0。脚本、原综合报告及查询输出保存在 [resource-audit-ab5c0b2](../../records/be2-pipeline-probe-20261012/resource-audit-ab5c0b2/)。

原层级报告四核合计：后端 132058 LUT（包含 CSR/PMP 52167 LUT）、MMU 32971 LUT、L1D 35058 LUT、L2 6780 LUT；ICache 返回队列归属 18587 LUT，后端 lateQueue 归属 3501 LUT。这些是综合重建层级的资源归属，不是各源代码模块的独立成本。返回队列的网表层级除 LUTRAM 还包含大量 LUT 和 CARRY8，CSR 层级也含以 Memory_reg 命名的逻辑；应追踪实际组合锥与端点，不能承诺换掉两项存储即可省下 18587 LUT。查询中的宏单元和内部子单元也不能直接相加作为物理 LUT 利用率。

推荐候选顺序：恢复原 iTLB/dTLB 的 8sets×4ways+4superpage 与 walk-cache 4ways，先保留现有 cache 容量；核实并优化宽浅返回存储、CSR/PMP 译码/更新与写回选择的真实组合锥。保持 FIFO 容量、pipe/flow、反压稳定、同沿入出队、取消和精确异常语义，比较整个四核资源而非某一重建层级。只有测得这条路径收益不足时再试 cache 参数候选：L1 sets 64→32 保留两路（各 4KiB→2KiB），或者减少 ways；L2 减 ways 必须同时记录 sets/容量，避免仅减少字节数却留下主要比较和仲裁逻辑。所有容量候选须覆盖冲突替换、dirty writeback、probe、PTW、LR/SC/AMO 以及 SD coherent DMA 的原 golden。

面积规划目标可取 85%–90% LUT，作为给布线留空间的工程目标而非通过保证；以原 260026 LUT 为基线，90% 对应 218160 LUT，需要净省 41866 LUT。面积达到候选目标后才用实际 routed 路径决定时序修改，依次区分 F1 TLB/PMP/tag、后端 hold/ready/写口控制、L1D/L2 仲裁与高扇出布线；不以加级、降频或修改约束掩盖问题。保留 100MHz、精确异常、程序顺序、无死锁及原 golden。四核和派生单核独立工程并行实现，单核不独立调整基础参数。

### 用户确认执行后的第一轮等价面积候选

恢复部署默认完整 `Sv39MmuParams()`（i/dTLB 各 8sets×4ways+4superpage，upper/middle walk-cache 各 4ways），同步恢复严格 LiteX marker；四核与两种单核继续同源派生，L1/L2 容量及路数不变。ICache 两项返回和 Writeback 两项 late 返回改为 `RegisteredPairQueue`：两个独立不复位数据寄存器、复位 ownership、非 flow；前者 pipe=true、后者 pipe=false，与原 Queue 同容量和同拍接口。F1 已预留响应容量、F2 反压保持、满队列同沿出入、late.ready 只读寄存 occupancy、取消边界及 E+1 返回合同均保持。新增软件 FIFO model 与原 Chisel Queue 的逐拍对照，覆盖 282-bit 数据、满/空、反压、同时入出及 clear。

PMP 范围计算采用恒等式 `(boundary & ~byteMask) + byteMask == boundary | byteMask`，替换 NAPOT/NA4 上界中一次宽加法，保留完整 65-bit 范围和 58-bit blockPrevious。新增独立整数区间模型覆盖 OFF/TOR/NA4/NAPOT、最大全 ones 编码及零下溢；复用原随机权限 oracle。资源收益待整体综合实测，不承诺层级归属的 LUT 全部消失。新最小验证入口 `resource-minimum.sh` 保存源/主机/工具/XML，选择原 T11/T12/T21/T22、精确异常、FENCE/SFENCE 和前端恢复合同，不改原 golden、seed、scale 或 watchdog。

旧 36fddea 的系统最小回归已在 Alan exit=0（包括新增 SFENCE AXI 反压程序、原四核程序及 RVV），它是本轮改动前的基线证据，不作为新候选通过证明。

### 四核资源调整裁定（先前缩 TLB 的候选，已由用户决定恢复）

第三轮裁定：564961c 的四核 synth LUT252977（104.36%），相对 b59e9be 多622，place 前 UTLZ-1 exit=1；故将 PHT 有效位读选择与 PMP 前缀/空区间计算恢复到 b59e9be，保留新增独立模型测试。b59e9be 调试单核完成 route/phys_opt，但完整 gate WNS=-0.523ns、TNS=-380.233ns、1703 setup failures，WHS=+0.004ns、WPWS=+0.103ns，exit=1，不生成 bit。最差 sys_clk 路径为返回槽 `first_accessFault_0_reg/C` 至 `refillData_reg[23]/CE`，10.255ns data（logic3.177/route7.078）、26级（9 CARRY8），而非纯队列存储延迟。

cache 后备方案由四核所有：L1D 各2KiB/64sets/1way，ICache各4KiB/64sets/2ways，L2仍4ways/per-core32KiB，完整 TLB/walk-cache 不变；普通单核和 RVV 单核同样派生。只减 D-cache 容量/相联度，不改 ISA、MMU/PMP 或黄金结果；需原程序覆盖冲突替换、dirty/probe、LR/SC/AMO/PTW 及 coherent SD DMA。默认/独立验证几何未改。

指令队列端口裁定：仍8项、原子0/1/2入队、单项出队，按位置奇偶分两组。连续两项分别写不同组，每组仅一套数据选择/行写译码；仅一项时只写对应组，满队列同时出入在沿前消费旧头。head/tail/count、反压与clear规则不变；clear阻止同沿入出且仅清ownership。新增4/8/16项软件FIFO模型逐bit核对任意289-bit记录、满替换、跨尾环绕、零项包与clear，避免依赖生产指令数据的特例。

按实测路径预计算 startPc+0/2/4/6/8，再由长度选择PC/eventPc/RAS返回地址，代替长度/fault选择后的连续宽加法；64-bit溢出、跨页原始PC、RVC/RV32及栈旁路结果保持。refillData只由refillRsp.vld驱动写入；原 killed/error/cancel 条件仍只授权refillWrite，后者在下一沿安装。无资格响应的私有payload不会安装；在install沿数据RAM仍采样旧已授权payload；fence.i优先清valid。此项去除F2 cancel至256-bit payload CE扇出，不延迟cancel，不增加级，不放宽错误或取消golden。

后续 b59e9be 保留完整 TLB/cache 的第一轮：Alan 最小组件/合同回归 56/56、构建接口 7/7，exit=0；三套 RTL/BIOS prepare/binding exit=0。四核与带 ILA 单核并行 Vivado 2022.2：四核 synth LUT252355（104.11%，相对 ab5c0b2 净省7671）、FF116893、BRAM108.5、DSP92，仍在 place 前因 UTLZ-1 exit=1，无 routed/bit；单核 synth LUT84177（34.73%）、FF59401、BRAM264.5、DSP23，继续实现，尚不宣称时序通过。PMP 新测试首次长组合扫描遇到 svsim 双向 pipe 写阻塞（Java stack 保留在 console），测试样本/期望值不变，b59e9be 增加每组同步 peek 后通过；此问题不作为 RTL 死锁或主机不可用证据。

第二轮仍保留完整 TLB、cache、10-bit history/1024 PHT 和 bank 数。PHT 初始化有效位按同一 counter bank/row 查询，共享给该 bank 的训练和所有已获准同索引预测，未获准查询仍由 available 屏蔽；减少四次全表动态读选择，不改变初值、更新、冲突或同址旁路。NAPOT mask bit j 用低 j 位全一前缀表达 `addr ^ (addr + 1)`；NA4/NAPOT 的大小在 56-bit aperture 内恒正，只让 TOR 比较前后编码地址，OFF 仍空。保留原两个预测器测试，新增 1/4/8-bank 独立整数 counter model 检查不同 bank/row 和同 bank 冲突；复用全部 PMP 整数区间/随机权限模型。资源与时序收益仍待新源综合。

实际面积超限后将部署iTLB/dTLB各由8sets×4ways+4superpage entries改为4sets×2ways+1superpage entry；PTW upper/middle walk-cache从4ways改为2ways（sets仍1/2）。保持16位ASID、4KiB/2MiB/1GiB页型、Svade/PMP/权限、共享PTW接口和F0/F1/F2级数。只缩容量、不裁指令或权限。四核为配置所有者，普通单核和RVV单核由其copy派生；profile新增MMU参数以防单核独立调参，并输出各项到cluster-profile。

另采用已有的32位物理总线边界缩小VIPT物理tag及候选PMP比较输入：原PMA早已以完整64位selected PA拒绝高位非零地址，且在cache hit/refill前阻止授权。对所有可授权PA，缩小前后完全相同；对越界PA，原完整PMA仍必须返回access fault，不能通过截断别名执行低地址行。PMP仍比较完整CSR范围、不改变8项PMP或任何地址/异常接口。新增“预热低地址→Sv39重映射到相同低位的高PA”自检，要求准确access fault、raw=0、不产生refill，验证不能借缓存别名绕过拒绝。现有PMP随机oracle与程序golden保持。

SFENCE生命周期补充裁定：连接后端时，translationBlocked不只阻止fresh F0，还必须阻止已保存的retry/deferred重新进入iTLB；否则MMU空闲后，等待Icache返回的旧fetch可能重新启动翻译。缓存已接受的refill继续独立排空，后端串行等待使用真实MMU idle而非frontend local idle，避免等待被暂停的retry自身造成死锁。独立前端的block-drain行为不变。生产SFENCE控制与全局restart同时寄存到下一拍，防止WB先撤掉iTLB响应而cache尚未取消F1查询；WB观察脉冲/原后端拍号不变，restart gap内没有年轻ID发射。追加原sv39_basic/sv39_ptw_cache在单核seed=7 AXI反压下的原样程序检查；这些是接线规则补充，尚待新源最低门禁，不宣称已复现运行时失效。
