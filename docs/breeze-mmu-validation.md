# Breeze Sv39 MMU 模块验证

2026-10-04：独立新版 MMU 按 [RTL 规格](breeze-mmu-rtl-spec.md) 实现，模块验证 PASS。系统依赖仍按 [闭环清单](breeze-mmu-closure-checklist.md) 逐项完成。

## 源码与验证版本

- RTL 和测试提交：`95b8c7769839f29d9817ef31f41a118e13fb5551`。
- 分支：`feat/pcie-fase-20260920`；本地 commit/push，经 GitHub 同步至 Alan 后执行。
- 生产代码：[mmu/sv39](../design/src/main/scala/mmu/sv39/)，包 `flow.mmu.sv39`，共 7 个文件。
- 测试：[mmu/sv39](../design/src/test/scala/mmu/sv39/)，2 个 suite 和独立内存/参考 walker 驱动。
- 环境：Alan，sbt 1.9.7，Java 11.0.32.1，Verilator 5.028。
- 证据目录（Alan）：`/home/chen/FUN/flow-runs/20261004-sv39-95b8c77/`；包含 `source-sha.txt`、`verilator-version.txt`、`tests.log`、`exit-code.txt`。

在 Alan 的 `design/` 下执行：

```sh
sbt 'testOnly flow.mmu.sv39.*'
```

结果：29 tests，2 suites；29 succeeded，0 failed/aborted/canceled/ignored/pending；shell exit code 0。最终日志没有 Chisel elaboration warning 或 assertion failure。Scala 编译、Chisel elaboration、生成 RTL 的 Verilator 模块仿真均完成。

## 规格测试覆盖

| 规格编号 | 已执行检查 |
| --- | --- |
| T1 | Bare、M 模式、数据 MPRV/MPP 与取指忽略 MPRV；64 位地址直通，无 PTE 读 |
| T2 | 正/负非规范地址，三类访问直接 page fault，无 PTE 读 |
| T3–T5 | L=1、5；首次访问 3 次 PTE 读；中层命中 1 次、上层命中 2 次；请求、PTE 读、ready 恢复的精确拍数与 PA |
| T6 | 2 MiB/1 GiB 超页及区域内其他地址，含负规范 VA；后续命中不读 PTE |
| T7 | 超页未对齐、V=0、R=0/W=1、高位保留字段、level 0 非叶；fault 不缓存；非叶 U/A/D 忽略 |
| T8 | 各层 PTE 读的 access fault，三类访问；access fault 优先于无效 PTE 数据 |
| T9 | 40 种结构合法的 R/W/X/U/A/D 叶组合 × 5 种 privilege/MPRV/MPP 上下文 × 3 类访问 × SUM/MXR 全组合，共 2400 次动态权限比对；不新增 walk |
| T10 | 16 位 ASID（含 0xffff）隔离、G 叶跨 ASID 命中、非叶 G 不继承 |
| T11 | 全刷、按 VA 刷、按 VA+ASID 刷；G/非 G、非目标 ASID、超页范围、非规范 sfence VA；任意 sfence 清空 walk-cache |
| T12 | I/D 同时 miss，首个 PTE 读来自 D 侧，两侧最终完成 |
| T13 | grant 前 kill、grant 同拍 kill、grant 后 kill；合法结果照常 refill；被 kill 的 page/access fault 不交付；pending fault 与 S1 响应的 kill |
| T14 | 连续 hit→miss，miss 拍接受的年轻请求不产生响应，按顺序重发成功 |
| T15 | 基础页和超页 invalid-first 分配；填满并 touch 后按 tree PLRU 替换 |
| T16 | L=1、5 的随机 PTW ready 反压，地址稳定断言、结果正确；seed `0x1639` |
| T17 | 随机稀疏页表、400 次随机访问，与独立 Scala Sv39 walker 比对；含超页、权限、结构错、access fault、sfence；seed `0x1739` |

T3–T5 按延迟分成两个测试，其余各一项，共 16 个 MMU 场景。另有 13 个结构测试：参数约束；1/2/4/8/16 路 PLRU 的随机软件树比对；4 种 walk-cache 组/路配置的 ASID、替换、flush；1 set、1/2/8 ways MMU 和单超页项配置的翻译及 sfence。

## 断言落实

RTL 规格 §10 的 13 条断言均已实现：

| 编号 | 模块 |
| --- | --- |
| 1–5 | `Sv39Tlb`：响应独热、多命中、RAM 读写互斥、访问类型、fault retry VPN |
| 6 | `Sv39Mmu` 与 `Sv39Tlb`：sfence 前 idle，TLB 查询已排空 |
| 7–8 | `Sv39Tlb`：sfence S0/S1 无请求/refill，新 sfence 脉冲须间隔且 S1 已结束 |
| 9–10 | `Sv39Ptw`：响应只能在 sWait，请求反压期间地址稳定 |
| 11 | `Sv39WalkCache`：fill key/ASID 未命中 |
| 12 | `Sv39Ptw` 与 `Sv39Tlb`：done 必须属于 valid/granted 的 miss |
| 13 | `Sv39Ptw`：I/D grant 互斥 |

上述结果证明本次参数和测试序列下的模块行为。前端跨页取指、LSU 非对齐 trap、真实 D-cache PTW 通道/PMP/PMA/前进保证、核心 sfence 串行化仍待系统集成；本次没有综合、时序、FPGA 或 Linux 运行证据。
