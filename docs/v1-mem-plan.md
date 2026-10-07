# v1 访存子系统：先骨架、后仿真迭代

用户决定（2026-10-06）：先跑通，再完善。正确性靠仿真判定，不做纸面审计轮次。骨架分支 `feat/v1-mem-skeleton` 已合入 `feat/pcie-fase-20260920`，后续在当前分支与 `/home/chen/leisure/flow` 实现。按事务闭环分批：先提交 RTL，再提交对齐 spec 的 directed 小测试，然后逐步扩大验证。

## 步骤

| # | 内容 | 负责 | 状态 |
| --- | --- | --- | --- |
| 1 | RTL 骨架：L1D、L2 的流水级、阵列、寄存器、状态机、协议 Bundle；能 elaborate；功能留 `TODO` | Claude | 完成：五种几何配置均能 elaborate（`MemSkeletonElabSpec`） |
| 2 | V1-BE 完成后合并本分支；先做命名清理、删除旧实现，再扫描现有代码补外部接口：MMU/PMP/PMA、L1I 客户端、集群、LiteX 外壳、事件 | codex | 已提交，最终接口提交 `71f4988`；报告 [`tasks/V1-MEM-interfaces-report.md`](tasks/V1-MEM-interfaces-report.md) |
| 3 | 审核接口 | Claude | 未记录独立审核结论；当前原生 cluster 已接 Backend/L1D/MMU/L2 |
| 4 | 小测试与时序约定：单核 hit/miss/写回/升级；L1D 接行为级 L2，L2 接行为级 L1 代理 | Claude 写测试，codex 跑和修 | Alan `4f81993` 模块 88/88（驱动 10/10、L1D 24/24、L2 12/12、骨架 37/37、权限 5/5）、真实单核 L1D + L2 系统 10/10，两个命令均 exit=0。已修 L1D 写掩码、L2 单 set 行地址、shared Store 等 MSHR 的 probe 死锁及行为 L2 同行 probe/Get 接受顺序；既有断言、黄金期望、随机规模和 watchdog 保持。报告 [`tasks/MEM-single-core-tests-report.md`](tasks/MEM-single-core-tests-report.md)，任务书 [`tasks/MEM-single-core-tests.md`](tasks/MEM-single-core-tests.md) |
| 5 | 校验设施：黄金内存逐 load 比对；SWMR 与目录一致性监视器；看门狗 | Claude 写，codex 修和跑 | 黄金内存、协议行为模型、看门狗已写（`memsys/MemTestKit.scala`、`MemAgents.scala`）；Alan `4f81993` 独立驱动回归 10/10；三个 spec 的成功用例公共收尾均强制最终内存检查，L1D/L2/系统均已通过。多核 SWMR 监视器待第 6 步 |
| 6 | 压力测试：`BreezeMemGeometry.stress` 极小 cache，多核随机；再跑 litmus | Claude 写，codex 跑 | |
| 7 | 一边改一边完善状态机，消化 `TODO`，spec 随之更新 | 共同 | 普通 Load/Store 与 L2 槽主流程已通过现有单核 directed/随机系统门槛，L1D §5.3 同步资源等待说明；原子路径及多核验证待完成 |

## 规则

- 骨架任何时候都必须能 elaborate；`MemSkeletonElabSpec` 是门槛。
- L1D/L2 的 spec 降为参考文档，不进冻结清单。仿真与 spec 冲突时，先判断哪个是对的，再改另一个。
- 后端接口 `L1DCoreIO` 仍然冻结（`interface/L1DCoreIO.scala`），骨架直接复用，不再定义第二份。
- 几何参数定义在 `config/BreezeMemConfig.scala`（`BreezeMemGeometry`），由 `BreezeClusterConfig.mem` 持有（2026-10-07 合并）；`BreezeCluster`、`BreezeClusterWishbone` 与生成器只接收 `BreezeClusterConfig`，L1D/L1I/L2/协议模块接收几何并自行推导。旧 `DefaultDCacheConfig`、`BreezeCoreConfig.dcache*`、`L2CacheGeometry`、`numHarts`/`hartIdWidth`/`sharerWidth`/`txnIdWidth` 已删除。

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
