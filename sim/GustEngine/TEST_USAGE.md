# GustEngine 测试能力与使用说明

## 1. 目前可以实现的测试

当前 `gustengine_sim` 支持 4 类测试模式：

1. `sequential`（顺序取指）
- 用途：验证前端在无背压、无分支时能持续提交指令。
- 覆盖点：
  - `instpack` 正常握手
  - PC 从 `reset_addr` 开始前进
  - `misaligned` 标志应为 0
  - 指令数据与内存模型窗口匹配

2. `backpressure`（后端背压）
- 用途：验证后端 `ready` 间歇拉低时，前端/缓存链路不死锁。
- 覆盖点：
  - 持续有 `instpack` 提交
  - 出现有效背压周期（`stalled_valid_cycles > 0`）
  - PC 前进行为仍合法（不倒退，4B 对齐步进）

3. `branch`（分支重定向）
- 用途：验证后端触发 `pc_misfetch + pc_redir` 后，提交流切换到新 PC 路径。
- 覆盖点：
  - 指定周期触发 branch
  - branch 后首条提交 PC 等于 `branch_target`
  - branch 前后提交序列都保持合法步进

4. `misalign`（未对齐异常）
- 用途：验证未对齐 PC 的异常标志传递。
- 覆盖点：
  - 提交 `instpack` 的 `misaligned` 标志为 1
  - 仍可持续提交（按当前 RTL 行为）

---

## 2. 如何验证测试通过

每次运行关注两类输出：

1. 末行状态
- `TB PASS`：该测试通过
- `TB FAIL: ...`：失败并带原因

2. 统计信息（`[GustEngine TB]` 行）
- `cycles`：总周期数
- `mem_req_hs / mem_resp_hs`：内存握手次数
- `instpack_hs`：提交次数
- `stalled_valid_cycles`：后端背压导致 valid 被阻塞的周期数
- `first_commit / last_commit`：首尾提交样本（pc/data/misaligned）

判定建议：
- 单次测试：只要出现 `TB PASS` 即通过。
- 回归测试：4 个模式都出现 `TB PASS` 才算整体验证通过。

---

## 3. 怎么调用测试环境

### 3.1 构建

```bash
cd /home/chen/FUN/flow/sim/GustEngine
cmake -S . -B cmake-build-debug
cmake --build cmake-build-debug -j4
```

### 3.2 查看帮助

```bash
./cmake-build-debug/gustengine_sim --help
```

### 3.3 单项运行

1. 顺序取指
```bash
./cmake-build-debug/gustengine_sim \
  --no-vcd \
  --test=sequential \
  --reset-addr=0x1000 \
  --target-commits=16
```

2. 后端背压
```bash
./cmake-build-debug/gustengine_sim \
  --no-vcd \
  --test=backpressure \
  --reset-addr=0x1000 \
  --target-commits=16 \
  --ready-pattern=110010
```

3. 分支重定向
```bash
./cmake-build-debug/gustengine_sim \
  --no-vcd \
  --test=branch \
  --reset-addr=0x1000 \
  --target-commits=20 \
  --branch-cycle=100 \
  --branch-target=0x3000 \
  --branch-post-commits=8
```

4. 未对齐异常
```bash
./cmake-build-debug/gustengine_sim \
  --no-vcd \
  --test=misalign \
  --reset-addr=0x1002 \
  --target-commits=12
```

### 3.4 一键回归（四模式）

```bash
cd /home/chen/FUN/flow/sim/GustEngine

./cmake-build-debug/gustengine_sim --no-vcd --test=sequential   --reset-addr=0x1000 --target-commits=16 && \
./cmake-build-debug/gustengine_sim --no-vcd --test=backpressure --reset-addr=0x1000 --target-commits=16 --ready-pattern=110010 && \
./cmake-build-debug/gustengine_sim --no-vcd --test=branch       --reset-addr=0x1000 --target-commits=20 --branch-cycle=100 --branch-target=0x3000 --branch-post-commits=8 && \
./cmake-build-debug/gustengine_sim --no-vcd --test=misalign     --reset-addr=0x1002 --target-commits=12
```

命令链全部成功且每项输出 `TB PASS`，即回归通过。

---

## 4. 常用参数速查

- `--test=sequential|backpressure|branch|misalign`
- `--max-cycles=N`
- `--latency=N`
- `--beat-gap=N`
- `--reset-addr=0x...`
- `--target-commits=N`
- `--ready-pattern=0101...`（仅 backpressure）
- `--branch-cycle=N`（仅 branch）
- `--branch-target=0x...`（仅 branch）
- `--branch-post-commits=N`（仅 branch）
- `--post-cycles=N`
- `--no-vcd`

