# BreezeFrontend

本文记录当前 `BreezeFrontend` 的设计目标、流水线划分、已实现内容，以及当前阶段的简化约定。

## 设计目标

`BreezeFrontend` 当前不是一次性把完整前端写完，而是按流水线逐阶段补齐：

1. 先把 PC 生成和阶段寄存关系明确下来。
2. 再把 `BreezeCache` 接入前端流水。
3. 再把取回的指令和预测信息送到 fetch buffer。
4. 最后再细化阻塞、预测、异常、flush 等控制逻辑。

当前的设计重点是：

- 地址统一按虚拟地址处理。
- `BreezeCache` 在前端内部实例化。
- 流水线按 `S0 -> S1 -> S2 -> S3` 组织。
- 后端重定向优先级最高，前端快速预测次之，顺序取指 `+4` 最低。

## 当前流水线定义

### S0: Next PC Generation

`S0` 负责决定下一条 PC。

当前候选来源有三个：

1. 后端重定向 `io.beRedirect`
2. `S3` 的快速预测重定向
3. 顺序执行 `s1_pcReg + 4`

当前实现使用 `Mux1H(Seq(...))` 组织这三种来源，让优先级关系更直接。

此外，`S0` 当前还有一个推进条件：

- `s0_canAdvance := io.fetchBuffer.canAccept3 && icache.io.drsp.valid`

这个条件是当前阶段的简化实现，表示只有 fetch buffer 可继续接收、且 cache 当前给出返回时，`s1_pcReg` 才更新到下一条 PC。

### S1: PC Stage

`S1` 当前保存前端的 PC：

- `s1_pcReg`

当前行为：

- reset 时回到 `io.resetAddr`
- 满足 `s0_canAdvance` 时，装入 `s0_nextPc`

此外，`S1` 会把当前 `s1_pcReg` 送给 `BreezeCache` 的请求端：

- `icache.io.dreq.bits.vaddr := s1_pcReg`

当前 `S1` 的请求有效条件是：

- `s1_valid := io.fetchBuffer.canAccept3 && !s2_validReg`

这表示当前实现里，前端只在 fetch buffer 可接收且没有 outstanding cache 请求时继续向 cache 发请求。

### S2: Cache Request Tracking

`S2` 当前不是直接保存指令，而是保存“已经发给 cache 的这次访问”的上下文。

当前寄存器：

- `s2_validReg`
- `s2_pcReg`

当前行为：

- 当 `icache.io.dreq.fire` 时：
  - `s2_validReg := true`
  - `s2_pcReg := s1_pcReg`
- 当 `icache.io.drsp.valid` 时：
  - `s2_validReg := false`

这意味着当前前端把 `S2` 用作“等待 cache 返回”的阶段。

### S3: Returned Instruction + Mini Decode

`S3` 负责锁存 cache 返回的结果，并做最小快速解码。

当前寄存器：

- `s3_validReg`
- `s3_pcReg`
- `s3_instReg`

当前行为：

- 仅当 `icache.io.drsp.valid` 时更新
- 锁存：
  - `s3_pcReg := s2_pcReg`
  - `s3_instReg := icache.io.drsp.bits.data`

`S3` 同时实例化了 `MiniDecode`，当前只支持最小预测信息生成：

- 指令类型：`NONE` / `JAL`
- `predTaken`
- `predPc`

当前仅对 `JAL` 做快速识别：

- 若 `opcode == 1101111`
- 则认为是 `JAL`
- 预测跳转目标为 `pc + J-type immediate`

## Fetch Buffer 接口

当前前端输出到 fetch buffer 的接口不是 `ready-valid`，而是固定的前端输出加空间反馈：

- 前端输出：
  - `valid`
  - `bits.pc`
  - `bits.inst`
  - `bits.pred`
- fetch buffer 反馈：
  - `canAccept3`

`canAccept3` 的语义是：

- buffer 当前至少还能再接收 3 条，不会溢出

当前前端用这个信号来控制是否继续向前推进。

## 当前已实现内容

当前已经完成：

1. `BreezeFrontendConfig`
2. `BreezeFrontend` 内部实例化 `BreezeCache`
3. `S0/S1/S2/S3` 的基础寄存结构
4. `S0` 的 PC 来源优先级选择
5. `S1` 向 cache 发虚拟地址请求
6. `S2` 跟踪 outstanding request
7. `S3` 锁存 cache 返回的 PC 和指令
8. `MiniDecode` 对 `JAL` 的快速识别
9. fetch buffer 输出接口与预测信息结构

## 当前简化约定

当前实现仍然是“先搭骨架，再细化控制”，因此有一些有意保留的简化：

1. 指令宽度先固定为 32 位。
2. 快速预测类型当前只支持 `NONE` 和 `JAL`。
3. 还没有系统性补齐 flush、stall、异常、misaligned、miss 细粒度处理。
4. `S0/S1` 的推进条件目前是阶段性写法，后续还会继续收敛。
5. 当前重点是先把阶段关系和信息流走通，再做严格时序与控制优化。

## 后续方向

接下来继续完善 `BreezeFrontend` 时，应优先关注：

1. 重新收敛 `S0 -> S1 -> S2 -> S3` 的推进和阻塞条件
2. 明确 cache miss 时前端应该如何停住
3. 完善 fetch buffer 的消费语义
4. 扩展 `MiniDecode` 的预测类型
5. 逐步加入异常、flush、预测修正等控制路径
