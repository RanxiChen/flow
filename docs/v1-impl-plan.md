# Breeze v1 一次性实现计划与进度

本文是 v1 覆盖性重写的总入口和进度记录。新会话接手时先读本文，再读对应 RTL spec。用户决定（2026-10-06）：

- 在现有 Breeze 上**原地覆盖**，不另开目录；旧实现只保留在 git 历史和 tag `breeze-old-linux-20261006` 中。
- Claude 先写 RTL spec，再严格按 spec 实现；可以分多次提交，但 codex **只在完整提交上验证**（交接提交见第 4 节）。
- 参数参照 Rocket 或取合理默认值，以后通过生成参数和计数器调优。
- 验证顺序：IA（必要时 IMA）→ 访存与一致性 → FP 最后；FP 问题挂起，不挡前面的批次。
- 不做形式化（`agent.md`）。

## 1. 范围

| 部分 | spec | 内容 |
| --- | --- | --- |
| 协议与 L2/Home | [`coherence-l2-rtl-spec.md`](coherence-l2-rtl-spec.md) | 四链路协议、主流水、目录、慢槽、probe 引擎、AXI4 内存引擎 |
| L1D | [`l1d-rtl-spec.md`](l1d-rtl-spec.md) | S0–S2、pending-store、MSHR、写回槽、probe 处理、LR/SC、AMO、MMIO、PTW 入口 |
| 后端 | [`backend-v1-rtl-spec.md`](backend-v1-rtl-spec.md) | T01 记分板与 MDU（沿用 [`backend-rtl-spec.md`](backend-rtl-spec.md) 冻结规则）、FPU 带 tag 在途表、访存对齐 S0/S1/S2、迟到数据 |
| 核心、集群与外壳 | [`cluster-soc-rtl-spec.md`](cluster-soc-rtl-spec.md) | Sv39 MMU 接入、L1I 客户端、核心顶层、集群 AXI 边界、DMA 从口、LiteX 外壳、仿真与 ACT4 入口 |
| 测试 | [`v1-testplan.md`](v1-testplan.md) | 模块、集群、SoC 各层测试与门槛 |
| codex 规则 | [`tasks/V1-test-fix-loop.md`](tasks/V1-test-fix-loop.md) | 测试—修复循环规则 |

不在本轮：前端预测改进（BTB/GHR/RAS/预取）、计数器本体与 `perf` 从口（本轮只引出事件线）、FASE 适配新内存系统、PCIe。

## 2. 文件替换表

（随各 spec 完成填写。）

## 3. 进度

| 项 | 状态 |
| --- | --- |
| 协议与 L2 spec | 初稿完成 |
| 集成备忘 | 完成：[`v1-integration-notes.md`](v1-integration-notes.md)（现有代码事实与集成决定） |
| L1D spec | 未开始 |
| 后端 spec | 未开始 |
| 集群与外壳 spec | 未开始 |
| 测试计划、codex 规则 | 未开始 |
| RTL 实现 | 未开始 |
| 交接提交 | 未产生 |

## 4. 交接

全部 RTL、测试和文档完成后，Claude 在最后一个提交的信息中写 `V1-HANDOFF`，并在本文第 3 节记录该提交的 SHA。codex 只从带此标记的提交开始验证；此前的中间提交可能无法编译，不作验证对象。
