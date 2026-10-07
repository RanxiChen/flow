# 整机仿真：测试台与测试程序约定（v1 第 4 步）

日期：2026-10-07。目的：在 ChiselSim/Verilator 中运行真实 `BreezeCluster`（后端、前端、L1I、Sv39 MMU、L1D、L2），用自检程序验证整机。内存子系统模块级已通过（`a304cc2`），本步检查真实后端对 `L1DCoreIO` 的使用、真实 MMU 经 L1D/L2 的 walk、L1I 经 L2 取指、FENCE.I 端到端和多 hart 程序。

旧 `BreezeCore` 及其仿真设施（`core/BreezeCore.scala`、`sim/BreezeCoreSimSupport.scala`、`test/scala/sim/*`、`test/scala/core/breezecoreSpec.scala`）在本步删除；有效语义由新测试台上的 riscv-tests 与自写程序重新覆盖。`sim/BreezeCoreTandem*.scala`（trace 事件容器与格式化）保留复用。

## 1. 测试台（`design/src/test/scala/cluster/`）

DUT：`BreezeCluster(cfg, enableTandem = true)`。测试侧模型：

| 端口 | 模型 |
| --- | --- |
| `mem`（AXI4） | `AxiMemory`（复用 `memsys`），主存 `main_ram` 0x8000_0000，按 ELF 预装；可选随机反压 |
| `mmio`（AXI-Lite） | `ClusterMmio`：CLINT（`machine_timer` 0x0200_0000：`msip[h]` @ +4h，`mtimecmp[h]` @ +0x4000+8h，`mtime` @ +0xBFF8）、控制台（`soc_ctrl` 0x1200_0000：写字节即输出字符）；其他设备地址读 0、写丢弃并记录 |
| `msip`/`mtip`/`time` | 由 CLINT 模型驱动：`mtime` 每 `timerDivider` 拍加 1，`mtip[h] = mtime >= mtimecmp[h]` |
| 外部中断 | 恒 0 |
| `dma` | 空闲 |
| `retire[h]` | 每 hart 提交 trace，用于 tohost 检测、看门狗与可选日志 |

接口：

```scala
final case class ClusterRunConfig(
    cfg: BreezeClusterConfig,          // 预设 + privilegeProfile（Linux 打开 S/U、Sv39、C）
    elf: java.nio.file.Path,
    harts: Int = 1,                    // 需要报告 tohost 的 hart 数（0 until harts）
    resetAddr: BigInt = 0x80000000L,
    maxCycles: Long = 2_000_000,
    watchdog: Long = 20_000,           // 所有 hart 都无提交的连续拍数上限
    timerDivider: Int = 16,
    axiBackpressure: Boolean = false,
    seed: Int = 1,
    traceLog: Boolean = false)
final case class ClusterRunResult(cycles: Long, retired: Seq[Long], tohost: Seq[Option[BigInt]],
    console: String, passed: Boolean, reason: String)
object ClusterSim { def run(c: ClusterRunConfig): ClusterRunResult }
```

## 2. 结束与判定（程序 ABI）

- 程序为 ELF64，链接在 0x8000_0000；测试台装入全部 PT_LOAD 段，复位地址为 `resetAddr`。
- 符号 `tohost`：hart h 的结果槽位于 `tohost + 64*h`（64 B 间隔，避免与同行他 hart 槽假共享）。riscv-tests 只用 hart 0 的槽。
- 测试台从 `retire[h]` 的提交 Store 检测写入（地址落在 hart h 的槽内即可，任意宽度）；不依赖 cache 写回。值 1 = 通过；其他非零值 = 失败，`value >> 1` 为失败编号（riscv-tests 约定）。
- 通过条件：`0 until harts` 每个 hart 都写了 1。立即失败：任一 hart 写非 1 值、`hartFatal`、超过 `maxCycles`、看门狗触发。失败信息包括 hart、PC、最近 32 条提交、控制台输出。
- 未参与的 hart 由程序自行停住（riscv-tests 的 `RISCV_MULTICORE_DISABLE`；自写程序用 `wfi` 循环）。

## 3. 程序

| 目录 | 来源 | 构建 |
| --- | --- | --- |
| `third_party/riscv-tests`（submodule，固定 `bcffa2b`） | rv64ui/um/ua/uc/mi/si 的 `-p-` 环境 | `tools/build_riscv_tests.sh` → `design/build/riscv-tests/` |
| `tests/cluster/` | 自写自检程序：`link.ld`、`common.h`（`PASS`/`FAIL n` 宏、hart 槽地址）、`*.S` | `make -C tests/cluster` → `tests/cluster/build/*.elf` |

工具链前缀由 `RISCV_PREFIX` 指定，默认 `riscv64-unknown-elf-`；`riscv64-linux-gnu-` 加 `-nostdlib -static` 同样可用（本地已验证 riscv-tests 的 `-p-` 程序可链接）。ELF 缺失时用例失败并提示构建命令，不跳过。
