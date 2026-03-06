# Cache.scala 变更记录

**日期：** 2026-03-05
**作者：** chen + Claude
**文件：** `design/src/main/scala/cache/Cache.scala`

---

## 变更概述

完成 I-Cache 的完整实现，从基础结构定义到可编译运行的完整功能模块。

---

## 详细变更

### 1. 修正地址划分常量定义（第 80-95 行）

**变更前：**
```scala
val cache_block_width = 1 << block_offset_width  // 错误：值为 32
```

**变更后：**
```scala
val cache_block_bytes = 1 << block_offset_width   // 32 字节
val cache_block_width = cache_block_bytes * 8     // 256 bits
val tag_width = inst_address_width - ram_index_width - block_offset_width  // 51 bits
```

**修改原因：**
- 修正错误：cache line 位宽应为 256 bits，不是 32
- 语义明确：区分字节数和位宽
- 添加注释：每个常量都有详细说明

---

### 2. 完整实现 Cache 逻辑（第 108-280 行）

**新增内容：**

#### 2.1 流水线寄存器和控制信号
```scala
val addr_reg = RegInit(0.U(inst_address_width.W))
val index_reg = RegInit(0.U(ram_index_width.W))
val tag_reg = RegInit(0.U(tag_width.W))
val offset_reg = RegInit(0.U(block_offset_width.W))
```
**用途：** 锁存地址信息，配合 SyncReadMem 的一周期读延迟

#### 2.2 LFSR 随机替换
```scala
val lfsr = RegInit(0x1234.U(16.W))
lfsr := Cat(lfsr(14,0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10))
```
**用途：** 生成随机数选择 victim way（多项式：x^16+x^14+x^13+x^11+1）

#### 2.3 Refill 缓冲区
```scala
val beat_cnt = RegInit(0.U(2.W))
val refill_buffer = Reg(Vec(4, UInt(64.W)))
```
**用途：** 缓存 4 个 64-bit beat，拼接成 256-bit cache line

#### 2.4 地址解析
```scala
val req_offset = req_addr(block_offset_width - 1, 0)
val req_index = req_addr(block_offset_width + ram_index_width - 1, block_offset_width)
val req_tag = req_addr(inst_address_width - 1, block_offset_width + ram_index_width)
```
**用途：** 从 64-bit 地址中提取 tag/index/offset

#### 2.5 TAG 比较和 Hit 检测
```scala
val tag0_read = tag_ram0.read(req_index, tag_read_enable)
val tag1_read = tag_ram1.read(req_index, tag_read_enable)
val hit0 = valid_bit0(index_reg) && (tag0_read === tag_reg)
val hit1 = valid_bit1(index_reg) && (tag1_read === tag_reg)
val cache_hit = hit0 || hit1
```
**用途：** 双路并行 TAG 比较，检测 cache hit/miss

#### 2.6 主状态机

**IDLE 状态：**
```scala
is(IDLE) {
  io.dst.req.ready := true.B
  when(io.dst.req.fire) {
    cache_statusReg := RUN
    // 锁存地址
  }
}
```
**用途：** 等待第一个请求

**RUN 状态：**
```scala
is(RUN) {
  when(cache_hit) {
    // 返回指令
    val word_offset = offset_reg(block_offset_width - 1, 2)
    // 根据 offset 选择 32-bit 指令字（0-7）
    io.dst.resp.bits.data := inst
    io.dst.resp.valid := true.B
  }.otherwise {
    // 进入 UPDATE
    cache_statusReg := UPDATE
  }
}
```
**用途：** 正常流水线访问，hit 返回数据，miss 进入 UPDATE

**UPDATE 状态：**

- **REQ 子状态：** 使用 `speak()` 发送 cache line 地址
  ```scala
  val line_addr = Cat(tag_reg, index_reg, 0.U(block_offset_width.W))
  when(io.commer.speak(line_addr)) {
    update_statusReg := RESP
  }
  ```

- **RESP 子状态：** 使用 `listen()` 接收 4 个 beat
  ```scala
  val (done, data) = io.commer.listen()
  when(done) {
    refill_buffer(beat_cnt) := data
    when(beat_cnt === 3.U) {
      update_statusReg := REFILL
    }
  }
  ```

