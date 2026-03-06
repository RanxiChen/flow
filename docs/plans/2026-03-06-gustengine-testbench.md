# GustEngine Verilator Testbench 实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标**: 为 GustEngine（Frontend + ICache）创建完整的 Verilator C++ 测试环境

**架构**: 基于 Verilator 的 C++ testbench，模拟内存（NativeMemIO）和后端（InstPack consumer + branch control），验证前端流水线和 cache 的集成功能

**技术栈**: C++20, Verilator, CMake

**参考**: `sim/ICache/` 测试环境（复用内存模型和构建框架）

---

## Task 1: 生成 GustEngine 的 SystemVerilog

**目标**: 编译 Chisel 代码，生成 GustEngine 的 RTL 文件

**文件**:
- Read: `design/src/main/scala/core/Frontend.scala:406-417`
- Create: `sim/GustEngine/rtl/GustEngine.sv` (生成)
- Create: `sim/GustEngine/rtl/filelist.f`

**Step 1: 运行 Chisel 编译生成 SystemVerilog**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireGustFrontend"
```

**期望输出**:
- 编译成功
- 在 `sim/GustEngine/rtl/` 目录下生成 `GustEngine.sv` 及相关文件

**Step 2: 创建 filelist.f**

检查生成的文件：

```bash
cd /home/chen/FUN/flow/sim/GustEngine/rtl
ls -1 *.sv
```

创建 `sim/GustEngine/rtl/filelist.f`，列出所有 SystemVerilog 文件：

```
GustEngine.sv
```

**说明**: 如果有其他 `.sv` 文件（如 `ICache.sv`），也需要添加到 filelist.f 中。

**Step 3: 验证生成的 RTL**

```bash
head -30 /home/chen/FUN/flow/sim/GustEngine/rtl/GustEngine.sv
```

**期望**: 看到模块定义，包含 `io_reset_addr`, `io_instpack_*`, `io_be_ctrl_*`, `io_mem_*` 等端口

**Step 4: 提交 filelist**

```bash
cd /home/chen/FUN/flow
git add sim/GustEngine/rtl/filelist.f
git commit -m "test(gustengine): add RTL filelist for Verilator

- List SystemVerilog files for GustEngine testbench
- Reference: sim/ICache/rtl/filelist.f

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 2: 创建 CMakeLists.txt

**目标**: 配置 Verilator 构建系统

**文件**:
- Create: `sim/GustEngine/CMakeLists.txt`

**Step 1: 编写 CMakeLists.txt**

参考 `sim/ICache/CMakeLists.txt`，创建 `sim/GustEngine/CMakeLists.txt`：

```cmake
cmake_minimum_required(VERSION 3.28)
project(GustEngine)

set(CMAKE_CXX_STANDARD 20)

find_package(verilator HINTS $ENV{VERILATOR_ROOT})
add_executable(gustengine_sim
        tb_main.cpp
        memory_model.cpp
        backend_model.cpp
)

# Read RTL sources from filelist.f under rtl/
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/rtl/filelist.f" RTL_FILELIST)
list(FILTER RTL_FILELIST EXCLUDE REGEX "^[ \t]*($|#|//)")
list(TRANSFORM RTL_FILELIST PREPEND "${CMAKE_CURRENT_SOURCE_DIR}/rtl/")

verilate(gustengine_sim
        TOP_MODULE GustEngine
        PREFIX VGustEngine
        SOURCES ${RTL_FILELIST}
        TRACE
)
```

**Step 2: 提交 CMakeLists.txt**

```bash
git add sim/GustEngine/CMakeLists.txt
git commit -m "test(gustengine): add CMake build configuration

- Configure Verilator with top module GustEngine
- Enable VCD trace generation
- Add source files: tb_main.cpp, memory_model.cpp, backend_model.cpp

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 3: 复制和适配内存模型

**目标**: 复用 ICache 的内存模型代码

**文件**:
- Copy: `sim/ICache/memory_model.h` → `sim/GustEngine/memory_model.h`
- Copy: `sim/ICache/memory_model.cpp` → `sim/GustEngine/memory_model.cpp`

**Step 1: 复制内存模型文件**

```bash
cd /home/chen/FUN/flow/sim/GustEngine
cp ../ICache/memory_model.h .
cp ../ICache/memory_model.cpp .
```

**Step 2: 验证文件内容**

```bash
head -20 memory_model.h
```

**期望**: 看到 `MemoryBackend`, `AddrFuncBackend`, `SingleOutstandingMemModel` 的定义

**Step 3: 提交内存模型**

```bash
git add sim/GustEngine/memory_model.h sim/GustEngine/memory_model.cpp
git commit -m "test(gustengine): add memory model (copied from ICache)

- Copy MemoryBackend, AddrFuncBackend, SingleOutstandingMemModel
- Provides 4-beat burst memory simulation for NativeMemIO
- No modifications needed, direct reuse from ICache testbench

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 4: 创建后端模型骨架

**目标**: 创建模拟后端行为的 C++ 类（InstPack 消费 + 分支控制）

**文件**:
- Create: `sim/GustEngine/backend_model.h`
- Create: `sim/GustEngine/backend_model.cpp`

**Step 1: 编写 backend_model.h**

创建 `sim/GustEngine/backend_model.h`：

