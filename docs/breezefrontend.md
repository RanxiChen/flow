# BreezeFrontend

本文记录当前 `BreezeFrontend` 的设计目标、流水线划分、已实现内容，以及当前阶段的验证重点。

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

`S3` 同时实例化了 `MiniDecode`，当前已经支持用于快速修正的最小解码信息：

- 指令类型：`JAL` / `BRANCH` / `JALR`
- `predTaken`
- `predPc`

当前 `MiniDecode` 的职责不是独立生成完整预测，而是对流水线带下来的预测结果做有限修正：

- `JAL`：可在 `S3` 直接修正 target
- `BRANCH`：只在“原预测为 taken”时校验并修正 target，不修方向
- `JALR`：只识别类型，不在 `S3` 修 target

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
10. `GShare` 配置下的 `GHR + BTB + PHT` 预测骨架
11. 预测元数据沿 `S2/S3` 和前后端流水传递
12. 后端到前端的 `BTB/PHT/GHR` 训练反馈通路

## 当前简化约定

当前实现仍然是“先搭骨架，再细化控制”，因此有一些有意保留的简化：

1. 指令宽度先固定为 32 位。
2. 当前已经实现 gshare 雏形，但还没有完成系统性的正确性验证。
3. 还没有系统性补齐 flush、stall、异常、misaligned、miss 细粒度处理。
4. `S0/S1` 的推进条件目前是阶段性写法，后续还会继续收敛。
5. 当前重点已经从“把预测骨架接起来”转为“验证预测、修正、训练闭环是否正确”。

## 后续方向

接下来继续完善 `BreezeFrontend` 时，项目级优先级应按下面顺序理解：

1. 先在核心路径上实现 `ecall`，保证测试程序能够正常结束并建立最基本的 trap/退出语义。
2. `ecall` 完成后，接入 `riscv-tests`，先验证基础 `I` 指令集是否正确。
3. 只有在基础 ISA 验证稳定之后，才启用 `GShare` 配置，系统性检查分支预测路径。

因此，前端相关的下一阶段重点不是立刻扩写 `gshare` 用例，而是先为后续验证铺平主路径。等到 `GShare` 进入验证阶段时，再优先关注：

1. `BTB/PHT/GHR` 的 lookup 与 update 语义是否正确
2. `S3` 快速修正规则是否符合当前设计约束
3. `S0 -> S1 -> S2 -> S3` 的推进和阻塞条件是否自洽
4. cache miss 时前端是否能按预期停住

## 期望中的逐周期骨架行为

这一节描述的是后续希望对齐的前端流水行为，不完全等同于当前 `BreezeFrontend.scala` 已经写死的控制逻辑。这里先只讨论默认顺序取指，不考虑 backend redirect，也不考虑 `S3` 快速预测修正。

### 基本口径

前端的目标不是“同一时刻只盯住一条指令”，而是让 PC 在前级继续顺序推进，同时让 cache miss 只阻断真正的 cache 访问。

换句话说：

- `S0` 每拍都按当前规则计算下一条 PC，默认是 `+4`
- `S1` 表示“当前前端想送去 cache 的那个 PC”
- `S2` 表示“已经真正送进 cache、正在等待返回的那个 PC”
- `S3` 表示“本拍拿到 cache 返回、准备送进 fetch buffer 的那个 PC/inst”

当 cache 正在处理 miss 时：

- `S2` 继续保留 miss 的那条请求
- `S1` 仍然可以承载下一条顺序 PC
- 但是 `S1 -> cache` 这条访问通路不使能

因此，`S1` 这个位置不一定被冻结成空，而更像是“前端已经算出了下一条候选 PC，但 cache 当前不能接单”。

### 复位后第一组顺序指令的例子

假设：

- `resetAddr = A`
- 下一条顺序指令是 `B = A + 4`
- 再下一条是 `C = A + 8`
- 刚复位后 icache 为空，因此 `A` 第一次访问一定 miss
- 暂时不考虑跳转，`S0` 默认总是算 `+4`

则理想行为如下。

#### Cycle 0: reset

- `S1` 初始化为 `A`
- `S2/S3` 无效
- cache 没有正在进行的 miss

这一拍只是把起始 PC 装好。

#### Cycle 1: A 进入 S1 并驱动 cache

- `S1` 上的 PC 是 `A`
- `S1 -> cache` 访问使能为 1，前端真正把 `A` 送进 icache
- 同时 `S0` 已经按顺序算出下一条 PC，也就是 `B`

所以这一拍结束时，前端已经知道“如果没有跳转修正，下一拍想看的就是 `B`”。

#### Cycle 2: A 进入 S2，B 进入 S1

- `A` 现在处于 `S2`，表示这条访问正在等 cache 结果
- 由于冷启动，`A` 在 cache 中判定为 miss
- `B` 进入 `S1`
- 但因为 cache 已经开始处理 `A` 的 miss，`B` 这一拍虽然在 `S1`，却不能真正访问 cache

这就是这版骨架里最关键的点：

- `B` 在前端流水里已经存在
- 但 `B` 对 cache 的访问必须被 gating 掉

#### Cycle 3 ~ miss 返回前

- `A` 持续留在 `S2`
- icache 对外发 miss 请求，并等待 refill
- `B` 继续占在 `S1`
- `S1 -> cache` 访问保持关闭
- `S0` 还能继续算出更后继的顺序 PC，但在第一版骨架里，通常只需要先明确 `B` 被卡在 `S1`

这一段时间里，前端外观上应该体现为：

- outstanding miss 是 `A`
- 顺序后继 `B` 已经在入口等着
- 但 cache 不接受 `B`

#### miss 返回当拍

- cache 返回 `A` 所在的 cache line
- `A` 这条请求完成
- 前端解除 `S1 -> cache` 的阻塞条件

在这个边界点，流水会开始重新流动。

#### 返回后一拍: A 进入 S3，B 进入 S2

- `A` 进入 `S3`，这一拍对外形成 `{pc=A, inst=instA}`
- `B` 从 `S1` 推进到 `S2`，开始真正等待自己的 cache 返回
- 如果 `A` 是普通顺序指令，则 `S0` 仍然默认继续生成 `C`
- 如果后续加入快速预测，那么也是从 `A` 在 `S3` 的解码结果来修正后续 PC

所以你前面强调的那句话，可以作为第一版 spec 的核心：

`A` miss 时，不是让整个前端只有 `A` 一条指令；而是 `A` 在 `S2` 等 miss，`B` 已经占在 `S1`，只是 `B` 对 cache 的访问不使能。等 miss 返回后，`A -> S3`，`B -> S2`，流水重新接上。

### 后续用于逐拍监测的关键信号

如果后面要像 `BreezeCacheSpec` 一样写逐周期对齐测试，建议至少观察这些抽象信号：

- `s1_pc`
- `s1_valid`
- `s1_cache_req_en`
- `s2_pc`
- `s2_valid`
- `s2_is_miss_wait`
- `s3_pc`
- `s3_valid`
- `fetchBuffer.valid`
- `fetchBuffer.bits.pc`
- `nextPc`

其中最关键的是把下面两个概念分开：

- `S1` 这个阶段是否被占用
- `S1 -> cache` 这次访问是否真正使能

这两个在 miss 期间不应该被混成一个信号。
