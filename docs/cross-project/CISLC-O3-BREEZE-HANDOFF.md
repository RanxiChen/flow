# CISLC-O3 读取 Breeze 的入口

更新：2026-09-19。本文是跨仓库交接说明，不代替两边的架构规格，也不声称会话自动共享。

## 先读这里

Breeze 仓库：`/home/chen/leisure/flow`，本次读取 main `4edfbc3`。
CISLC-O3 仓库：`/home/chen/work/CISLC-O3`，本次读取 develop `06462b0`。
后续读取时先核对 HEAD 和工作区状态；下述运行状态是本次快照。

用户当前方向：保留已跑通 Linux 的 Breeze 第一代，先推进 CISLC-O3，再回看顺序核优化。
可以借鉴、翻译 Breeze 模块为 SystemVerilog；这不是把顺序后端整体翻译成乱序后端。
不因这份交接自动引入 MMU、FPU、多核或 Linux 到 O3，也不自动搬运代码。

O3 的权威入口：

- `/home/chen/work/CISLC-O3/agent.md`：协作与修改规则。
- `/home/chen/work/CISLC-O3/doc/CISLC_O3.md`：后端当前状态、接口和时序。
- `/home/chen/work/CISLC-O3/doc/CISLC_O3_frontend.md`：前端当前状态。
- `/home/chen/work/CISLC-O3/doc/CISLC_O3_ARCH_SPEC.md`：架构目标和实现边界。
- `/home/chen/work/CISLC-O3/sim/o3/README.md`、`verification/act4/README.md`：测试入口。

O3 规则要求按用户指定位置，沿指令路径逐段定义职责、不变量、接口和周期行为，再实现并同步文档。
当前阶段不能为维持旧测试而擅自扩大范围。本文不更改这些规则。

## 已读到的 O3 边界

项目定位是 M-mode、物理地址直通的计算引擎；CSR 尚未接入，不能理解为完整特权架构已实现。
四宽取指/译码/重命名/提交，96 项 PRF、64 项 ROB、4 个 checkpoint。
当前状态文档描述 Integer/Memory/Branch 三类 IQ（16/8/4 项），整数执行通路四路，访存与分支各单发射。
规格的概览仍有“统一整数 IQ”表述，涉及实现时应核对详细状态文档与 RTL，不能只引用概览。

已有部分 RV64I、LSU、分支恢复及统一仿真内存路径。文档记录 ACT4 RV64I-I 51/51，
此次没有重跑，不代表完整 ISA、异常或内存序通过。FENCE 屏障语义未闭合，M 扩展待接入，
A 扩展待实现；F/D/C 不在当前规划，D-cache 未实现。
前端有 FTQ 和 redirect 闭环，但预测仍为顺序 not-taken，无 BTB/BHT/RAS。

## Breeze 已有能力

已发布单核 KCU105：100 MHz、RV64IMAFDC + Zicsr/Zifencei、2 GiB DDR4。
五级顺序单发射 IF/ID/EXE/MEM/WB；MEM 发起乘除、浮点和访存，并阻塞等待结果。
M/S/U、Sv39、I/D TLB 各16项、硬件 PTW、8项有效 PMP、异常委托、Sstc。
每核 L1I/L1D 各8 KiB、4路、32 B行；共享 L2/Home 单核16 KiB、四核64 KiB。
L1D 为阻塞式 MESI 写回/写分配缓存，含 LR/SC/AMO；SD DMA 接入一致性 Home。
JTAG FASE 支持 halt、寄存器访问和指令注入，硬件记录器用于保存历史现场。

上板已验证 Buildroot musl、Linux、Python 和六项 GAPBS 小图自检。
BFS/BC `-g 18 -n 50` 平均为3.03940/42.47229秒；大图未加-v，线程环境未独立记录。
Linux 使用 RAM 根文件系统；SD 由 BIOS 加载启动文件，Linux SD-root 尚未成功，网络未验证。

发布入口：https://github.com/RanxiChen/flow/releases/tag/breeze-kcu105-20260918
硬件提交29ee514，软件提交b2a895b；不是后续所有修改都已上板。
详细参数及图见 `/home/chen/leisure/flow/README.md`；原始数据见 `docs/benchmarks/breeze-gapbs-20260918.md`。

## 复用地图（以下路径相对于 Breeze 根目录）