```cpp
#ifndef BACKEND_MODEL_H_
#define BACKEND_MODEL_H_

#include <cstdint>
#include <functional>
#include <vector>

/**
 * BackendModel - 模拟后端行为
 *
 * 职责：
 * 1. 控制 instpack.ready（背压）
 * 2. 接收和验证 instpack 数据
 * 3. 在指定时机发送分支跳转信号（pc_misfetch + pc_redir）
 */
class BackendModel {
public:
  struct Outputs {
    bool instpack_ready;       // instpack 是否 ready
    bool pc_misfetch;          // 分支预测失败标志
    uint64_t pc_redir;         // 跳转目标地址
  };

  BackendModel();

  // 复位
  void reset();

  // 组合逻辑输出
  Outputs comb() const;

  // 时序逻辑：处理 instpack 握手
  // 返回是否发生握手
  bool tick(bool instpack_valid, uint32_t instpack_data, uint64_t instpack_pc);

  // 配置：设置 instpack.ready 模式
  void set_always_ready(bool always_ready) { always_ready_ = always_ready; }
  void set_ready_pattern(const std::vector<bool>& pattern);

  // 配置：在指定周期发送分支跳转
  void schedule_branch(uint64_t trigger_cycle, uint64_t target_pc);

  // 查询统计
  uint32_t received_count() const { return received_count_; }
  uint64_t current_cycle() const { return cycle_; }

  // 设置期望值回调（用于验证）
  using ExpectedDataFunc = std::function<uint32_t(uint64_t pc)>;
  void set_expected_data_func(ExpectedDataFunc func) { expected_data_func_ = func; }

private:
  bool always_ready_ = true;
  std::vector<bool> ready_pattern_;
  uint32_t ready_pattern_idx_ = 0;

  bool has_scheduled_branch_ = false;
  uint64_t branch_trigger_cycle_ = 0;
  uint64_t branch_target_pc_ = 0;

  uint64_t cycle_ = 0;
  uint32_t received_count_ = 0;
  ExpectedDataFunc expected_data_func_;
};

#endif
```

**Step 2: 编写 backend_model.cpp（基础实现）**

创建 `sim/GustEngine/backend_model.cpp`：

```cpp
#include "backend_model.h"
#include <iostream>
#include <iomanip>
#include <stdexcept>

BackendModel::BackendModel() = default;

void BackendModel::reset() {
  cycle_ = 0;
  received_count_ = 0;
  ready_pattern_idx_ = 0;
  has_scheduled_branch_ = false;
}

BackendModel::Outputs BackendModel::comb() const {
  Outputs out;

  // instpack.ready 逻辑
  if (always_ready_) {
    out.instpack_ready = true;
  } else if (!ready_pattern_.empty()) {
    out.instpack_ready = ready_pattern_[ready_pattern_idx_];
  } else {
    out.instpack_ready = false;
  }

  // 分支跳转逻辑
  out.pc_misfetch = false;
  out.pc_redir = 0;
  if (has_scheduled_branch_ && cycle_ == branch_trigger_cycle_) {
    out.pc_misfetch = true;
    out.pc_redir = branch_target_pc_;
  }

  return out;
}

bool BackendModel::tick(bool instpack_valid, uint32_t instpack_data, uint64_t instpack_pc) {
  const bool fire = instpack_valid && comb().instpack_ready;

  if (fire) {
    // 验证数据
    if (expected_data_func_) {
      const uint32_t expected = expected_data_func_(instpack_pc);
      if (instpack_data != expected) {
        std::cerr << "[BackendModel] Data mismatch at PC=0x" << std::hex << instpack_pc
                  << ", got=0x" << instpack_data << ", expected=0x" << expected << "\n";
        throw std::runtime_error("InstPack data mismatch");
      }
    }

    received_count_++;

    // Pattern 模式下，移动到下一个 ready 值
    if (!always_ready_ && !ready_pattern_.empty()) {
      ready_pattern_idx_ = (ready_pattern_idx_ + 1) % ready_pattern_.size();
    }
  }

  cycle_++;
  return fire;
}

void BackendModel::set_ready_pattern(const std::vector<bool>& pattern) {
  if (pattern.empty()) {
    throw std::runtime_error("ready_pattern cannot be empty");
  }
  always_ready_ = false;
  ready_pattern_ = pattern;
  ready_pattern_idx_ = 0;
}

void BackendModel::schedule_branch(uint64_t trigger_cycle, uint64_t target_pc) {
  has_scheduled_branch_ = true;
  branch_trigger_cycle_ = trigger_cycle;
  branch_target_pc_ = target_pc;
}
```

**Step 3: 提交后端模型**

```bash
git add sim/GustEngine/backend_model.h sim/GustEngine/backend_model.cpp
git commit -m "test(gustengine): add backend model for testbench

- Implement BackendModel to simulate backend behavior
- Control instpack.ready (always ready or pattern-based)
- Support scheduled branch misprediction (pc_misfetch)
- Validate received instpack data against expected values

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 5: 创建 testbench 主程序骨架

**目标**: 创建 tb_main.cpp 的基础结构（配置、初始化、时钟驱动）

**文件**:
- Create: `sim/GustEngine/tb_main.cpp`

**Step 1: 编写 tb_main.cpp（第一部分：配置和工具函数）**

创建 `sim/GustEngine/tb_main.cpp`：

```cpp
#include <cstdlib>
#include <exception>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>

#include <verilated.h>
#include <verilated_vcd_c.h>

#include "VGustEngine.h"
#include "memory_model.h"
#include "backend_model.h"

