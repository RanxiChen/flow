# BreezeCore Cache 与 LiteX 接口调研

## 文档状态

- 日期：2026-08-02
- 性质：实现前调研与接口建议，不代表代码已经完成
- 范围：ICache、DCache、MMIO 与 LiteX 仿真 SoC 的连接
- 暂不涉及：DDR、FPGA 上板、性能调优

本次调研以本机 `/home/chen/litex_soc/litex` 为主要实现基线。该仓库当前位于提交
`f5f412dedb450c4900e7c6602e11f11444f77bc8`；同时对照了 2026-08-02 时 LiteX 官方仓库
`master`。官方远端当日 HEAD 为 `3f01ef9658376d0cbd5d21a1db720163e605f71e`。

## 结论摘要

第一版应该同时整理 ICache 和 DCache 的下级接口，再接 LiteX，而不是只实现 DCache 后再补
ICache 适配。建议冻结以下接口方向：

1. BreezeCore 对 LiteX 暴露两个相互独立的 64-bit Wishbone master：只读 `ibus` 和读写
   `dbus`。LiteX 已经能够把同一个 CPU 的多个 `periph_buses` 分别加入 SoC 总线并进行仲裁。
2. ICache 和 DCache 本体不直接依赖 Migen/LiteX；在 Chisel 中使用内部请求/响应接口，另设
   `IWishboneAdapter`、`DWishboneAdapter`，由专用 LiteX 顶层组合。
3. 第一版使用 64-bit、word-addressed Wishbone Classic 单拍事务。现有 32-byte ICache line
   由 4 个 64-bit 读事务完成 refill；暂不依赖 burst。
4. RV64 并不要求第一版 SoC 具有 64-bit 物理地址。建议第一版 LiteX 总线地址宽度采用 32 bit，
   核内仍保留 64-bit 地址；高 32 bit 非零或不符合物理区域属性的访问直接形成 access fault。
5. Cacheable、可读、可写、可执行、device 等属性必须由 BreezeCore 的硬件 PMA 表判断。
   LiteX 的 `SoCRegion.cached` 不能自动驱动核内 DCache bypass。
6. `FENCE.I` 不能只是立即清空 ICache。它必须等待 D 侧先前写事务完成；如果以后采用
   write-back DCache，还必须先把 dirty line 写回，再失效 ICache，最后才允许后续取指。
7. LiteX 默认的 Wishbone bus timeout 会返回 `ack` 和全 1 数据，并把 timeout 记入 SoC
   controller；它不会通过 master 的 `err` 返回 access fault。因此不能仅依靠 LiteX 默认
   timeout 完成精确异常。

## LiteX 源码调研结果

### Wishbone 接口

本地 `litex/soc/interconnect/wishbone.py` 中的 Wishbone master/slave 信号包括：

```text
adr, dat_w, dat_r, sel, cyc, stb, ack, we, cti, bte, err
```

LiteX SoC 的 Wishbone 总线目前默认采用 word addressing。若总线数据宽度为 64 bit、SoC
地址宽度为 32 bit，则：

```text
Wishbone adr width = 32 - log2(8) = 29 bit
wb.adr             = byte_address[31:3]
wb.sel             = 8-bit byte enable
```

一个 Classic 事务必须保持 `cyc`、`stb`、地址、写使能、写数据和 byte enable 稳定，直到
收到 `ack` 或 `err`。`ack` 表示完成，`err` 表示该事务失败。

LiteX 也定义了 `cti`/`bte` 和 incrementing burst，片上 Wishbone SRAM 也具有可选 burst
支持。但是 `SoCBusHandler` 的 `bursting` 默认关闭，所以第一版使用四个独立 Classic 读事务，
避免把 cache 正确性绑定到每个 slave 的 burst 能力。以后确认整条总线都支持 burst 后，再将
line refill 优化为 `INCREMENTING ... END`。

### 两个 CPU master

LiteX 的 CPU 基类允许 `periph_buses` 包含多条总线。SoC 集成代码会遍历它们，并逐条调用
`bus.add_master()`。现有 CV32E40P 和 VexRiscv wrapper 都采用独立的 `ibus`、`dbus`。

因此 BreezeCore 不应在核内把取指和数据请求预先仲裁成一条总线。正确边界是：

```text
                               BreezeCoreLiteXTop
                    +-----------------------------------+
                    |                                   |
 frontend -> ICache -> IWishboneAdapter -> ibus master |
 backend  -> DCacheSubsystem -> DWishboneAdapter        |
                    |                         -> dbus master
                    +-----------------------------------+
                              |               |
                              +--- LiteX ------+
                                    |
                         ROM / RAM / UART / Timer
```

如果 LiteX 使用 shared interconnect，两路 master 在共享 slave 前仲裁；如果以后改成 crossbar，
两路访问不同 slave 时可以并行。这个选择属于 SoC，不应改变 cache 接口。

