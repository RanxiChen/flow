# I-Cache 设计文档

**日期：** 2026-03-02
**状态：** 设计中（用户自行开发阶段）
**作者：** chen + Claude

---

## 1. 设计目标

为 RV64 五级流水线处理器设计一个简单、可验证的指令缓存（I-Cache），作为处理器前端的基础组件。

### 使用场景
- 集成到 RV64 五级流水线核心
- 前端接口将基于 cache 接口设计（cache 优先，前端适配）

---

## 2. 技术规格

### 2.1 基本参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 类型 | I-Cache | 指令缓存，只读 |
| 地址空间 | 64-bit 物理地址 | 暂不处理虚拟地址 |
| 容量 | 8 KB | 总容量 |
| 组相联度 | 2-way | 两路组相联 |
| Cache line 大小 | 32 字节 | 每个 cache line |
| 总线宽度 | 64-bit | 每次传输 8 字节 |
| 替换策略 | 随机（LFSR） | 简单的随机替换 |

### 2.2 设计约束

- **阻塞式**：同时只处理一个未命中请求
- **只读**：无写入、无写回、无分配写策略
- **物理地址**：当前不处理虚拟地址转换（后期扩展）
- **无流水线**：tag 比较和 data 读取不流水化（简化设计）

### 2.3 地址划分

**假设：64-bit PA，8KB cache，2-way，32B line**

```
 63           13 12        5 4         0
 +-------------+-----------+-----------+
 |     Tag     |   Index   |  Offset   |
 +-------------+-----------+-----------+
    51 bits       8 bits      5 bits
```

- **Offset [4:0]**：32 字节 = 5 bits（定位 line 内字节）
- **Index [12:5]**：8KB / 2-way / 32B = 128 sets = 8 bits
- **Tag [63:13]**：剩余 51 bits

---

## 3. 接口设计

### 3.1 CPU 接口（IFetch）

**输入：**
- `addr: UInt(64.W)` - 物理地址
- `valid: Bool` - 请求有效信号

**输出：**
- `inst: UInt(32.W)` - 返回的指令
- `ready: Bool` - cache 就绪信号（可接受新请求）

**握手协议：**
- CPU 发出 `valid` + `addr`
- Cache 返回 `ready`（当前能否接受请求）
- 当 `valid && ready` 时，请求被接受

### 3.2 内存总线接口（自定义协议）

**请求通道（Cache → Memory）：**
- `mem_req_valid: Bool` - 请求有效
- `mem_req_addr: UInt(64.W)` - 请求地址（cache line 起始地址）

**响应通道（Memory → Cache）：**
- `mem_resp_valid: Bool` - 响应数据有效
- `mem_resp_data: UInt(64.W)` - 响应数据（每个 beat 8 字节）
- `mem_resp_ready: Bool` - cache 准备好接收数据

**Refill 流程：**
1. Cache 发起请求：`mem_req_valid = true`, `mem_req_addr = line_addr`
2. Memory 连续返回 4 个 beat，每个 beat 64-bit（共 32 字节）
3. Cache 使用 `mem_resp_ready` 反压控制

**注：** 后续会添加适配层将此协议转换为 Wishbone/AXI 标准总线协议。

### 3.3 控制接口

**输入：**
- `flush: Bool` - 刷新 cache（用于 fence.i 指令，阶段 3 实现）

---

## 4. 内部结构

### 4.1 存储阵列

**Tag Array：**
- 组织：128 sets × 2 ways
- 内容：`valid (1-bit) + tag (51-bit)`
- 实现：SyncReadMem 或 Reg 数组

**Data Array：**
- 组织：128 sets × 2 ways × 32 bytes
- 内容：指令数据
- 实现：SyncReadMem 或 Reg 数组

### 4.2 状态机

**基础状态（阶段 1）：**
- `IDLE`：等待请求，执行 tag 比较
- `REFILL`：从内存取 cache line

**可能的扩展状态（阶段 2/3）：**
- `LOOKUP`：流水化 tag 比较（可选）
- `WAIT_RESP`：等待最后一个 beat（可选）

### 4.3 关键逻辑

**Hit 检测：**
```scala
val hit_way0 = valid_array(index)(0) && (tag_array(index)(0) === addr_tag)
val hit_way1 = valid_array(index)(1) && (tag_array(index)(1) === addr_tag)
val cache_hit = hit_way0 || hit_way1
```

**Way 选择（随机替换）：**
- 使用 LFSR 生成随机数
- 当 miss 时，随机选择一路进行替换

**Refill 计数器：**
- 计数 4 个 beat（0 → 3）
- 每个 beat 写入 Data Array 的对应位置

---

## 5. 开发方法：最小完整路径（三阶段）

### 阶段 1：基础完整流程

**目标：** 实现能跑通的最简 cache（冷启动 miss → refill → hit）

**功能范围：**
- 2 状态机：IDLE + REFILL
- Tag/Data Array 初始化为空
- 单地址访问流程：
  1. 第一次访问 miss，进入 REFILL 状态
  2. 从内存取回 4 个 beat，填充 cache line
  3. 第二次访问 hit，直接返回

**测试用例：**
```scala
"basic cache flow" in {
  // 1. 第一次访问地址 0x1000，miss → refill
  // 2. 等待 4 个 beat 填充完成
  // 3. 第二次访问地址 0x1000，hit → 立即返回
}
```

**开发顺序：**
1. 定义接口 Bundle
2. 搭建 Tag/Data Array 结构
3. 实现 IDLE 状态（tag 比较 + hit 检测）
4. 实现 REFILL 状态（beat 计数 + 写入）
5. 编写基础测试

