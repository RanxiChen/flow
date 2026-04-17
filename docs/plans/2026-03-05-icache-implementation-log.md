# I-Cache 实现日志

**日期：** 2026-03-05
**状态：** 开发中
**作者：** chen + Claude
**相关文件：** `design/src/main/scala/cache/Cache.scala`

---

## 本次会话目标

在用户已完成基础结构定义的基础上，完成 I-Cache 的完整功能实现，特别是：
1. 实现流水线访问逻辑（TAG检查 + 数据访问）
2. 实现状态机控制的 UPDATE 流程（miss 处理和 refill）
3. 使用 `NativeMemIO` 接口的 `speak/listen` 模式访问内存

---

## 代码结构现状

### 已完成部分（用户编写）

1. **状态机定义**（68-76 行）
   ```scala
   object cache_status extends ChiselEnum {
     val IDLE, RUN, UPDATE = Value
   }
   object update_status extends ChiselEnum {
     val REQ, RESP, REFILL, WAIT = Value
   }
   ```

2. **存储结构定义**（80-94 行）
   - `data_ram0/1`: 2-way 数据 RAM（每个 way 256 项，每项 256 bits）
   - `tag_ram0/1`: 2-way 标签 RAM（每个 way 256 项，每项 51 bits）
   - `valid_bit0/1`: 2-way 有效位（每个 way 256 项，寄存器实现）

3. **地址划分常量**（80-83 行）
   - `ram_index_width = 8`: Index 位宽，对应 256 个 set
   - `block_offset_width = 5`: Offset 位宽，对应 32 字节 cache line

4. **接口使用示例**（96-117 行）
   - 演示了如何使用 `NativeMemIO` 的 `speak/listen` 方法
   - 采用两状态机：speak 阶段发送地址，listen 阶段接收数据
   - **这段代码不应修改，作为后续实现的参考**

5. **用户设计思路**（59-66 行注释）
   ```
   整个电路实现流水线访问cache的过程，分为两个子阶段，TAG检查跟数据访问
   然后是状态机控制update过程
   当cache miss的时候，状态机从流水线访问的状态切换到update状态，
   这个状态该锁存数据访问阶段的资源，TAG的使能端一直保持不可读取状态
   在update状态，要先进行read，然后在refill阶段决定填充到那个way，在这之后一个空周期，确保写回
   基于这个空周期，不在需要ram实现同周期读写的时候进行冲突检测
   最后在一个周期进入流水线访问状态，继续访问下一个地址
   ```

---

## 本次代码修改记录

### 修改 1：完善地址划分常量定义

**文件位置：** Cache.scala:80-95

**修改前：**
```scala
val ram_index_width = 8
val ram_entry_num = 1 << ram_index_width
val block_offset_width = 5
val cache_block_width = 1 << block_offset_width  // 错误：这里是 32，应该是 256
```

**修改后：**
```scala
val ram_index_width = 8                           // Index 宽度：8 bits -> 256 sets
val ram_entry_num = 1 << ram_index_width          // 每个 way 的 entry 数量：256
val block_offset_width = 5                         // Offset 宽度：5 bits -> 32 bytes per line
val cache_block_bytes = 1 << block_offset_width   // Cache line 大小：32 字节
val cache_block_width = cache_block_bytes * 8     // Cache line 位宽：256 bits（用于存储到 RAM）

val inst_address_width = 64                        // 物理地址宽度：64 bits
val tag_width = inst_address_width - ram_index_width - block_offset_width  // Tag 宽度：51 bits
```

**修改原因：**
- **错误修正**：`cache_block_width` 应该是 cache line 的位宽（256 bits），而不是字节数（32）
- **语义明确**：区分 `cache_block_bytes`（字节数）和 `cache_block_width`（位宽）
- **添加注释**：每个常量都添加了注释说明含义和计算依据
- **统一命名**：将 `tag_index_width` 改为 `tag_width`，更符合术语规范

### 修改 2：添加分段注释和示例代码标记

**修改内容：**
- 在存储结构定义前添加 `========== 存储结构定义 ==========` 分隔标记
- 在示例代码前后添加明确标记，说明这段代码仅供参考，不应修改
- 所有注释使用中文，符合项目风格