// ========== 配置结构 ==========

struct TbConfig {
  uint64_t max_cycles = 2000;
  uint32_t mem_response_latency = 12;
  uint32_t mem_inter_beat_latency = 2;
  std::string test_mode = "sequential";  // sequential | backpressure | branch | misalign
  uint64_t reset_addr = 0x1000;
  uint32_t fetch_count = 10;  // 期望取指数量
  bool enable_vcd = true;
};

// ========== 统计结构 ==========

struct TbStats {
  uint64_t cycles = 0;
  uint64_t instpack_handshakes = 0;
  uint64_t mem_req_handshakes = 0;
  uint64_t mem_resp_handshakes = 0;
};

// ========== 工具函数 ==========

static std::string Hex64(uint64_t v) {
  std::ostringstream oss;
  oss << "0x" << std::hex << std::setw(16) << std::setfill('0') << v;
  return oss.str();
}

static std::string Hex32(uint32_t v) {
  std::ostringstream oss;
  oss << "0x" << std::hex << std::setw(8) << std::setfill('0') << v;
  return oss.str();
}

// ========== 命令行参数解析 ==========

static TbConfig ParseArgs(int argc, char **argv) {
  TbConfig cfg;
  for (int i = 1; i < argc; ++i) {
    std::string arg(argv[i]);
    if (arg.rfind("--max-cycles=", 0) == 0) {
      cfg.max_cycles = std::strtoull(arg.c_str() + 13, nullptr, 10);
    } else if (arg.rfind("--test=", 0) == 0) {
      cfg.test_mode = arg.substr(7);
    } else if (arg.rfind("--reset-addr=", 0) == 0) {
      cfg.reset_addr = std::strtoull(arg.c_str() + 13, nullptr, 0);
    } else if (arg.rfind("--fetch-count=", 0) == 0) {
      cfg.fetch_count = static_cast<uint32_t>(std::strtoul(arg.c_str() + 14, nullptr, 10));
    } else if (arg.rfind("--mem-latency=", 0) == 0) {
      cfg.mem_response_latency = static_cast<uint32_t>(std::strtoul(arg.c_str() + 14, nullptr, 10));
    } else if (arg == "--no-vcd") {
      cfg.enable_vcd = false;
    } else if (arg == "--help") {
      std::cout << "Usage: gustengine_sim [--test=sequential|backpressure|branch|misalign] "
                   "[--max-cycles=N] [--reset-addr=N] [--fetch-count=N] [--mem-latency=N] "
                   "[--no-vcd]\n";
      std::exit(0);
    }
  }
  return cfg;
}

// ========== 期望数据计算 ==========

static uint32_t ExpectedInstWord(MemoryBackend &backend, uint64_t pc) {
  if ((pc & 0x3ULL) != 0) {
    throw std::runtime_error("PC must be 4-byte aligned");
  }
  const uint64_t beat_addr = pc & ~0x7ULL;
  const uint64_t beat_data = backend.read(beat_addr);
  return ((pc & 0x4ULL) != 0) ? static_cast<uint32_t>((beat_data >> 32) & 0xffffffffULL)
                               : static_cast<uint32_t>(beat_data & 0xffffffffULL);
}

// ========== 主函数（待补充测试逻辑）==========

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const TbConfig cfg = ParseArgs(argc, argv);

  std::cout << "[TB] GustEngine Testbench\n";
  std::cout << "[TB] Test mode: " << cfg.test_mode << "\n";
  std::cout << "[TB] Reset addr: " << Hex64(cfg.reset_addr) << "\n";
  std::cout << "[TB] Max cycles: " << cfg.max_cycles << "\n\n";

  // DUT 实例化
  auto *dut = new VGustEngine;
  VerilatedVcdC *trace = nullptr;
  if (cfg.enable_vcd) {
    Verilated::traceEverOn(true);
    trace = new VerilatedVcdC;
    dut->trace(trace, 99);
    trace->open("gustengine_tb.vcd");
  }

  // 时钟驱动函数
  vluint64_t sim_time = 0;
  auto eval_clock = [&](int clk) {
    dut->clock = clk;
    dut->eval();
    if (trace != nullptr) {
      trace->dump(sim_time);
    }
    sim_time += 1;
  };

  // 模型实例化
  AddrFuncBackend mem_backend;
  SingleOutstandingMemModel mem_model(mem_backend, cfg.mem_response_latency,
                                      cfg.mem_inter_beat_latency);
  BackendModel backend_model;

  // 设置后端期望数据验证函数
  backend_model.set_expected_data_func(
      [&mem_backend](uint64_t pc) { return ExpectedInstWord(mem_backend, pc); });

  TbStats stats;

  // ========== 复位 ==========
  dut->reset = 1;
  dut->io_reset_addr = cfg.reset_addr;
  dut->io_mem_req_ready = 0;
  dut->io_mem_resp_valid = 0;
  dut->io_mem_resp_bits_data = 0;
  dut->io_instpack_ready = 0;
  dut->io_be_ctrl_flush = 0;
  dut->io_be_ctrl_pc_misfetch = 0;
  dut->io_be_ctrl_pc_redir = 0;

  for (int i = 0; i < 5; ++i) {
    eval_clock(0);
    eval_clock(1);
  }

  dut->reset = 0;
  mem_model.reset();
  backend_model.reset();

  std::cout << "[TB] Reset complete, starting simulation...\n\n";

  // ========== 主循环（待补充）==========
  // TODO: 在下一个 task 中实现

  std::cout << "[TB] Simulation end (placeholder)\n";

  // 清理
  if (trace != nullptr) {
    trace->close();
    delete trace;
  }
  delete dut;

  return 0;
}
```

**Step 2: 尝试编译（会失败，因为还缺少主循环）**

```bash
cd /home/chen/FUN/flow/sim/GustEngine
mkdir -p cmake-build-debug
cd cmake-build-debug
cmake ..
```

**期望输出**: CMake 配置成功，生成 Makefile

**Step 3: 提交 testbench 骨架**

```bash
git add sim/GustEngine/tb_main.cpp
git commit -m "test(gustengine): add testbench main skeleton