---

### 阶段 2：增强 Refill 和替换

**目标：** 完善多 beat refill 和 2-way 替换逻辑

**功能范围：**
- 完整的 4-beat refill 处理
- LFSR 随机 way 选择
- 多地址、多路填充场景

**测试用例：**
```scala
"multi-address access" in {
  // 1. 访问地址 A（way 0）
  // 2. 访问地址 B（way 1）
  // 3. 访问地址 C（替换 way 0 或 way 1）
  // 4. 验证 hit/miss 行为正确
}
```

**开发顺序：**
1. 实现 LFSR 随机数生成器
2. 在 REFILL 状态中集成 way 选择逻辑
3. 验证 4-beat 数据正确写入
4. 编写多地址测试

---

### 阶段 3：边界情况和完善

**目标：** 处理边界情况，添加控制接口

**功能范围：**
- Flush/Invalidate 接口（清空所有 valid 位）
- 边界地址测试（地址 0、最大地址等）
- 连续 miss 处理（虽然是阻塞式，但测试阻塞行为）

**测试用例：**
```scala
"flush operation" in {
  // 1. 填充 cache
  // 2. 发起 flush
  // 3. 验证所有 valid 位清零
  // 4. 再次访问变成 miss
}

"boundary addresses" in {
  // 测试 0x0、0xFFFFFFFF 等边界地址
}
```

**开发顺序：**
1. 添加 flush 逻辑（遍历 valid_array 清零）
2. 编写边界测试
3. 代码审查和优化

---

## 6. 测试策略

### 6.1 测试框架

使用 ChiselSim + ScalaTest：
```scala
class ICacheSpec extends AnyFreeSpec with Matchers with ChiselSim {
  "ICache basic behavior" in {
    simulate(new ICache) { dut =>
      // test code
    }
  }
}
```

### 6.2 测试节点

**增量测试原则：** 每完成一个功能点立即编写对应测试

| 开发节点 | 测试内容 |
|---------|---------|
| 接口定义完成 | 编译通过测试 |
| Tag Array 实现 | 读写测试 |
| IDLE 状态（hit） | 手动填充 cache，验证 hit |
| REFILL 状态 | Mock memory，验证 refill |
| 阶段 1 完成 | 端到端测试（miss → refill → hit） |
| 阶段 2 完成 | 多地址、替换策略测试 |
| 阶段 3 完成 | Flush、边界测试 |

### 6.3 测试辅助工具

**Mock Memory：**
```scala
class MockMemory {
  // 存储预设的指令数据
  // 响应 cache 的 refill 请求，模拟多 beat 返回
}
```

**测试 Helper：**
- `expectHit()`: 验证 cache hit 行为
- `expectMiss()`: 验证 cache miss 行为
- `fillCache()`: 手动填充 cache（用于测试 hit）
- `peekCacheState()`: 检查内部状态（用于调试）

---

## 7. 实现注意事项

### 7.1 Chisel 最佳实践

- 使用 `RegInit` 初始化寄存器
- 使用 `Vec` 管理多路数组
- 避免组合环（组合逻辑不要有反馈）
- 使用 `ChiselEnum` 定义状态机状态

### 7.2 常见陷阱

1. **Beat 拼接顺序**：确认内存返回的 beat 顺序（高字节在前还是低字节在前）
2. **地址对齐**：refill 请求地址需要对齐到 cache line（清零 offset 位）
3. **Valid 位初始化**：上电时所有 valid 位应为 0（全 miss）
4. **Ready 信号**：REFILL 状态时 ready 应为 false（阻塞新请求）

### 7.3 调试建议

- 使用 `printf` 打印关键状态（状态机、hit/miss、refill 进度）
- 使用波形查看器（VCD）观察时序
- 先用简单的测试验证基础逻辑，再增加复杂场景

---

## 8. 后续扩展方向

当基础 I-Cache 稳定后，可以考虑：

1. **虚拟地址支持**：添加 TLB，支持 VIPT（Virtual Index Physical Tag）
2. **非阻塞**：支持多个未命中请求（MSHR）
3. **预取**：顺序预取或 stride 预取
4. **D-Cache**：数据缓存，需要处理写策略（write-through / write-back）
5. **总线适配**：实现 Wishbone / AXI 适配层
6. **L2 Cache**：统一的二级缓存

---

## 9. 参考资料

### 内部文档
- `design/src/main/scala/cache/Cache.scala` - 当前实现
- `design/src/test/scala/core/regfileSpec.scala` - 测试风格参考
- `docs/simple_bus.md` - 总线设计参考

### 经典教材
- *Computer Architecture: A Quantitative Approach* (Hennessy & Patterson)
  - Chapter 2: Memory Hierarchy Design
- *Computer Organization and Design RISC-V Edition*
  - Chapter 5: Memory Hierarchy

### Chisel 资源
- Chisel3 官方文档：https://www.chisel-lang.org/
- ChiselTest 文档：https://github.com/ucb-bar/chiseltest

---

## 10. 开发时间线

| 阶段 | 预计工作量 | 备注 |
|-----|----------|------|
| 阶段 1 | 用户自行开发 | 基础流程，可随时请求帮助 |
| 阶段 2 | 待定 | 取决于阶段 1 进展 |
| 阶段 3 | 待定 | 取决于前两阶段完成情况 |

---

## 变更记录

- **2026-03-02**：初始设计，确定三阶段开发方法
- 用户开始自行开发阶段 1，设计文档待后续更新