### ROM、RAM 与仿真

`SoCCore` 已提供 integrated ROM、integrated SRAM 和 integrated main RAM。`litex_sim` 也能够
装载 ROM/RAM 初始化文件，因此早期仿真不需要 LiteDRAM，更不需要 DDR controller/PHY。

现有 LiteX CPU 搜索逻辑会在 LiteX 内置 CPU 目录和执行命令的当前目录中寻找含 `core.py` 的
CPU 目录。因此项目可以继续采用 out-of-tree wrapper，不必修改 `/home/chen/litex_soc/litex`。
更稳妥的入口是新增项目自己的 `flow_sim.py`，显式导入 `litex_wrapper/flow` 并构造 SimSoC，
而不是依赖从任意工作目录运行 `litex_sim --cpu-type=flow`。

### LiteX 区域属性不是硬件 PMA

LiteX 使用 `SoCRegion(cached=...)` 与 CPU wrapper 的 `io_regions` 检查地址图：IO 区域应标为
uncached，普通存储区域应标为 cached。这些信息位于 Python SoC 构建阶段，并不会自动成为
BreezeCore RTL 的 DCache 控制输入。

应当定义一份 SoC 地址图源数据，并同时用于：

- LiteX 的 `SoCRegion`；
- BreezeCore LiteX 顶层中的 PMA 参数；
- linker script / 软件头文件。

PMA 至少需要表达：

| 区域类型 | readable | writable | executable | cacheable | device |
| --- | ---: | ---: | ---: | ---: | ---: |
| Boot ROM | 1 | 0 | 1 | 1 | 0 |
| RAM | 1 | 1 | 1 | 1 | 0 |
| UART / Timer / CSR | 1 | 1 | 0 | 0 | 1 |
| 未映射 | 0 | 0 | 0 | 0 | 0 |

这不仅决定 DCache bypass，还负责阻止从 MMIO 取指、向 ROM 写入，以及在发出 Wishbone 请求前
识别明显的未映射访问。

## 建议的核内存储接口

### 通用 line 接口

ICache refill 与可能的 DCache refill/writeback 都使用 32-byte line。建议先定义与具体总线无关的
请求/响应：

```scala
class LineMemReq(val pAddrBits: Int = 32, val lineBytes: Int = 32) extends Bundle {
  val addr  = UInt(pAddrBits.W)          // 必须按 lineBytes 对齐
  val write = Bool()
  val data  = UInt((lineBytes * 8).W)    // read 时忽略
  val mask  = UInt(lineBytes.W)          // 首版 writeback 通常全 1
}

class LineMemResp(val lineBytes: Int = 32) extends Bundle {
  val data  = UInt((lineBytes * 8).W)    // write 响应时忽略
  val error = Bool()
}

class LineMemIO(...) extends Bundle {
  val req  = Decoupled(new LineMemReq(...))
  val resp = Flipped(Decoupled(new LineMemResp(...)))
}
```

必须使用 valid/ready 握手，不能继续依赖“一拍请求脉冲”和“未来某拍一定返回”的隐含假设。
请求只有在 `req.valid && req.ready` 时才算被接收；响应只有在 `resp.valid && resp.ready` 时才算
被消费。

ICache 只产生 line read。DCache 若第一版采用 write-through，则暂不产生 line write；接口保留
write 能力，未来改成 write-back 时无需再次修改 LiteX 边界。

### uncached / MMIO 标量接口

DCacheSubsystem 还需要独立的 64-bit 标量访问语义：

```scala
class ScalarMemReq(val pAddrBits: Int = 32) extends Bundle {
  val addr  = UInt(pAddrBits.W) // 8-byte 对齐后的总线地址
  val write = Bool()
  val data  = UInt(64.W)
  val mask  = UInt(8.W)
}

class ScalarMemResp extends Bundle {
  val data  = UInt(64.W)
  val error = Bool()
}
```

Backend 现有的 `BackendMemIO` 请求没有 `ready`，响应没有 `error`。它也需要改成正式的
request/response 握手，并把响应错误携带到流水线，以产生：

- instruction access fault：`mcause = 1`；
- load access fault：`mcause = 5`；
- store/AMO access fault：`mcause = 7`。

`mtval` 应保存发生访问错误的原始字节地址，而不是 8-byte 对齐后的 Wishbone 地址。

## ICache 接入方案

现有 ICache 已经固定为 32-byte line，但 miss 下级接口仍是：

- `req + paddr` 的单周期脉冲；
- 一次返回完整 256-bit line；
- 没有 request ready；
- 没有 response ready；
- 没有 error。

这不能直接可靠地连接具有 back-pressure 和错误响应的 Wishbone。建议用 `LineMemIO` 替换，并
由 `IWishboneAdapter` 完成以下状态机：