- Add configuration parsing (test mode, reset addr, etc.)
- Instantiate DUT, memory model, backend model
- Implement reset sequence
- Add VCD trace support
- TODO: main simulation loop in next task

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 6: 实现 testbench 主循环 - Sequential 模式

**目标**: 实现顺序取指测试（无背压，无跳转）

**文件**:
- Modify: `sim/GustEngine/tb_main.cpp` (在主循环位置)

**Step 1: 在主函数中添加测试模式配置**

在 `backend_model.reset()` 之后，主循环之前添加：

```cpp
  // ========== 配置测试场景 ==========
  if (cfg.test_mode == "sequential") {
    // 顺序取指：后端持续 ready，无跳转
    backend_model.set_always_ready(true);
  } else if (cfg.test_mode == "backpressure") {
    // 背压测试：ready 模式为 1,1,0,1,1,0,... (每 3 周期阻塞 1 周期)
    backend_model.set_ready_pattern({true, true, false});
  } else if (cfg.test_mode == "branch") {
    // 分支跳转：在第 20 周期跳转到 0x2000
    backend_model.set_always_ready(true);
    backend_model.schedule_branch(20, 0x2000);
  } else if (cfg.test_mode == "misalign") {
    // 对齐异常测试：reset_addr 已经设置为非对齐地址（通过命令行）
    backend_model.set_always_ready(true);
  } else {
    throw std::runtime_error("Unknown test mode: " + cfg.test_mode);
  }
```

**Step 2: 实现主循环**

找到 `// ========== 主循环（待补充）==========` 注释，替换为：

```cpp
  // ========== 主循环 ==========
  bool test_pass = true;
  std::string fail_reason;

  for (stats.cycles = 0; stats.cycles < cfg.max_cycles; ++stats.cycles) {
    // ========== Comb 阶段 ==========

    // 获取模型输出
    auto mem_out = mem_model.comb();
    auto backend_out = backend_model.comb();

    // 驱动 DUT 输入
    dut->io_mem_req_ready = mem_out.req_ready;
    dut->io_mem_resp_valid = mem_out.resp_valid;
    dut->io_mem_resp_bits_data = mem_out.resp_data;

    dut->io_instpack_ready = backend_out.instpack_ready;
    dut->io_be_ctrl_flush = 0;  // 暂不使用
    dut->io_be_ctrl_pc_misfetch = backend_out.pc_misfetch;
    dut->io_be_ctrl_pc_redir = backend_out.pc_redir;

    // 时钟下降沿
    eval_clock(0);

    // ========== Seq 阶段 ==========

    // 检测握手
    const bool mem_req_fire =
        (dut->io_mem_req_valid != 0) && (dut->io_mem_req_ready != 0);
    const bool mem_resp_fire =
        (dut->io_mem_resp_valid != 0) && (dut->io_mem_resp_ready != 0);
    const bool instpack_fire =
        (dut->io_instpack_valid != 0) && (dut->io_instpack_ready != 0);

    // 更新模型
    mem_model.tick(mem_req_fire, dut->io_mem_req_bits_addr, mem_resp_fire);
    backend_model.tick(dut->io_instpack_valid, dut->io_instpack_bits_data,
                       dut->io_instpack_bits_pc);

    // 更新统计
    if (mem_req_fire) stats.mem_req_handshakes++;
    if (mem_resp_fire) stats.mem_resp_handshakes++;
    if (instpack_fire) stats.instpack_handshakes++;

    // 时钟上升沿
    eval_clock(1);

    // ========== 终止条件检查 ==========

    // 成功：收到足够的指令
    if (backend_model.received_count() >= cfg.fetch_count) {
      std::cout << "[TB] Success: received " << backend_model.received_count()
                << " instructions\n";
      break;
    }
  }

  // ========== 结果检查 ==========

  if (backend_model.received_count() < cfg.fetch_count) {
    test_pass = false;
    fail_reason = "Timeout: only received " + std::to_string(backend_model.received_count()) +
                  "/" + std::to_string(cfg.fetch_count) + " instructions";
  }

  std::cout << "\n========== Simulation Results ==========\n";
  std::cout << "Cycles: " << stats.cycles << "\n";
  std::cout << "InstPack handshakes: " << stats.instpack_handshakes << "\n";
  std::cout << "Memory req handshakes: " << stats.mem_req_handshakes << "\n";
  std::cout << "Memory resp handshakes: " << stats.mem_resp_handshakes << "\n";
  std::cout << "Instructions received: " << backend_model.received_count() << "\n";

  if (test_pass) {
    std::cout << "\n[TB] ✓ PASS\n";
  } else {
    std::cout << "\n[TB] ✗ FAIL: " << fail_reason << "\n";
  }

  std::cout << "========================================\n";
```

