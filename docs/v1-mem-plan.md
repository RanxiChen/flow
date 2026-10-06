# v1 访存子系统：先骨架、后仿真迭代

用户决定（2026-10-06）：先跑通，再完善。正确性靠仿真判定，不做纸面审计轮次。分支 `feat/v1-mem-skeleton`，工作目录 `/home/chen/flow-mem`，与后端分支 `feat/pcie-fase-20260920` 并行，完成后合并。

## 步骤

| # | 内容 | 负责 | 状态 |
| --- | --- | --- | --- |
| 1 | RTL 骨架：L1D、L2 的流水级、阵列、寄存器、状态机、协议 Bundle；能 elaborate；功能留 `TODO` | Claude | 完成：五种几何配置均能 elaborate（`MemSkeletonElabSpec`） |
| 2 | 扫描现有代码，补外部接口：MMU/PMP/PMA、L1I 客户端、集群、LiteX 外壳、事件 | codex | 任务书 [`tasks/V1-MEM-interfaces.md`](tasks/V1-MEM-interfaces.md) |
| 3 | 审核接口 | Claude | |
| 4 | 小测试与时序约定：单核 hit/miss/写回/升级；L1D 接行为级 L2，L2 接行为级 L1 代理 | Claude 写测试，codex 跑和修 | |
| 5 | 校验设施：黄金内存逐 load 比对；SWMR 与目录一致性监视器；看门狗 | Claude | 与第 4 步并行 |
| 6 | 压力测试：`BreezeMemGeometry.stress` 极小 cache，多核随机；再跑 litmus | Claude 写，codex 跑 | |
| 7 | 一边改一边完善状态机，消化 `TODO`，spec 随之更新 | 共同 | |

## 规则

- 骨架任何时候都必须能 elaborate；`MemSkeletonElabSpec` 是门槛。
- L1D/L2 的 spec 降为参考文档，不进冻结清单。仿真与 spec 冲突时，先判断哪个是对的，再改另一个。
- 后端接口 `L1DCoreIO` 仍然冻结（`interface/L1DCoreIO.scala`），骨架直接复用，不再定义第二份。
- 几何参数暂放 `config/BreezeMemConfig.scala`（`BreezeMemGeometry`），以免与后端分支在 `config.scala` 冲突。合并时挂到 `BreezeClusterConfig.mem` 下。

## 骨架文件

| 文件 | 内容 |
| --- | --- |
| `config/BreezeMemConfig.scala` | 几何参数与约束；`default`、三种非默认冒烟配置、`stress` |
| `coherence/CoherenceParams.scala`、`CoherenceBundles.scala` | 推导常量；四条链路的消息、L1/目录状态 |
| `bus/Axi4.scala` | AXI4 与 AXI4-Lite 主口 |
| `l1d/L1DParams.scala`、`L1DBundles.scala` | 推导常量；顶层 IO、流水寄存器、MSHR/写回/probe/MMIO 的 Bundle 与状态枚举 |
| `l1d/L1DCache.scala` | S0 仲裁与冲突检查、阵列读写、S1 保持安全捕获、S2 判定、PS、resp/s2Hold/late、链路 |
| `l1d/L1DMiss.scala` | MSHR（含 LATE）、写回槽、Get/Put、RSP↓ |
| `l1d/L1DProbe.scala`、`L1DMmio.scala` | probe 接收、整行读与答复；阻塞 MMIO |
| `l2/L2Bundles.scala` | meta、流水项、分类动作、槽、probe 答复与 Put 缓冲 |
| `l2/L2Home.scala` | 端口、入口（S2 握手）、S0–S2 主流水、快路径、Put、输出 FIFO、阵列初始化 |
| `l2/L2Slots.scala`、`L2ProbeEngine.scala`、`L2MemEngine.scala` | 慢槽状态机；probe 引擎；AXI4 读与写缓冲 |
