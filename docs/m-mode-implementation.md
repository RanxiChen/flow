# M-Mode Implementation Notes

## Goal

本文档记录 Flow 第一版 RISC-V RV64 Machine mode 特权架构实现要求，只保留当前有效的约束和主路径语义。

## V1 Scope

- 仅实现 `RV64 M-mode`
- 不实现 `S-mode`
- 不实现 `U-mode`
- 不实现 `VS/VU/Hypervisor`
- 仅处理同步异常
- 暂不实现任何异步中断
- 不实现页式虚拟内存
- 不实现 `satp` / `SFENCE.VMA` / `SINVAL.VMA`
- 不实现复杂 `PMP/PMA` 权限机制，第一版只做基础地址 / 对齐 / 访问错误检查
- 不实现 `F/D/Q` 浮点扩展
- 不实现 `V` 向量扩展
- 不实现其他带状态的 user-mode 扩展
- 不实现标准 double-trap 扩展 `Smdbltrp`
- 不实现 `Smrnmi/RNMI`
- 不实现大端模式
- 不实现虚拟化拦截机制
- 不实现异常委托和中断委托机制

## Current Privilege Model

- 当前特权级始终为 `M`
- trap 路径固定为 `M -> M trap -> mret -> M`
- 不存在 `U/S -> M` 的 trap
- 不存在 `S-mode` 自己的 trap
- `MPP` 永远为 `M`，即 `2'b11`
- 所有同步异常固定由 `M-mode` 处理
- 不进行 `medeleg` / `mideleg` 查询
- 不存在从 `M-mode` 向 `S-mode` 的 trap 委托

## Trap Main Path

### Synchronous Exception Entry

同步异常进入 `M-mode trap` 时：

- `if in_trap == 0:`
- `in_trap <= 1`
- `mepc   <= fault_pc`
- `mcause <= exception_cause`
- `mtval  <= exception_value`
- `MPIE   <= MIE`
- `MIE    <= 0`
- `MPP    <= M`
- `pc     <= mtvec`
- `flush pipeline`

### Nested Trap Policy

第一版不实现标准 `Smdbltrp` / `MDT` 机制，内部仅保留一个调试用状态：

- `in_trap = 0` 时，异常正常进入 trap
- `in_trap = 1` 时，再次异常视为 fatal nested trap

如果同步异常发生时 `in_trap != 0`：

- `fatal_error <= 1`
- `halt        <= 1`
- 不更新 `pc` / `mepc` / `mcause` / `mtval` / `mstatus`
- 不再次跳转 `mtvec`

### `mret`

执行 `mret` 时：

- `pc      <= mepc`
- `MIE     <= MPIE`
- `MPIE    <= 1`
- `MPP     <= M`
- `in_trap <= 0`
- `flush pipeline`

## `mstatus` Policy

### Implemented Fields

- `MIE`
- `MPIE`
- `MPP`

说明：

- `MIE` 是 `M-mode` 全局中断使能位
- `MPIE` 保存 trap 之前的 `MIE`
- `MPP` 保存 trap 之前的特权级
- 即使第一版不实现中断，同步异常进入 trap 时仍按规范更新 `MPIE` / `MIE` / `MPP`
- `mret` 不是强行把 `MIE` 置 `1`，而是 `MIE <= MPIE`，随后 `MPIE <= 1`

### Internal Debug State

以下状态仅用于内部调试，不属于标准 CSR 字段：

- `in_trap`
- `fatal_error`

### Read-Only Zero Fields

以下字段固定为 `0`，只读，写入忽略：

- `mip.*`
- `mie.*`
- `SIE`
- `SPIE`
- `SPP`
- `SXL`
- `UXL`
- `MPRV`
- `MXR`
- `SUM`
- `MBE`
- `SBE`
- `UBE`
- `TVM`
- `TW`
- `TSR`
- `FS`
- `VS`
- `XS`
- `SD`
- `SPELP`
- `MPELP`
- `mhpmcounter3` ... `mhpmcounter31`
- `mhpmevent3` ... `mhpmevent31`

## XLEN Policy