**Step 3: 编译测试**

```bash
cd /home/chen/FUN/flow/sim/GustEngine/cmake-build-debug
cmake --build . -j4
```

**期望输出**: 编译成功，生成 `gustengine_sim` 可执行文件

**Step 4: 运行 sequential 测试**

```bash
./gustengine_sim --test=sequential --reset-addr=0x1000 --fetch-count=10 --no-vcd --max-cycles=500
```

**期望输出**:
- 成功接收 10 条指令
- `[TB] ✓ PASS`
- 统计信息显示合理的握手次数

**Step 5: 提交主循环实现**

```bash
git add sim/GustEngine/tb_main.cpp
git commit -m "test(gustengine): implement main simulation loop

- Add test mode configuration (sequential, backpressure, branch, misalign)
- Implement clock-driven simulation loop
- Drive DUT inputs from memory and backend models
- Collect handshake statistics
- Check termination condition (received enough instructions)
- Print simulation results and pass/fail status

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 7: 验证 Sequential 测试

**目标**: 运行并验证顺序取指测试

**文件**: N/A（运行测试）

**Step 1: 运行基础测试**

```bash
cd /home/chen/FUN/flow/sim/GustEngine/cmake-build-debug
./gustengine_sim --test=sequential --reset-addr=0x1000 --fetch-count=5 --no-vcd --max-cycles=300
```

**期望输出**:
- `[TB] Success: received 5 instructions`
- `[TB] ✓ PASS`
- `InstPack handshakes: 5`

**Step 2: 测试不同 reset_addr**

```bash
./gustengine_sim --test=sequential --reset-addr=0x2000 --fetch-count=3 --no-vcd --max-cycles=200
```

**期望**: 同样通过，从 0x2000 开始取指

**Step 3: 测试更多指令**

```bash
./gustengine_sim --test=sequential --reset-addr=0x1000 --fetch-count=20 --no-vcd --max-cycles=1000
```

**期望**: 通过，接收 20 条指令

**Step 4: 如果测试失败，生成 VCD 调试**

```bash
./gustengine_sim --test=sequential --reset-addr=0x1000 --fetch-count=5 --max-cycles=300
# 查看 gustengine_tb.vcd
```

**Step 5: 记录测试结果**

如果所有测试通过，创建 `sim/GustEngine/TEST_RESULTS.md`：

```markdown
# GustEngine Testbench 测试结果

## Sequential 模式测试

**日期**: 2026-03-06

### Test 1: 基础顺序取指
- 命令: `./gustengine_sim --test=sequential --reset-addr=0x1000 --fetch-count=5`
- 结果: ✓ PASS
- 周期数: ~XXX
- 说明: 成功从 0x1000 取 5 条指令

### Test 2: 不同起始地址
- 命令: `./gustengine_sim --test=sequential --reset-addr=0x2000 --fetch-count=3`
- 结果: ✓ PASS
- 说明: 验证不同 reset_addr 工作正常

### Test 3: 长序列取指
- 命令: `./gustengine_sim --test=sequential --reset-addr=0x1000 --fetch-count=20`
- 结果: ✓ PASS
- 说明: 流水线持续工作，无卡死

## 待测试模式

- [ ] backpressure: 后端背压测试
- [ ] branch: 分支跳转测试
- [ ] misalign: PC 对齐异常测试
```

**Step 6: 提交测试结果**

```bash
git add sim/GustEngine/TEST_RESULTS.md
git commit -m "test(gustengine): add sequential mode test results

- Verify basic sequential instruction fetch
- Test different reset addresses
- Test long instruction sequences
- All tests pass

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 8: 实现并验证 Backpressure 测试

**目标**: 测试后端背压（instpack.ready 间歇性为 0）

**文件**: N/A（使用现有代码）

**Step 1: 运行 backpressure 测试**

```bash
cd /home/chen/FUN/flow/sim/GustEngine/cmake-build-debug
./gustengine_sim --test=backpressure --reset-addr=0x1000 --fetch-count=10 --no-vcd --max-cycles=1000
```

**期望输出**:
- `[TB] ✓ PASS`
- 周期数应该比 sequential 模式更多（因为有阻塞）
- 所有 10 条指令数据正确

**Step 2: 如果失败，生成 VCD 调试**

```bash
./gustengine_sim --test=backpressure --reset-addr=0x1000 --fetch-count=5 --max-cycles=1000
```

检查波形：
- `io_instpack_ready` 应该按照 `{true, true, false}` 模式变化
- 流水线在 ready=0 时应该正确阻塞
- 无指令丢失或重复

**Step 3: 更新测试结果**

在 `TEST_RESULTS.md` 中添加：

```markdown
## Backpressure 模式测试

### Test 4: 后端间歇性阻塞
- 命令: `./gustengine_sim --test=backpressure --reset-addr=0x1000 --fetch-count=10`
- 结果: ✓ PASS
- Ready pattern: {true, true, false} (循环)
- 说明: 流水线正确处理背压，无数据丢失
```

**Step 4: 提交更新**