**修改原因：**
- **代码可读性**：清晰区分不同功能模块（常量定义、存储结构、示例代码）
- **避免误修改**：明确标记示例代码区域，防止后续实现时误删
- **开发指引**：为后续实现者提供清晰的代码导航

---

## 待实现功能（下一步）

基于用户的设计思路和示例代码，需要实现：

### 1. 流水线访问逻辑（RUN 状态）

**Stage 1: 地址解析 + TAG 查询**
```scala
// 从 io.dst.req 接收地址
val req_addr = io.dst.req.bits.addr
val offset = req_addr(block_offset_width-1, 0)
val index = req_addr(ram_index_width + block_offset_width - 1, block_offset_width)
val tag = req_addr(inst_address_width-1, ram_index_width + block_offset_width)

// 同时读取两路的 tag（SyncReadMem 需要一个周期）
val tag0 = tag_ram0.read(index, enable)
val tag1 = tag_ram1.read(index, enable)
```

**Stage 2: TAG 比较 + 数据读取**
```scala
// 下一周期进行 tag 比较
val hit0 = valid_bit0(index_reg) && (tag0 === tag_reg)
val hit1 = valid_bit1(index_reg) && (tag1 === tag_reg)
val cache_hit = hit0 || hit1

// 根据 hit 情况选择数据或进入 UPDATE
when(cache_hit) {
  // 从 data_ram 读取并返回指令
  val data = Mux(hit0, data_ram0.read(...), data_ram1.read(...))
  io.dst.resp.bits.data := data(offset_to_inst_bits)
  io.dst.resp.valid := true.B
}.otherwise {
  // 进入 UPDATE 状态
  cache_statusReg := UPDATE
  // 锁存地址信息用于 refill
}
```

### 2. UPDATE 状态机（miss 处理）

**REQ 子状态：发送内存请求**
```scala
when(update_statusReg === REQ) {
  // 参考示例代码的 speak 用法
  val line_addr = Cat(tag_reg, index_reg, 0.U(block_offset_width.W))
  when(io.commer.speak(line_addr)) {
    update_statusReg := RESP
    beat_cnt := 0.U
  }
}
```

**RESP 子状态：接收数据并写入**
```scala
when(update_statusReg === RESP) {
  // 参考示例代码的 listen 用法
  val (done, data) = io.commer.listen()
  when(done) {
    // 写入 data_ram（需要累积 4 个 beat）
    refill_buffer(beat_cnt) := data
    beat_cnt := beat_cnt + 1.U
    when(beat_cnt === 3.U) {
      update_statusReg := REFILL
    }
  }
}
```

**REFILL 子状态：决定替换哪路**
```scala
when(update_statusReg === REFILL) {
  // 使用 LFSR 随机选择 way
  val victim_way = lfsr(0)  // 取最低位

  // 写入 tag 和 data
  when(victim_way === 0.U) {
    tag_ram0.write(index_reg, tag_reg)
    data_ram0.write(index_reg, Cat(refill_buffer))
    valid_bit0(index_reg) := true.B
  }.otherwise {
    tag_ram1.write(index_reg, tag_reg)
    data_ram1.write(index_reg, Cat(refill_buffer))
    valid_bit1(index_reg) := true.B
  }

  update_statusReg := WAIT
}
```

**WAIT 子状态：空周期确保写回**
```scala
when(update_statusReg === WAIT) {
  // 等待一个周期，确保 SyncReadMem 写入完成
  update_statusReg := REQ  // 重新开始（实际应该返回 RUN 状态）
  cache_statusReg := RUN
}
```

### 3. LFSR 随机替换逻辑

```scala
// 使用 LFSR 生成随机数（多项式：x^16 + x^14 + x^13 + x^11 + 1）
val lfsr = RegInit(1.U(16.W))  // 初始种子不为 0
lfsr := Cat(lfsr(14,0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10))

// 使用最低位选择 way
val victim_way = lfsr(0)
```

### 4. 关键信号和资源锁存

```scala
// 在进入 UPDATE 状态时锁存地址信息
val index_reg = RegInit(0.U(ram_index_width.W))
val tag_reg = RegInit(0.U(tag_width.W))
val offset_reg = RegInit(0.U(block_offset_width.W))

// 在 miss 时锁存
when(cache_hit === false.B && cache_statusReg === RUN) {
  index_reg := index
  tag_reg := tag
  offset_reg := offset
}
```

