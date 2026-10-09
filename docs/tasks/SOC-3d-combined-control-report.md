# SOC-3d 合并控制链候选

2026-10-09 用户授权四项一起实现，以尽快时序收敛为目标。基于主仓 `56c121eccc23e0f1f6854c32d1934e7b786f8337`，CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be` 保持。源目录 `/home/chen/leisure/flow`，分支 `feat/pcie-fase-20260920`。现有 Alan 三项 Vivado 输入/进程不修改，不开展逐项物理对照实验。

## 实现与拍数

- L1D tag 候选地址、way mask、payload 与各来源最终许可分开；install > probe > allocation > PS 独热授权，保持同拍 kill 和资源所有权。
- FpUnit 两项无 flow/pipe 输入 Queue，tag 在后端接受时分配；CVFPU ready 不进入后端 req.ready，commit 仍 EX+2，原始 CVFPU 最早 EX+1 fire。FP32/FP64 ADDMUL 原始计算均 5 拍，后端接受到结果 6 拍；输出仍直接仲裁。kill 保留已提交 queued 项，作废队头本地丢弃；killDrain 同时检查缓冲和 CVFPU 排空。
- tag 按 way 与最多 8 个 set 分组，局部读写译码；EX payload 按原 exAdvance 捕获，只 ex.valid 使用晚到 idLeave。FP→x0 flags ownership 用寄存计数非零，逐拍断言对照 metadata 表。
- time/stimecmp 用 8-bit 并行比较和均衡字典序树，仍为同拍组合 pending，不增加中断延迟。

本批按明确授权更新 backend-timing-contract T14/T16、结构例外与 backend-v1/l1d spec；没有修改整数、普通 Load/Store、WB/W2 提交拍数、MMU/一致性协议、100 MHz 时序约束。原数值 golden 和安全断言含义保留；旧 raw input 与 EX 同拍的检查改为明确一拍请求缓冲合同，不放宽为任意延迟。

## 功能门槛（待执行）

新增定向用例覆盖两项输入缓冲填满/排空、同拍提交/取消及 committed queued DIV 存活、全部 tag set/way 与替换脏行、每个 timer byte 边界/最高位/零/全一和写后重装。复用完整 FpUnit 子模块套件、后端拍数/异常/保持子集、缓存 kill/refill/probe/原子/非默认几何、真实多核一致性和短集群冒烟。仅相关子集，不跑全项目完整回归。执行主机 cloud_chen 优先，编译/RTL 生成/仿真前重读共享主机配置。

当前为实现记录，功能与时序均未验证；证据与最终 SHA 将在门槛完成后补充。