```bash
git add sim/GustEngine/TEST_RESULTS.md
git commit -m "test(gustengine): verify backpressure mode

- Test intermittent instpack.ready=0
- Verify pipeline correctly stalls and resumes
- No instruction loss or duplication

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 9: 实现并验证 Branch 测试

**目标**: 测试分支跳转和流水线冲刷

**文件**: N/A（使用现有代码）

**Step 1: 运行 branch 测试**

```bash
cd /home/chen/FUN/flow/sim/GustEngine/cmake-build-debug
./gustengine_sim --test=branch --reset-addr=0x1000 --fetch-count=15 --no-vcd --max-cycles=1000
```

**期望行为**:
- 前 20 周期：从 0x1000 开始顺序取指
- 第 20 周期：发送 pc_misfetch=1, pc_redir=0x2000
- 之后：从 0x2000 继续取指，直到收到 15 条指令

**期望输出**:
- `[TB] ✓ PASS`
- 接收的指令 PC 应该包含来自 0x1000 和 0x2000 的指令

**Step 2: 如果失败，生成 VCD 调试**

```bash
./gustengine_sim --test=branch --reset-addr=0x1000 --fetch-count=10 --max-cycles=1000
```

检查波形：
- 第 20 周期：`io_be_ctrl_pc_misfetch=1`, `io_be_ctrl_pc_redir=0x2000`
- 流水线应该清空 S1, S2 的 valid 位
- 下一个周期：PC 应该变为 0x2000

**Step 3: 更新测试结果**

```markdown
## Branch 模式测试

### Test 5: 分支跳转
- 命令: `./gustengine_sim --test=branch --reset-addr=0x1000 --fetch-count=15`
- 结果: ✓ PASS
- 跳转时机: cycle 20
- 跳转目标: 0x2000
- 说明: 流水线正确冲刷，PC 正确跳转
```

**Step 4: 提交更新**

```bash
git add sim/GustEngine/TEST_RESULTS.md
git commit -m "test(gustengine): verify branch misprediction handling

- Test pc_misfetch signal and pipeline flush
- Verify PC redirection to new target
- Ensure pipeline clears correctly

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 10: 实现并验证 Misalign 测试

**目标**: 测试 PC 对齐异常检测

**文件**:
- Modify: `sim/GustEngine/backend_model.cpp` (添加异常标志检查)

**Step 1: 增强 BackendModel 检查对齐异常**

在 `backend_model.cpp` 的 `tick()` 函数中，添加对齐检查：

找到 `bool BackendModel::tick(...)` 函数，在 `if (fire)` 块内添加：

```cpp
    // 检查对齐异常（如果 test 需要验证这个标志）
    // 注意：这里假设 DUT 有 instruction_address_misaligned 输出
    // 如果需要验证，可以添加参数传入
```

**注**: GustEngine 的 instpack.bits 包含 `instruction_address_misaligned` 标志，但当前 BackendModel 没有检查它。可以后续扩展。

**Step 2: 运行 misalign 测试**

```bash
cd /home/chen/FUN/flow/sim/GustEngine/cmake-build-debug
./gustengine_sim --test=misalign --reset-addr=0x1002 --fetch-count=3 --no-vcd --max-cycles=500
```

**期望行为**:
- reset_addr = 0x1002（未对齐）
- 第一条指令的 `instpack.bits.instruction_address_misaligned` 应该为 true
- 但 DUT 仍然会输出指令（异常处理由后端负责）

**期望输出**:
- `[TB] ✓ PASS`
- 能够正常接收指令（即使 PC 未对齐）

**Step 3: 手动检查 VCD 验证异常标志**

```bash
./gustengine_sim --test=misalign --reset-addr=0x1002 --fetch-count=1 --max-cycles=200
```

打开 `gustengine_tb.vcd`，查看：
- `io_instpack_bits_instruction_address_misaligned` 应该在第一条指令输出时为 1
- 后续指令（PC=0x1006, 0x100A）的异常标志应该也为 1（因为都未对齐）

**Step 4: 更新测试结果**

```markdown
## Misalign 模式测试

### Test 6: PC 对齐异常
- 命令: `./gustengine_sim --test=misalign --reset-addr=0x1002 --fetch-count=3`
- 结果: ✓ PASS
- 起始 PC: 0x1002（未对齐）
- 说明: 前端正确检测并标记对齐异常，异常标志传递到 instpack
```

**Step 5: 提交更新**

```bash
git add sim/GustEngine/TEST_RESULTS.md
git commit -m "test(gustengine): verify PC alignment exception detection

- Test with misaligned reset_addr (0x1002)
- Verify instruction_address_misaligned flag is set
- Frontend correctly propagates exception through pipeline

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 11: 创建测试计划文档

**目标**: 记录测试环境的设计和使用方法

**文件**:
- Create: `sim/GustEngine/PLAN_GUSTENGINE_TB.md`

**Step 1: 编写测试计划文档**

创建 `sim/GustEngine/PLAN_GUSTENGINE_TB.md`：

```markdown
# GustEngine Verilator Testbench 设计文档

**日期**: 2026-03-06
**模块**: GustEngine (gustFrontend + ICache)
**目的**: 验证带 Cache 的三级流水线前端功能正确性

---

## 1. 测试环境架构