- 系统执行宽度固定为 `MXLEN=64`
- 不存在 `SXLEN` / `UXLEN` 配置
- 不支持低特权级 32-bit XLEN 执行模式
- 不实现低特权级 XLEN 相关寄存器高位符号扩展规则
- `RV64I` 的 `W` 类指令仍按 `RV64I` 规则实现：低 `32` 位运算，结果符号扩展到 `64` 位

## Memory Access Policy

- 所有 `load/store` 都按当前 `M-mode` 规则访问内存
- 不实现 `MPRV` 借用 `MPP` 权限的访存语义
- `MPRV` 不参与访存权限判断
- 不实现页表权限检查，因此 `MXR` / `SUM` 不参与权限判断
- 取指访问不受 `MPRV` 影响
- 第一版只做基础地址 / 对齐 / 访问错误检查

## Endianness Policy

- 只支持 little-endian 数据访问
- 指令取指固定为 little-endian
- 所有 `load/store` 均按 little-endian 处理
- 不支持 big-endian
- 不支持运行时切换大小端

## Unsupported Instructions and Mechanisms

### Not Implemented

- `MDT`
- `SDT`
- `RNMI`
- `MNRET`
- `mnepc`
- `mncause`
- `mnstatus`
- `medeleg`
- `mideleg`
- `medelegh`
- `satp`
- `SFENCE.VMA`
- `SINVAL.VMA`
- `SRET`
- `sepc`
- `scause`
- `stval`
- `stvec`
- `sie`
- `sip`
- `sstatus`
- `Zicfilp`
- 虚拟化相关 trap 拦截
- 浮点状态跟踪
- 向量状态跟踪
- 其他扩展状态跟踪

### Illegal Instruction Cases

以下情况触发 illegal instruction exception：

- 执行 `SRET`
- 执行 `SFENCE.VMA`
- 执行 `SINVAL.VMA`
- 软件访问 `medeleg`
- 软件访问 `mideleg`
- 软件访问 `medelegh`
- 所有依赖 `Zicfilp` 扩展的指令或行为
- 任何浮点指令
- 任何向量指令
- 任何访问浮点 CSR 的指令
- 任何访问向量 CSR 的指令
- 任何浮点、向量或对应扩展状态访问指令
- 访问未实现的 RV32 高半部分计数器 CSR，如 `mcycleh`、`minstreth` 以及相关 `mhpmcounternh` / `mhpmeventnh`

### `WFI`

- 第一版可按 `NOP` 处理
- 后续可再实现为 wait / halt until interrupt

## Interrupt Policy

- 第一版不支持异步中断
- `mip` 与 `mie` CSR 提供基础访问接口
- `mip` 的所有位均为只读 `0`，写入忽略
- `mie` 的所有位均为只读 `0`，写入忽略
- `machine software interrupt`、`machine timer interrupt`、`machine external interrupt`、`supervisor-level interrupt`、`local counter overflow interrupt` 以及平台自定义中断都不会被置为 pending
- 上述中断都不会触发 trap
- `mstatus.MIE`、`mstatus.MPIE`、`mstatus.MPP` 仍按同步异常 trap 和 `mret` 的规则更新
- `MIE` 不参与任何实际异步中断响应

## Performance Counter Policy

### Implemented Counters

- 第一版实现基础硬件性能计数器
- `mcycle` 和 `minstret` 均实现为 `64` 位可读写 CSR
- `mcycle` 用于统计处理器核心运行周期数，默认每个时钟周期递增
- `minstret` 用于统计正常退休的指令数量，仅在指令最终提交且未触发异常时递增
- 触发同步异常的指令不计入 `minstret`
- 被流水线冲刷的指令不计入 `minstret`
- `mret` 和普通 CSR 指令在正常提交时计入退休指令数
- 对 `mcycle` 或 `minstret` 的 CSR 写操作在对应 CSR 指令提交时生效
- CSR 写入会覆盖该周期的默认计数更新

### Read-Only Zero Counters