- **REFILL 子状态：** 选择 victim way，写入数据
  ```scala
  val victim_way = lfsr(0)
  val refill_data = Cat(refill_buffer(3), ..., refill_buffer(0))
  when(victim_way === 0.U) {
    tag_ram0.write(index_reg, tag_reg)
    data_ram0.write(index_reg, refill_data)
    valid_bit0(index_reg) := true.B
  }.otherwise { /* way 1 */ }
  ```

- **WAIT 子状态：** 空周期确保写回
  ```scala
  cache_statusReg := RUN
  ```

---

### 3. 修复接口初始化问题（第 104-107 行）

**变更前：**
```scala
io.commer.initialize()
io.dst.initialize()
```

**变更后：**
```scala
io.commer.initialize()
// io.dst.initialize() // 不能调用，因为会尝试写入输入端口
```

**修改原因：**
- `io.dst.req` 是 `Flipped(Decoupled(...))`，其中 `valid` 和 `bits` 是输入端口
- 不能在模块内部写入输入端口，否则编译报错
- `io.commer` 的所有信号都是输出，可以初始化

---

### 4. 保留示例代码作为参考（第 109-134 行）

**变更：** 将原示例代码注释保留

**原因：**
- 该代码演示了 `speak/listen` 的正确用法
- 作为后续维护和学习的参考
- 不影响实际功能

---

## 编译验证

**命令：**
```bash
cd /home/chen/FUN/flow/design
sbt "runMain cache.FireICache"
```

**结果：**
```
[success] Total time: 6 s
```

**生成文件：**
- `sim/ICache/rtl/ICache.sv` (119 KB)
- `sim/ICache/rtl/data_ram_256x256.sv`
- `sim/ICache/rtl/tag_ram_256x51.sv`
- `sim/ICache/rtl/filelist.f`

---

## 代码统计

- **总行数：** 约 280 行（含注释）
- **新增代码：** 约 170 行
- **注释行：** 约 80 行
- **状态机：** 3 个主状态 + 4 个子状态

---

## 功能覆盖

### ✅ 已实现

- [x] 地址解析（tag/index/offset）
- [x] 双路 TAG 比较
- [x] Cache hit 检测
- [x] 指令字选择（32-bit from 256-bit line）
- [x] 4-beat refill（64-bit × 4 = 256-bit）
- [x] LFSR 随机替换
- [x] 握手协议（ready/valid）
- [x] 状态机（IDLE/RUN/UPDATE）
- [x] NativeMemIO 接口（speak/listen）

### ⏸️ 未实现（后续工作）

- [ ] Flush/Invalidate 接口（用于 fence.i）
- [ ] 性能计数器（hit rate, miss count）
- [ ] 总线适配层（Wishbone/AXI）

---

## 设计要点

1. **SyncReadMem 时序：** 读延迟一周期，需要寄存器缓存地址
2. **地址对齐：** Refill 请求清零 offset 位
3. **Beat 拼接：** 高地址 beat 在高位 `Cat(beat[3], ..., beat[0])`
4. **阻塞模式：** UPDATE 状态时 `ready = false`，不接受新请求
5. **WAIT 状态：** 空周期避免读写冲突

---

## 测试建议

**使用 Verilator 仿真环境：**

```bash
cd /home/chen/FUN/flow/sim/ICache
mkdir -p build && cd build
cmake ..
make
./tb_icache
```

**测试重点：**
1. 冷启动 miss → refill → hit 流程
2. 多地址访问（不同 index）
3. 随机替换验证（填满 2-way）
4. 边界地址测试

---

## 参考文档

- **设计文档：** `docs/plans/2026-03-02-icache-design.md`
- **实现日志：** `docs/plans/2026-03-05-icache-implementation-log.md`
- **接口定义：** `design/src/main/scala/interface/interface.scala`
- **仿真环境：** `sim/ICache/PLAN_ICACHE_TB.md`

---

## 总结

本次实现完成了 I-Cache 的核心功能，对应设计文档的阶段 1 和阶段 2。代码已编译通过并生成 SystemVerilog，可以进入仿真验证阶段。后续可以根据测试结果进行调试和优化，并添加 flush 等扩展功能。