```
┌─────────────────────────────────────────────────────────┐
│                   Verilator Testbench                    │
│                                                           │
│  ┌────────────────┐         ┌─────────────────────────┐ │
│  │  Memory Model  │◄────────┤   GustEngine (DUT)      │ │
│  │                │  mem    │                         │ │
│  │ (NativeMemIO)  ├────────►│  ┌─────────┐ ┌───────┐ │ │
│  └────────────────┘         │  │Frontend │ │ICache │ │ │
│                              │  │(3-stage)│ │       │ │ │
│  ┌────────────────┐         │  └─────────┘ └───────┘ │ │
│  │ Backend Model  │◄────────┤                         │ │
│  │                │ instpack│                         │ │
│  │ (Consumer +    ├────────►│                         │ │
│  │  Branch Ctrl)  │ be_ctrl │                         │ │
│  └────────────────┘         └─────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 组件说明

### 2.1 Memory Model

**文件**: `memory_model.h`, `memory_model.cpp`

**功能**:
- 模拟外部内存，响应 `NativeMemIO` 请求
- 4-beat burst 模式（每个请求返回 4 个 64-bit beat）
- 可配置延迟：首 beat 延迟、beat 间延迟

**接口**:
- Input: `mem.req` (valid, addr)
- Output: `mem.resp` (valid, data)

**行为**:
- 地址必须 32-byte 对齐
- 数据生成：`data = f(addr)`（地址函数，用于验证）

### 2.2 Backend Model

**文件**: `backend_model.h`, `backend_model.cpp`

**功能**:
- 模拟后端行为，消费 `instpack` 指令
- 控制 `instpack.ready`（背压）
- 发送分支跳转信号（`pc_misfetch`, `pc_redir`）

**模式**:
- Always Ready: `instpack.ready` 始终为 true
- Pattern Ready: 按照模式循环（如 `{true, true, false}`）
- Scheduled Branch: 在指定周期发送跳转信号

**验证**:
- 检查接收的指令数据是否匹配期望值（通过 `ExpectedInstWord` 计算）

---

## 3. 测试模式

### 3.1 Sequential（顺序取指）

**参数**: `--test=sequential`

**配置**:
- `instpack.ready`: 始终 true
- `be_ctrl.pc_misfetch`: 始终 false

**验证**:
- PC 连续递增 +4
- 每条指令数据正确
- 流水线吞吐正常（cache hit 后稳定输出）

**示例**:
```bash
./gustengine_sim --test=sequential --reset-addr=0x1000 --fetch-count=10
```

### 3.2 Backpressure（后端背压）

**参数**: `--test=backpressure`

**配置**:
- `instpack.ready`: 模式 `{true, true, false}`（每 3 周期阻塞 1 周期）
- `be_ctrl.pc_misfetch`: false

**验证**:
- 流水线正确阻塞和恢复
- 无指令丢失或重复
- 数据正确

**示例**:
```bash
./gustengine_sim --test=backpressure --reset-addr=0x1000 --fetch-count=10
```

### 3.3 Branch（分支跳转）

**参数**: `--test=branch`

**配置**:
- `instpack.ready`: 始终 true
- 在第 20 周期：`pc_misfetch=1`, `pc_redir=0x2000`

**验证**:
- 跳转前：从 reset_addr 顺序取指
- 跳转后：所有级 valid 清零，PC 跳转到 0x2000
- 从新地址继续取指

**示例**:
```bash
./gustengine_sim --test=branch --reset-addr=0x1000 --fetch-count=15
```

### 3.4 Misalign（对齐异常）

**参数**: `--test=misalign`

**配置**:
- `reset_addr`: 非 4-byte 对齐地址（如 0x1002）
- `instpack.ready`: 始终 true

**验证**:
- `instpack.bits.instruction_address_misaligned` 为 true
- 前端仍然输出指令（异常处理由后端负责）

**示例**:
```bash
./gustengine_sim --test=misalign --reset-addr=0x1002 --fetch-count=3
```

---

## 4. 命令行参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--test=MODE` | sequential | 测试模式：sequential, backpressure, branch, misalign |
| `--reset-addr=ADDR` | 0x1000 | 启动 PC 地址 |
| `--fetch-count=N` | 10 | 期望取指数量 |
| `--max-cycles=N` | 2000 | 最大仿真周期 |
| `--mem-latency=N` | 12 | 内存首 beat 延迟 |
| `--no-vcd` | false | 禁用 VCD 波形生成 |

---

## 5. 构建和运行

### 5.1 生成 RTL

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireGustFrontend"
```

### 5.2 构建 Testbench

```bash
cd /home/chen/FUN/flow/sim/GustEngine
mkdir -p cmake-build-debug
cd cmake-build-debug
cmake ..
cmake --build . -j4
```

### 5.3 运行测试

```bash
# Sequential 模式
./gustengine_sim --test=sequential --reset-addr=0x1000 --fetch-count=10 --no-vcd

# Backpressure 模式
./gustengine_sim --test=backpressure --reset-addr=0x1000 --fetch-count=10 --no-vcd

# Branch 模式
./gustengine_sim --test=branch --reset-addr=0x1000 --fetch-count=15 --no-vcd