---

## 实现策略

### 分步实现顺序

1. **第一步**：完成地址解析逻辑（从 io.dst.req 提取 tag/index/offset）
2. **第二步**：实现 TAG 比较和 hit 检测（假设 cache 已填充）
3. **第三步**：实现 UPDATE 状态机的 REQ 和 RESP 子状态（参考示例代码）
4. **第四步**：实现 REFILL 和 WAIT 子状态（包括 LFSR）
5. **第五步**：整合流水线访问和状态机切换逻辑
6. **第六步**：完善握手协议和 ready/valid 信号

### 测试策略

使用 Verilator 仿真环境（`sim/ICache/`）验证：
1. **冷启动测试**：cache 全空，第一次访问 miss → refill → 第二次 hit
2. **多地址测试**：访问不同地址，验证 index 解析正确
3. **替换测试**：填满 2-way，验证随机替换逻辑
4. **边界测试**：地址 0、最大地址等边界情况

---

## 设计决策和注意事项

### 1. SyncReadMem 的时序

- **读延迟**：SyncReadMem 有一个周期的读延迟，需要寄存器缓存地址
- **写时序**：写入在时钟上升沿完成，WAIT 状态确保写入完成

### 2. Beat 拼接顺序

- 内存每次返回 64-bit（8 字节）
- 4 个 beat 拼接成 256-bit cache line
- **拼接顺序**：`Cat(beat[3], beat[2], beat[1], beat[0])` 或相反（需要在测试中确认）

### 3. 地址对齐

- Refill 请求地址需要对齐到 cache line 起始：`Cat(tag, index, 0.U(block_offset_width.W))`
- 清零 offset 位，确保请求整个 cache line

### 4. Ready 信号语义

- **RUN 状态**：`io.dst.req.ready := true.B`（可以接受新请求）
- **UPDATE 状态**：`io.dst.req.ready := false.B`（阻塞，不能接受新请求）

### 5. 初始化时序

- 使用 `io.commer.initialize()` 和 `io.dst.initialize()` 初始化接口
- 这些调用放在模块顶层，确保信号有默认值

---

## 与设计文档的对应关系

本次实现对应设计文档（`2026-03-02-icache-design.md`）的：
- **阶段 1**：基础完整流程（IDLE + REFILL）
- **阶段 2 部分**：4-beat refill 和 2-way 随机替换

暂不实现：
- **阶段 3**：Flush/Invalidate 接口（后续添加）
- 虚拟地址支持、非阻塞、预取等扩展功能

---

## 遗留问题和后续工作

1. **Flush 接口**：当前未实现 `io.dst.flush` 信号处理（需要清空所有 valid 位）
2. **错误处理**：当前假设内存访问总是成功，未处理超时或错误响应
3. **性能优化**：
   - 考虑是否需要 critical word first（先返回请求的指令字）
   - 考虑是否需要流水化 tag 比较
4. **总线适配**：后续需要将 `NativeMemIO` 替换为 Wishbone/AXI 接口

---

## 代码风格约定

1. **注释语言**：中文注释优先，技术术语保持英文
2. **命名规范**：
   - 寄存器：`xxx_reg`（如 `tag_reg`）
   - Wire：直接使用名称（如 `cache_hit`）
   - 状态：大写（如 `IDLE`, `REFILL`）
3. **分段注释**：使用 `// ========== ... ==========` 分隔不同功能模块
4. **修改说明**：每次修改都添加 `// 修改原因：...` 注释

---

## 参考资料

- **接口定义**：`design/src/main/scala/interface/interface.scala`
  - `NativeMemIO`: speak/listen 方法定义
  - `ICacheIO`: CPU 接口定义
- **示例代码**：Cache.scala:96-117 行（speak/listen 使用示例）
- **仿真环境**：`sim/ICache/PLAN_ICACHE_TB.md`

---

## 实现完成总结

### 已实现功能

✅ **完整的 I-Cache 功能**（对应设计文档阶段 1 + 阶段 2）