| 内容 | 源码入口 | 复用边界 |
| --- | --- | --- |
| 整数乘法 | `design/src/main/scala/multiplier/RiscvMulUnit.scala`、`SignedMul65x65.scala` | 65×65有符号实现，报告延迟3拍、启动间隔1；O3需加标签、回压与冲刷合同，核对原握手 |
| 整数除法 | `design/src/main/scala/divider/RiscvDivUnit.scala`、`UnsignedRadix4Divider.scala` | 借鉴运算及边界条件；明确在途除法被冲刷后的结果丢弃和单元释放 |
| 浮点 | `design/src/main/scala/fpu/BreezeFp.scala`、`design/src/main/resources/vsrc/fpnew/FlowFpnewWrapper.sv` | 用户希望保留IP内部；O3当前不规划F/D，不主动接入；以后需处理舍入模式、fflags的提交语义 |
| 指令语义 | `design/src/main/scala/core/InstDecode.scala`、`backend/BreezeBackend.scala`（后者位于scala下） | 可核对指令边界行为，不能复制顺序流水线的控制所有权 |
| 分支预测 | `design/src/main/scala/frontend/BreezePHT.scala`、`BreezeBTB.scala` | GShare 8位历史、256项PHT、16项BTB；O3需适配FTQ、块内分支及推测历史恢复 |
| L1D | `design/src/main/scala/cache/BreezeDCache.scala` | 可以作为阻塞式缓存起点；O3 LSU负责年龄/提交/错误路径副作用，不能让推测store直接落入缓存 |
| L2及一致性 | `design/src/main/scala/cache/BreezeL2Home.scala`、`top/BreezeMulticoreClusterWishbone.scala`（scala下） | 协议、probe进展、原子性一起迁移；不要只翻译数据阵列 |
| SRAM | `design/src/main/scala/mem/sram_1r1w.scala`、`sram.scala` | 同步读、读写冲突及掩码语义需明确；FPGA映射要重新综合确认 |
| MMU/PMP | `design/src/main/scala/mmu/` | 作为未来参考，O3当前物理地址架构不需要自动引入 |
| CSR/计数器 | `design/src/main/scala/core/RegFile.scala`、`BreezePerformanceCounters.scala` | 顺序单退休事件不能直接用于四宽提交；CSR副作用和异常需归属提交阶段 |
| FASE/记录器 | `design/src/main/scala/fase/`、`litex_wrapper/flow/fase.py` | O3 drain需覆盖ROB、LSQ及在途FU；不能直接套用顺序MEM排空条件 |
| 验证参考 | `design/src/test/scala/`、`verification/act4/`、`sim/fase/` | 复用测试意图和边界用例，SV实现必须按自己的接口重新验证 |

翻译前先记录输入输出、接受条件、延迟/启动间隔、状态所有权、复位、flush、异常与副作用。
运算模块输出携带O3事务身份；年轻结果不得更新已回收的PRF/ROB entry，编号复用和晚返回必须处理。
译成SV不等于功能已验证；也应检查第三方IP许可证与来源，保留原版权声明。

## 面积和时序证据

发布单核布局后：68,170 LUT（28.12%），16,143 CLB（53.28%），47,434 FF，439.5 BRAM等效块。
其中记录器13,578 LUT、342 RAMB36；FPU12,401 LUT；D-cache7,095 LUT；乘法5,516 LUT。
LUT百分比不是占用CLB百分比；单核调试版本也不能直接乘4估计四核。

第一代四核综合：183,847 LUT（75.84%）、99,067 FF、197.5 BRAM等效块、44 DSP。
该配置包含SD/DMA，不含FASE/记录器/ILA。布局布线已启动，本文件尚未查验最终结果；不能宣称100 MHz通过。
这些是Breeze证据，不是CISLC-O3面积、频率或性能证据。

## 尚在运行的任务与检查位置

Alan仓库 `/home/chen/FUN/flow`，本次可用SSH为 `chen@192.168.8.88`；连接方式以后需重新确认。

- 四核实现：`build/fpga/kcu105-4core-sd-100mhz-9c10f0f-synth/implementation-driver.log`、`implementation-exit-code.txt`。
- 独立perf镜像：`build/sd-perf/build-current.log`。配置提交4edfbc3；最后确认处于依赖构建，尚未验证最终产物或板上计数。
- perf沿用现有bitstream，添加DT原始事件映射和Buildroot perf工具；细分FPU等待等硬件事件尚未增加。

不要因为进入O3项目而自动停止、清理或重启这些任务。读取运行状态和修改任务是不同操作。

## 给 O3 助手的使用方式

先读本文件，再读O3自己的状态文档和agent.md，最后只打开当前任务需要的Breeze源码。
不要把Breeze候选的“顺序解耦/双发射”讨论当成O3已经批准的新架构。
需要共享新结论时，在对应项目的权威文档落盘，更新本文件的链接或边界；不要依赖另一会话自动获知聊天内容。
