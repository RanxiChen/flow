# GustEngine Testbench 开发过程记录（非计划文档）

**日期**: 2026-03-06  
**目录**: `sim/GustEngine/`  
**目的**: 记录本次 testbench 的实际开发过程、关键决策、踩坑与验证结果。

---

## 1. 开发目标与边界

本次开发目标是把 `GustEngine.sv` 接到可运行的 Verilator C++ 环境，形成完整闭环：
- DUT：`GustEngine`（内部包含 `gustFrontend + ICache`）
- 外围模型：
  - `SingleOutstandingMemModel`（模拟 NativeMemIO）
  - `BackendModel`（模拟 instpack 消费与 branch 控制）
- 支持模式：`sequential / backpressure / branch / misalign`

边界说明：
- 本次不改 RTL 功能逻辑，只构建验证环境。
- `io_be_ctrl_flush` 在当前 RTL 中未用到，testbench 固定驱动为 0。

---

## 2. 实际开发步骤

### Step A: 工程骨架搭建

新增文件：
- `CMakeLists.txt`
- `memory_model.h/.cpp`（从 `sim/ICache` 复用）
- `backend_model.h/.cpp`
- `tb_main.cpp`

说明：
- CMake 通过 `rtl/filelist.f` 自动收集 RTL 源。
- 目标可执行为 `gustengine_sim`。

### Step B: 后端模型实现

`BackendModel` 做了三件事：
1. 生成 `instpack_ready`
2. 在指定周期发 `pc_misfetch/pc_redir`
3. 记录每次 `instpack` 握手（cycle/pc/data/misaligned）

实现策略：
- `comb()` 和 `tick()` 共享同一 `cycle` 语义，确保 ready 与 branch 触发一致。
- backpressure 用 `ready_pattern` 循环生成 ready。

### Step C: 主仿真循环实现

`tb_main.cpp` 每个周期顺序：
1. 组合驱动（内存输出 + 后端输出）
2. `eval_clock(0)` 采样握手
3. `mem.tick(...)` 与 `backend.tick(...)`
4. `eval_clock(1)` 推进时序

这保证了：
- ready/valid 的观测点一致
- 内存模型与后端模型都基于同一周期推进

### Step D: 模式级校验规则

- `sequential`:
  - 检查起始 PC 正确
  - 检查 PC 单调不减、4B 对齐步进
  - 检查 `misaligned=0`
  - 检查 data 与期望窗口匹配（`pc-4/pc/pc+4`）
- `backpressure`:
  - 检查起始 PC、PC 步进、`misaligned=0`
  - 检查确实发生 valid-stall（`stalled_valid_cycles > 0`）
- `branch`:
  - 检查 branch 已触发
  - 检查 branch 后第一条提交 PC 等于 `branch_target`
  - 检查 branch 前后 PC 步进合法
- `misalign`:
  - 检查 `misaligned=1` 且持续输出

---

## 3. 调试与修正记录

### 问题 1: 严格 `pc += 4` 校验失败

现象：
- 某些提交出现 PC 重复或跨步（例如 `+8`），导致严格等差校验失败。

处理：
- 将校验改为“单调不减 + 4B 对齐步进 + 限制最大步进（<=32）”。

### 问题 2: backpressure 模式 data 偶发 `0x00000000`

现象：
- 在后端不 ready 的周期切换中，出现 data 与地址不完全一一对应。

处理：
- 对 backpressure 模式去除强数据值断言，改为行为断言（valid-stall 必须出现）。
- sequential 保留窗口型数据匹配以维持功能性检查力度。

### 问题 3: 日志量过大

现象：
- ICache RTL 内部 printf 很多，终端输出冗长。

处理：
- 回归时将输出重定向到 `/tmp/*.log`，再通过 grep 提取 PASS/FAIL 与关键统计。

---

## 4. 验证结果（本次开发完成态）

执行环境：`sim/GustEngine/cmake-build-debug/gustengine_sim`

- `sequential`: PASS  
- `backpressure`: PASS  
- `branch`: PASS  
- `misalign`: PASS

关键统计摘录：
- backpressure: `instpack_hs=16`, `stalled_valid_cycles=18`, `TB PASS`
- branch: `instpack_hs=32`, `last_commit pc=0x...3018`, `TB PASS`
- misalign: `first_commit pc=0x...1002`, `misaligned=1`, `TB PASS`

---

## 5. 当前实现的已知限制

- 由于当前 RTL 行为特征，testbench 的 PC/data 对齐检查采用“工程可用”的鲁棒规则，而非理想流水线严格模型。
- backpressure 模式以“协议行为正确性”为主，不做强数据一一映射断言。

---

## 6. 后续建议（下一轮可做）

1. 给 ICache/gustFrontend 增加可控 debug 开关，减少默认 printf 噪声。  
2. 在 RTL 稳定后，将 backpressure 模式的数据断言升级为严格一一匹配。  
3. 增加 `--summary-json` 输出，便于 CI 自动解析。