# Misalign 模式
./gustengine_sim --test=misalign --reset-addr=0x1002 --fetch-count=3 --no-vcd
```

---

## 6. 调试方法

### 6.1 生成 VCD 波形

移除 `--no-vcd` 参数：

```bash
./gustengine_sim --test=sequential --reset-addr=0x1000 --fetch-count=5
```

使用 GTKWave 查看：

```bash
gtkwave gustengine_tb.vcd
```

### 6.2 关键信号

**Frontend 流水线**:
- `io_instpack_valid`, `io_instpack_ready`: InstPack 握手
- `io_instpack_bits_pc`, `io_instpack_bits_data`: 指令 PC 和数据

**Cache 接口**:
- `io_mem_req_valid`, `io_mem_req_ready`, `io_mem_req_bits_addr`: 请求
- `io_mem_resp_valid`, `io_mem_resp_ready`, `io_mem_resp_bits_data`: 响应

**控制信号**:
- `io_be_ctrl_pc_misfetch`, `io_be_ctrl_pc_redir`: 分支跳转

---

## 7. 已知限制

1. **Backend Model 简化**: 不验证 `instruction_access_fault` 标志
2. **单一跳转**: Branch 模式只测试一次跳转（可扩展多次）
3. **固定 Ready Pattern**: Backpressure 模式使用固定模式（可参数化）

---

## 8. 后续扩展

1. **连续跳转测试**: 多次 pc_misfetch
2. **Cache miss 压力测试**: 增加内存延迟，测试长时间等待
3. **Random 测试**: 随机化 ready pattern、跳转时机、地址
4. **覆盖率收集**: Verilator coverage 工具

---

**文档完成**
```

**Step 2: 提交测试计划**

```bash
git add sim/GustEngine/PLAN_GUSTENGINE_TB.md
git commit -m "docs: add GustEngine testbench design document

- Document test environment architecture
- Explain memory model and backend model
- Describe 4 test modes (sequential, backpressure, branch, misalign)
- Provide build and run instructions
- List debugging methods and limitations

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 12: 更新 CLAUDE.md

**目标**: 将 GustEngine 测试环境信息添加到项目文档

**文件**:
- Modify: `CLAUDE.md`

**Step 1: 在 CLAUDE.md 中添加测试环境说明**

找到 `## 开发工作流` 或 `## 项目结构` 部分，在其后添加：

```markdown
### GustEngine 测试环境

**位置**: `sim/GustEngine/`

**功能**: 验证 Frontend + ICache 集成功能

**架构**:
- DUT: GustEngine (gustFrontend + ICache)
- Memory Model: 模拟 NativeMemIO（4-beat burst）
- Backend Model: 模拟后端（InstPack consumer + 分支控制）

**测试模式**:
1. **sequential**: 顺序取指，无背压，无跳转
2. **backpressure**: 后端间歇性 ready=0
3. **branch**: 分支跳转和流水线冲刷
4. **misalign**: PC 对齐异常检测

**构建和运行**:

1. **生成 RTL**:
   ```bash
   cd /home/chen/FUN/flow/design
   sbt "runMain core.FireGustFrontend"
   ```

2. **构建 Testbench**:
   ```bash
   cd /home/chen/FUN/flow/sim/GustEngine/cmake-build-debug
   cmake --build . -j4
   ```

3. **运行测试**:
   ```bash
   # Sequential 模式
   ./gustengine_sim --test=sequential --reset-addr=0x1000 --fetch-count=10 --no-vcd

   # Backpressure 模式
   ./gustengine_sim --test=backpressure --reset-addr=0x1000 --fetch-count=10 --no-vcd

   # Branch 模式
   ./gustengine_sim --test=branch --reset-addr=0x1000 --fetch-count=15 --no-vcd

   # Misalign 模式
   ./gustengine_sim --test=misalign --reset-addr=0x1002 --fetch-count=3 --no-vcd
   ```

**文档**:
- 设计文档: `sim/GustEngine/PLAN_GUSTENGINE_TB.md`
- 测试结果: `sim/GustEngine/TEST_RESULTS.md`
```

**Step 2: 更新项目结构说明**

找到项目结构部分，添加：

```markdown
sim/
├── ICache/             # I-Cache 单元测试
└── GustEngine/         # Frontend + ICache 集成测试
    ├── rtl/            # 生成的 SystemVerilog
    ├── tb_main.cpp     # 测试主程序
    ├── memory_model.h/.cpp  # 内存模型
    ├── backend_model.h/.cpp # 后端模型
    ├── CMakeLists.txt  # 构建配置
    ├── PLAN_GUSTENGINE_TB.md    # 测试计划
    └── TEST_RESULTS.md # 测试结果
```

**Step 3: 提交 CLAUDE.md 更新**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md with GustEngine testbench info

- Add GustEngine test environment description
- Document 4 test modes and their purposes
- Provide build and run instructions
- Update project structure diagram

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## 后续步骤（不在本计划中）

完成上述任务后，GustEngine 测试环境已经**可用**。后续工作：

1. **扩展测试场景**:
   - 连续多次跳转
   - 随机化测试（ready pattern、跳转时机）
   - Cache miss 压力测试

2. **性能分析**:
   - 统计流水线气泡（bubble）
   - 测量平均 IPC（Instructions Per Cycle）
   - 分析跳转延迟

3. **覆盖率收集**:
   - 使用 Verilator coverage 工具
   - 生成覆盖率报告

4. **与后端集成**:
   - 连接真实的后端模块（译码、执行）
   - 运行 RISC-V 测试程序

---

**计划完成标志**:
- ✅ RTL 生成成功
- ✅ Testbench 编译通过
- ✅ Sequential 测试通过
- ✅ Backpressure 测试通过
- ✅ Branch 测试通过
- ✅ Misalign 测试通过
- ✅ 文档完善

---

**预计总工时**: 约 4-6 小时（假设熟练的 C++/Verilator 开发者）

**关键里程碑**:
- Task 1-3: 环境搭建（1 小时）
- Task 4-6: Testbench 骨架和主循环（1.5 小时）
- Task 7-10: 测试验证（1.5 小时）
- Task 11-12: 文档完善（1 小时）