```text
IDLE
  -> 接收 32B 对齐 line read
  -> WB_READ_BEAT_0   byte address + 0
  -> WB_READ_BEAT_1   byte address + 8
  -> WB_READ_BEAT_2   byte address + 16
  -> WB_READ_BEAT_3   byte address + 24
  -> LINE_RESPONSE
```

每一拍均使用 `cti=000`、`bte=00`、`we=0`、`sel=0xff`，并在 `ack || err` 前保持请求稳定。
四个 64-bit 返回值按 little-endian 地址顺序放入 256-bit line：低地址 beat 放在低位。

任何一拍收到 `err`，或触发集成层规定的 timeout，都必须：

- 中止当前 refill；
- 不更新 tag/data/valid；
- 向 frontend 返回 `error=1`；
- 最终对该取指地址产生 instruction access fault。

ICache flush 与 refill 同时发生时，adapter 仍应把已经发出的 Wishbone 事务正常收尾；ICache
可以丢弃响应，但不能在总线事务中途撤销后立刻复用同一状态。

## DCache 接入方案

建议把数据侧划分为：

```text
BackendMemIO
     |
     v
DCacheSubsystem
  |-- PMA / permission check
  |-- cacheable RAM path -> small fully-associative DCache -> LineMemIO
  `-- device/MMIO path  -> ScalarMemIO
                                      |
                              DWishboneAdapter
                                      |
                                 dbus master
```

`DWishboneAdapter` 在 line 事务和 scalar 事务间做局部选择，但它只驱动一条 `dbus`。由于第一版
顺序核一次只允许一个未完成的数据访问，这里不需要复杂的 transaction ID 或乱序返回。

### 第一版写策略建议

为了先得到异常精确、容易验证的 MCU 数据通路，建议第一版采用：

- 4 个全相连 entry；
- 每个 entry 32 byte，与 ICache line 一致；
- load miss refill；
- write-through；
- store miss no-write-allocate；
- 单 outstanding、阻塞式访问；
- 简单 round-robin 或 pseudo-random replacement。

write-through store 只有在 Wishbone 写事务成功后才能向 Backend 返回完成，所以 slave 的
`err` 可以精确归属到当前 store。store hit 可以同步更新 DCache line，但如果外部写失败，必须
使该 line 失效或恢复旧数据，不能保留与 RAM 不一致的新值。

如果改成 write-back，必须新增 dirty bit、victim writeback 和 refill 串行状态。更重要的是，
一个较早已经退休的 store 可能在以后替换 dirty line 时才遇到总线错误，此时无法自然地把错误
精确归属到原 store。选择 write-back 前，必须先决定这种错误是：

- 作为触发替换的当前 load/store access fault；还是
- 作为不可恢复的 machine error；还是
- 通过额外机制保证 store 在可报告错误前不退休。

因此 line writeback 能力现在应在接口中预留，但 write-back 本身暂不在本次调研中冻结。

### MMIO bypass

device 区域访问不得分配、命中或更新 DCache line。它直接通过 `ScalarMemIO` 形成一个 64-bit
Wishbone Classic 事务：

```text
wb.adr   = byte_address[31:3]
wb.dat_w = Backend 已完成 lane 放置的 64-bit wdata
wb.sel   = Backend 的 8-bit wmask
wb.we    = load ? 0 : 1
```

顺序核必须等 MMIO 事务 `ack` 或 `err` 后再继续，以保持 device 访问的程序顺序。第一版不合并、
不投机、也不预取 MMIO。

## `FENCE.I` 的联合处理

现有实现中，Backend 识别 `FENCE.I` 后立即向 frontend 发出 `cacheFlush` 和 redirect。引入 DCache
后，这个行为不再充分。应增加一个明确的 `fenceiReq/fenceiDone` 协议：

```text
Backend 遇到 FENCE.I，停止其后的退休和取指
        |
        v
DCacheSubsystem 等待所有已接受的 store / MMIO 写完成
        |
        +-- 若为 write-back：遍历并写回全部 dirty line
        |
        v
返回 fenceiDone
        |
        v
失效整个 ICache，清空错误路径上的旧取指状态
        |
        v