**1. 流水线访问逻辑（RUN 状态）**
- 地址解析：提取 tag/index/offset（组合逻辑）
- TAG 比较：双路并行比较，hit 检测
- 数据返回：根据 offset 选择 32-bit 指令字（从 256-bit cache line）
- 握手协议：使用 Decoupled 的 ready/valid 信号

**2. UPDATE 状态机（cache miss 处理）**
- **REQ 子状态**：使用 `speak()` 发送 cache line 起始地址
- **RESP 子状态**：使用 `listen()` 接收 4 个 64-bit beat，存入 buffer
- **REFILL 子状态**：LFSR 随机选择 victim way，写入 tag 和 data
- **WAIT 子状态**：空周期确保 SyncReadMem 写入完成

**3. 随机替换策略**
- 16-bit LFSR（多项式：x^16 + x^14 + x^13 + x^11 + 1）
- 使用最低位选择 way 0 或 way 1

**4. 状态管理**
- IDLE → RUN（收到第一个请求）
- RUN → UPDATE（cache miss）
- UPDATE → RUN（refill 完成）

### 编译验证

```bash
$ sbt "runMain cache.FireICache"
[success] Total time: 6 s
```

**生成文件：**
- `sim/ICache/rtl/ICache.sv` (119 KB)
- `sim/ICache/rtl/data_ram_256x256.sv`
- `sim/ICache/rtl/tag_ram_256x51.sv`
- `sim/ICache/rtl/filelist.f`

### 关键设计决策

1. **接口初始化问题**
   - **问题**：`io.dst.initialize()` 尝试写入 Flipped 的输入端口
   - **解决**：移除该调用，只初始化 `io.commer`（所有信号都是输出）
   - **原因**：`io.dst.req` 是 `Flipped(Decoupled(...))`，`req.valid` 和 `req.bits` 是输入

2. **指令字选择逻辑**
   - 使用 `switch` 语句根据 `word_offset` (offset[4:2]) 选择 8 个 32-bit 字之一
   - 每个 cache line 256-bit 包含 8 条指令

3. **Beat 拼接顺序**
   - `Cat(refill_buffer(3), refill_buffer(2), refill_buffer(1), refill_buffer(0))`
   - 高地址 beat 在高位

4. **地址对齐**
   - Refill 请求地址：`Cat(tag_reg, index_reg, 0.U(block_offset_width.W))`
   - 清零 offset 位，确保对齐到 cache line 起始

### 代码统计

- **总行数**：约 200 行（含详细注释）
- **状态机**：3 个主状态（IDLE/RUN/UPDATE），4 个子状态（REQ/RESP/REFILL/WAIT）
- **存储结构**：4 个 SyncReadMem（2 个 tag RAM，2 个 data RAM），2 个 valid bit 向量

---

## 下一步工作

### 测试验证

**使用 Verilator 仿真环境**（`sim/ICache/`）：
1. **基础流程测试**：冷启动 miss → refill → hit
2. **多地址测试**：不同 index 的访问
3. **替换测试**：填满 2-way，验证随机替换
4. **边界测试**：地址 0、最大地址

**测试命令**（预期）：
```bash
cd /home/chen/FUN/flow/sim/ICache
mkdir build && cd build
cmake ..
make
./tb_icache
```

### 后续增强

1. **Flush 接口**（阶段 3）
   - 添加 `io.dst.flush` 信号处理
   - 清空所有 valid 位
   - 用于 `fence.i` 指令

2. **调试信息**
   - 当前有 printf 调试输出，可根据需要调整
   - 可以添加性能计数器（hit rate, miss count）

3. **总线适配**
   - 将 `NativeMemIO` 替换为 Wishbone/AXI 接口
   - 需要添加协议转换层

---

## 变更记录

- **2026-03-05 14:00**：创建实现日志，记录初始代码结构
- **2026-03-05 14:15**：完成地址划分常量定义的完善和注释
- **2026-03-05 14:45**：完成完整 I-Cache 实现
  - 实现流水线访问逻辑（地址解析、TAG 比较、数据返回）
  - 实现 UPDATE 状态机（REQ/RESP/REFILL/WAIT）
  - 实现 LFSR 随机替换策略
  - 修复接口初始化问题（移除 io.dst.initialize()）
  - 编译通过，生成 SystemVerilog 文件
- **2026-03-05 14:50**：更新实现日志，总结完成的功能
