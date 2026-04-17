# Flow - RV64 五级流水线处理器

这是一个使用 Chisel3 开发的 RISC-V 64-bit 五级流水线处理器项目。

## 当前开发状态

### I-Cache 开发计划

正在开发指令缓存（I-Cache）模块，位于 `design/src/main/scala/cache/Cache.scala`。

#### 开发进展

**仿真环境：**
- ✅ 仿真框架已搭建完成，位于 `sim/ICache/` 目录
  - 基于 Verilator 的 C++ testbench
  - 实现了单在途（single outstanding）内存模型
  - 支持 ready-valid 握手协议
  - 详细计划见 `sim/ICache/PLAN_ICACHE_TB.md`

**参考实现：**
- ✅ 当前 `Cache.scala` 中有一个简单的状态机实现
  - 使用 `NativeMemIO` 接口（仿真专用，位于 `design/src/main/scala/interface/interface.scala`）
  - 演示了通过 speak/listen 模式访问内存的完整流程
  - 采用二状态机：speak 阶段发送地址，listen 阶段接收数据
  - **后续开发会参考这个状态机电路和文件注释**

**下一步：**
- 在参考实现基础上，逐步实现完整的 I-Cache 功能
- 保持使用 `NativeMemIO` 接口以便在仿真环境中验证

#### 已确定的技术参数

**基本配置：**
- 类型：I-Cache（指令缓存，只读）
- 地址空间：64-bit 物理地址（暂不处理虚拟地址转换）
- 容量：8KB
- 组相联度：2-way
- Cache line 大小：32 字节
- 总线数据宽度：64-bit
- 替换策略：随机替换（LFSR）

**设计约束：**
- 阻塞式缓存：单个未命中请求
- 只读：无写入、无写回、无分配策略
- 物理地址访问（后期会添加虚拟地址支持）

#### 开发方法：最小完整路径（三阶段）

**阶段 1 - 基础功能**
- 实现 IDLE 和 REFILL 两状态状态机
- 冷启动 miss → refill → 下次 hit 的完整流程
- 测试：同一地址两次访问（第一次 miss+refill，第二次 hit）

**阶段 2 - 增强功能**
- 实现 4-beat refill（32 字节 / 8 字节 = 4 个 beat）
- 2-way 随机替换逻辑
- 测试：多地址访问、多路填充场景

**阶段 3 - 完善功能**
- Flush/invalidate 接口（支持 fence.i）
- 边界情况处理
- 测试：边界地址、满 cache 替换

#### 接口设计

**CPU 接口（IFetch）：**
- 接口类型：`ICacheIO`（定义在 `interface/interface.scala`）
- 使用标准 Decoupled（ready-valid）协议
- Input: `req` - 请求通道，包含 64-bit 物理地址
- Output: `resp` - 响应通道，返回 32-bit 指令

**内存总线接口（当前使用 NativeMemIO）：**
- 接口类型：`NativeMemIO`（仿真专用，定义在 `interface/interface.scala`）
- 使用标准 Decoupled（ready-valid）协议
- Output: `req` - 请求通道（valid, ready, addr[63:0]）
- Input: `resp` - 响应通道（valid, ready, data[63:0]）
- 辅助方法：
  - `speak(addr)`: 发送地址请求，返回握手成功标志
  - `listen()`: 接收数据响应，返回 (done, data) 元组
  - `initialize()`: 初始化接口信号
- 注：后续可能会添加 Wishbone/AXI 转换层

**控制接口：**
- Input: `flush` (用于 fence.i，后期实现)

#### 地址划分（8KB cache, 2-way, 32B line）

- **Offset** [4:0]: 32 字节 = 5 bits
- **Index** [12:5]: 8KB / 2-way / 32B = 128 sets = 8 bits
- **Tag** [63:13]: 51 bits

#### 测试策略

**仿真环境（已就绪）：**
- 位置：`sim/ICache/`
- 工具：Verilator + C++ testbench
- 内存模型：单在途（single outstanding）请求处理
- 特性：
  - 可配置响应延迟（response_latency，默认 12 周期）
  - beat 间延迟（inter_beat_latency，默认 2 周期）
  - 自动协议检查（背压、握手正确性、数据一致性）
  - VCD 波形生成（可选）
  - 详细统计和超时检测
  - 支持 single/double 测试模式

**编译和运行流程：**

1. **编译 Chisel 代码生成 SystemVerilog：**
   ```bash
   cd /home/chen/FUN/flow/design
   sbt "runMain cache.FireICache"
   ```
   生成的 SystemVerilog 文件会输出到 `sim/ICache/rtl/` 目录。