从 FENCE.I 后一条指令重新取指并允许 FENCE.I 退休
```

write-through 且阻塞式数据通路下，D 侧通常已经没有未完成事务，但仍保留握手能避免未来改写
策略时再次修改 Backend/Frontend 协议。write-back 下，全量 flush 对少量全相连 entry 很简单，
也正好满足单 hart MCU 的正确性要求。

## 总线错误和 timeout

必须区分两类失败：

1. slave 主动拉高 `wb.err`：adapter 立即返回 access fault；
2. 未映射地址或 slave 永不响应：LiteX 默认 timeout 最终给出 `ack` 和全 1 数据，只在
   SoCController 中累计 bus error，CPU 看不到 `wb.err`。

第一版建议同时使用两道防线：

- PMA 在发请求前拒绝未映射、无权限、不可执行等访问；
- I/D adapter 各自设置可配置事务 watchdog，并把超时作为 `error` 返回核心。

为了避免 LiteX 先以成功 `ack` 吞掉同一个 timeout，BreezeCore 仿真 target 应把 LiteX
`bus_timeout` 关闭，或把 adapter timeout 明确设得更短。最终采用哪一种应在写 `flow_sim.py`
时固定，并加入“未映射取指/load/store 均进入 trap”的代码级检查。

## LiteX wrapper 与仿真入口

现有 `litex_wrapper/flow/core.py` 只能视为旧草稿，不能原样延续，原因包括：

- 只有一条 32-bit `idbus`，没有独立 `ibus`/`dbus`；
- wrapper 期望 `litex_flow_top`，当前 Chisel 生成入口和模块名并不匹配；
- 生成 RTL 的副作用混在 wrapper 构造过程中；
- 没有定义 ICache 多拍 refill、DCache、MMIO bypass 和 access fault 行为。

建议最终结构为：

```text
design/src/main/scala/mem/LineMemIO.scala
design/src/main/scala/cache/BreezeDCache.scala
design/src/main/scala/bus/IWishboneAdapter.scala
design/src/main/scala/bus/DWishboneAdapter.scala
design/src/main/scala/top/BreezeCoreLiteXTop.scala
litex_wrapper/flow/core.py
fpga/sim/flow_sim.py
```

新版 Python wrapper 至少需要声明：

```python
ibus = wishbone.Interface(data_width=64, address_width=32,
                         addressing="word", mode="r")
dbus = wishbone.Interface(data_width=64, address_width=32,
                         addressing="word", mode="rw")
self.periph_buses = [ibus, dbus]
self.memory_buses = []
```

并将完整 Wishbone 信号，包括 `err`，连接到 `BreezeCoreLiteXTop`。RTL 应由单独、可重复的构建命令
提前生成，wrapper 只消费明确的 filelist，不在 LiteX elaboration 中临时调用 SBT 和复制文件。

仿真分两步推进更稳妥：

1. 先用 integrated ROM/RAM 加一个最小裸机程序，验证 reset、取指、load/store、MMIO UART 和
   access fault；
2. 核心的 CSR、trap、interrupt 和 ABI 足够稳定后，再尝试构建并启动完整 LiteX BIOS。

## 推荐实现顺序

1. 定义 PMA、`LineMemIO`、`ScalarMemIO` 以及带 error 的 Backend/Frontend memory response。
2. 实现并单独检查 `IWishboneAdapter`，再把 ICache miss 脉冲接口替换为握手接口。
3. 实现 `DWishboneAdapter` 的 uncached scalar 路径，让 Backend 可以先不经过 DCache 访问
   ROM/RAM/MMIO，并打通 load/store access fault。
4. 实现第一版小型全相连 DCache，接入 cached load 和 write-through store。
5. 实现 `fenceiReq/fenceiDone`，把 D 侧 drain 与 ICache invalidate 串起来。
6. 新增 `BreezeCoreLiteXTop`、新版 CPU wrapper 和项目内的 LiteX SimSoC target。
7. 最后再决定是否切换 write-back、write-allocate 和 Wishbone burst。

## 本次调研后仍需确认的选择

- 是否接受第一版 `4 entries × 32 bytes、write-through、no-write-allocate` 的建议；
- 第一版 32-bit 物理地址图的 ROM/RAM/MMIO 起始地址与容量；
- adapter watchdog 周期数以及 LiteX `bus_timeout` 的最终配置；
- 最小仿真先运行自有裸机程序，还是直接以 LiteX BIOS 为第一个软件目标。

这些选择不影响“两条独立 64-bit Wishbone master、cache 内部采用握手接口、硬件 PMA、
`FENCE.I` 联合处理”这一总体方向。

## 参考源码

- 本地 LiteX：`/home/chen/litex_soc/litex/litex/soc/interconnect/wishbone.py`
- 本地 LiteX：`/home/chen/litex_soc/litex/litex/soc/integration/soc.py`
- 本地 LiteX：`/home/chen/litex_soc/litex/litex/soc/integration/soc_core.py`
- 本地 LiteX：`/home/chen/litex_soc/litex/litex/tools/litex_sim.py`
- 本地 CPU 示例：`/home/chen/litex_soc/litex/litex/soc/cores/cpu/vexriscv/core.py`
- LiteX 官方仓库：<https://github.com/enjoy-digital/litex>
- RISC-V `Zifencei`：<https://docs.riscv.org/reference/isa/unpriv/zifencei.html>