- `mhpmcounter3` 至 `mhpmcounter31` 在第一版中实现为只读 `0`，写入忽略
- `mhpmevent3` 至 `mhpmevent31` 在第一版中实现为只读 `0`，写入忽略

### RV32 High-Half Counter CSRs

- 本设计为 `RV64` 实现，不提供 `RV32` 使用的高半部分计数器 CSR
- 不实现 `mcycleh`
- 不实现 `minstreth`
- 不实现相关 `mhpmcounternh` / `mhpmeventnh` CSR
- 访问这些未实现 CSR 时触发 illegal instruction exception

## Extension State Policy

- 不实现浮点扩展、向量扩展和其他带用户态状态的扩展
- 不实现 `Zicfilp` 扩展
- 不维护 `ELP` 状态
- 不实现 `SPELP` / `MPELP` 等 `xPELP` 字段的 trap 保存与恢复机制
- 若相关字段在 CSR 视图中出现，则统一视为只读 `0`，写入忽略
- 同步异常进入 `M-mode trap` 时，仅按基础 `Machine mode trap` 路径更新 `mepc` / `mcause` / `mtval` / `mstatus.MIE` / `mstatus.MPIE` / `mstatus.MPP`
- 不额外保存 landing pad 相关状态
- 不实现扩展状态的 `Dirty / Clean / Initial` 跟踪
- 不实现扩展状态上下文保存 / 恢复辅助机制

## Delegation Policy

- 第一版不提供 `medeleg`、`mideleg` 和 `medelegh` CSR
- 由于不支持 `S-mode`，异常委托寄存器和中断委托寄存器在本实现中不存在
- 软件访问 `medeleg`、`mideleg` 或 `medelegh` 时，应触发 illegal instruction exception
- CSR decode 中，`medeleg` / `mideleg` / `medelegh` 归类为非法 CSR

## S-Mode Trap CSR Policy

- 第一版不实现 `S-mode trap` 入口
- 不实现 `sepc`、`scause`、`stval`、`stvec`、`sie`、`sip`、`sstatus` 等 `S-mode CSR`
- 同步异常发生时，硬件仅更新 `Machine mode trap` 相关状态，即 `mepc` / `mcause` / `mtval` / `mstatus.MIE` / `mstatus.MPIE` / `mstatus.MPP`

## `misa`

### Implemented

- `misa` CSR 地址为 `0x301`
- 当前值来自 `CoreParam.misa`
- 当前编码表示 `RV64I` 且带 `M` 扩展
- reset 值锁存进 CSR file，可通过正常 CSR 路径读取
- 对 `misa` 的写入被忽略，保持只读

## `mtvec`

### Implemented

- 第一版实现 `mtvec` CSR
- `mtvec` 为 `RV64` 下 `MXLEN` 位 `WARL` 可读写寄存器
- `mtvec[63:2]` 保存 trap vector `BASE`
- `mtvec[1:0]` 保存 `MODE`
- `BASE` 作为地址使用时低两位补 `0`，因此 trap 入口地址至少 `4` 字节对齐
- 第一版可保留 `MODE=Direct` 和 `MODE=Vectored` 的字段编码
- 硬件结构中可预留 `Vectored` 模式下 `BASE + 4 x cause` 的目标地址计算逻辑
- 由于第一版暂不实现异步中断，所有已实现 trap 均为同步异常
- 因此无论 `MODE` 为 `Direct` 还是 `Vectored`，同步异常进入 trap 时均跳转到 `{mtvec[63:2], 2'b00}`
- `mcause` 仍正常记录同步异常原因，但不会参与第一版 trap 入口 `PC` 的计算
- `MODE >= 2` 为保留值
- 第一版可按 `WARL` 规则将 `MODE >= 2` 规整为 `Direct`，或在内部保留但不产生额外行为
- reset vector 由平台复位逻辑决定，与 `mtvec` 无关
- `mtvec` 复位值由实现指定，建议设置为默认 trap handler 地址
- 软件可通过 CSR 写修改 `mtvec`

## Code References

- `design/src/main/scala/top/config.scala`
- `design/src/main/scala/core/common.scala`
- `design/src/main/scala/core/RegFile.scala`