2. **构建 Verilator 仿真器：**
   ```bash
   cd /home/chen/FUN/flow/sim/ICache
   cmake --build cmake-build-debug -j4
   ```

3. **运行仿真测试：**
   - **Single 模式**（单次访问）：
     ```bash
     ./cmake-build-debug/icache_sim --no-vcd --test=single --single-addr=0x1000 --post-cycles=0
     ```

   - **Double 模式**（两次流水线访问，第二次地址 = 第一次地址 + 4）：
     ```bash
     ./cmake-build-debug/icache_sim --no-vcd --test=double --single-addr=0x1000 --post-cycles=0
     ```

   - **常用参数：**
     - `--test=single|double`：测试模式
     - `--max-cycles=N`：最大仿真周期数
     - `--latency=N`：内存响应延迟（首 beat，默认 12）
     - `--beat-gap=N`：beat 间延迟（默认 2）
     - `--single-addr=N`：起始访问地址
     - `--single-timeout=N`：单次请求超时周期
     - `--post-cycles=N`：测试后额外运行周期（默认 0）
     - `--no-vcd`：禁用 VCD 波形生成（加快仿真）

4. **测试输出解读：**
   - `TB PASS`：测试通过
   - `TB FAIL`：测试失败（会显示失败原因）
   - 统计信息包括：
     - 总周期数、握手次数、burst 完成数
     - 各个访问的延迟统计
     - 中文总结（如：第 1 次访问耗时 X 周期）

**测试方法：**
采用增量测试方法，每完成一个功能点立即编写对应测试：
1. 使用 Verilator 仿真环境验证硬件行为
2. 每次修改代码后重新编译并运行测试
3. 参考 `sim/ICache/PLAN_ICACHE_TB.md` 了解测试环境详细设计

**测试重点：**
- 基础握手协议正确性
- 冷启动 miss → refill → hit 流程
- 4-beat refill 正确性
- 多路替换逻辑
- 流水线连续访问（double 模式）
- 边界情况处理



### GustEngine 仿真环境

**状态：**
- ✅ 已完成 `sim/GustEngine/` Verilator C++ testbench 基础环境
- ✅ 覆盖模式：`sequential`、`backpressure`、`branch`、`misalign`

**目录与文件：**
- `sim/GustEngine/CMakeLists.txt`
- `sim/GustEngine/tb_main.cpp`
- `sim/GustEngine/backend_model.h/.cpp`
- `sim/GustEngine/memory_model.h/.cpp`
- `sim/GustEngine/rtl/filelist.f`
- `sim/GustEngine/DEV_GUSTENGINE_TB_2026-03-06.md`（开发过程记录）

**构建与运行：**
```bash
cd /home/chen/FUN/flow/sim/GustEngine
cmake -S . -B cmake-build-debug
cmake --build cmake-build-debug -j4

# 顺序取指
./cmake-build-debug/gustengine_sim --no-vcd --test=sequential --reset-addr=0x1000 --target-commits=16

# 后端背压
./cmake-build-debug/gustengine_sim --no-vcd --test=backpressure --ready-pattern=110010 --target-commits=16

# 分支重定向
./cmake-build-debug/gustengine_sim --no-vcd --test=branch --branch-cycle=100 --branch-target=0x3000 --branch-post-commits=8 --target-commits=20

# 未对齐异常
./cmake-build-debug/gustengine_sim --no-vcd --test=misalign --reset-addr=0x1002 --target-commits=12
```

## 开发工作流

1. 用户会先自行编写模块代码
2. 在需要帮助时主动请求 Claude 介入
3. 保持设计文档与代码同步

## 项目结构

```
design/
├── src/
│   ├── main/scala/
│   │   ├── cache/      # Cache 模块（开发中）
│   │   ├── core/       # 核心流水线
│   │   ├── interface/  # 接口定义（ICacheIO, NativeMemIO 等）
│   │   ├── mem/        # 内存相关
│   │   ├── wishbone/   # Wishbone 总线
│   │   └── ...
│   └── test/scala/     # 测试用例
├── docs/
│   └── plans/          # 设计文档
└── ...
sim/
└── ICache/             # I-Cache Verilator 仿真环境（已就绪）
    ├── rtl/            # 生成的 SystemVerilog
    ├── memory_model.h/.cpp  # 内存模型实现
    ├── tb_main.cpp     # 测试主程序
    ├── PLAN_ICACHE_TB.md    # 仿真计划文档
    └── CMakeLists.txt  # 构建配置
```

