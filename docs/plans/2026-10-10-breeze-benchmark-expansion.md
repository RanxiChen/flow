# Breeze 后续负载扩展计划

记录日期：2026-10-10。

用户决定：保留现有 CoreMark、GAPBS，后续把本次讨论的六类负载都加入。
本次仅记录计划；尚未下载、移植、编译或运行这些新增负载。以下顺序为建议，
不构成当前启动实现、仿真或板级运行的任务。

## 待接入清单

| 状态 | 负载 | 源码入口 | 接入目标 | 建议顺序 |
| --- | --- | --- | --- | --- |
| 待接入 | MiBench | https://github.com/embecosm/mibench | 先选择整数应用，补充图像、通信、加密等程序行为 | 第一批 |
| 待接入 | LLVM test-suite | https://github.com/llvm/llvm-test-suite | 选择公开源码中的压缩、解释器、搜索、指针密集程序，扩大控制流与访存覆盖 | 第一批 |
| 待接入 | PolyBench/C | https://github.com/MatthiasJReisinger/PolyBenchC-4.2.1 | 先选择矩阵乘、LU、Jacobi 等 C 内核，补充浮点和规则访存 | 第一批 |
| 待接入 | Embench IoT | https://github.com/embench/embench-iot | 小型嵌入式程序集合，适合裸机与 RTL 仿真回归 | 后续批次 |
| 待接入 | NAS Parallel Benchmarks | https://www.nas.nasa.gov/software/npb.html | 优先评估 3.3.1 串行版本、Class S，补充科学计算 | 后续批次 |
| 待接入 | llama2.c | https://github.com/karpathy/llama2.c | 配套小模型，增加端到端推理负载，观察计算与权重访存 | 后续批次 |

“全部加入”指六类负载均纳入接入工作，不预先要求每个上游套件中的全部程序都运行。
具体程序、版本、输入规模及适用平台在对应接入任务开始时确定。

## 现有基础

- CoreMark：现有基线，继续保留。
- GAPBS：已有 Buildroot 包，见
  [flow-gapbs.mk](../../linux/buildroot-external/package/flow-gapbs/flow-gapbs.mk)。
  已有板级运行记录见
  [Breeze GAPBS 首轮板级结果](../benchmarks/breeze-gapbs-20260918.md)。
  该历史记录的源码、平台、正确性验证与性能运行边界以原文为准。

## 后续接入要求

1. 固定上游版本或提交、源码来源、许可证、构建参数、目标 ISA/ABI，以及输入数据。
   LLVM 的 SPEC 外部套件仍需另行提供原版源码，不计入此次公开源码候选。
2. 为选定程序准备可重复输入和结果校验；先验证执行结果，再进行性能测量。
   浮点结果采用明确的校验方法与误差容限。
3. 区分裸机、Linux、RTL 仿真和 FPGA 板级适用范围；小规模验证与正式测量分别记录。
4. 单核基线显式固定线程数。程序初始化、输入生成、文件读写和计算阶段分别计时，
   避免把整个进程耗时直接当作算法内核耗时。
5. 记录核心源码 SHA、软件版本、执行命令、退出状态和证据位置；按运行环境能力
   收集时间、cycles、instret、IPC 与可用 HPM 指标。
6. 每次开始仿真及其编译、RTL 生成前，重新读取
   [共享仿真主机配置](../cross-project/simulation-host.md)，先校验 cloud_chen，
   不可用时校验 Alan；Vivado 使用 Alan。记录实际执行主机。

公开源码可获取不等于已在 Breeze 上可运行。本清单的所有新增项当前均为待接入，
不代表已经获得编译、正确性或性能验证证据。
