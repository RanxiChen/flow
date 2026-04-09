# Agent Workflow

本文件记录当前仓库里和 TinyCore + LiteX Sim 相关的固定工作流。

## 适用范围

当修改以下任一部分后，必须重新执行完整验证流程：

- `design/src/main/scala/interface/Wishbone.scala`
- `design/src/main/scala/top/tiny.scala`
- `litex_wrapper/tinycore/`
- `litex_sim_dir/tinysoc/`
- 任何会影响 TinyCore Wishbone 接口、LiteX wrapper、LiteX Sim 行为的代码

## TinyCore 当前目标

当前 `tinycore` 是一个最小 LiteX CPU stub：

- `ibus`：Wishbone 主口，只读
- `dbus`：Wishbone 主口，可读写
- 使用 LiteX Sim 运行整个 SoC
- 不额外维护独立 testbench

当前最小验证目标是：

1. 生成 `TinyCoreLitexTop.sv`
2. LiteX Sim 成功构建并启动
3. 终端中能看到 TinyCore 的总线访问日志
4. 仿真最终自动结束

## TinyCore 相关文件关系

当前 TinyCore 这条链路里，几个关键文件的职责如下：

- `design/src/main/scala/interface/Wishbone.scala`
  - 定义可复用的 LiteX Wishbone 主口接口
- `design/src/main/scala/top/tiny.scala`
  - 定义 `TinyCoreLitexTop`
  - 包含最小 `ibus/dbus` 状态机
  - 包含仿真时使用的总线 monitor
- `litex_wrapper/tinycore/core.py`
  - 把 `TinyCoreLitexTop` 包装成 LiteX CPU
  - 负责调用 `sbt` 生成 RTL
  - 负责把生成文件复制到 `generated/tinycore/`
- `generated/tinycore/`
  - LiteX 实际引用的 TinyCore RTL 缓存目录
- `litex_sim_dir/tinysoc/sim.py`
  - TinyCore 的 LiteX Sim 入口
  - 使用本地 `flow` conda 环境中的 LiteX
  - 负责构建并运行最小 SoC 仿真
- `litex_sim_dir/tinysoc/README.md`
  - 当前 TinyCore 仿真的使用说明

建议把这几个位置视为 TinyCore 第一阶段集成的主工作区。

## 每次修改后的必跑流程

### 第 1 步：生成 TinyCore SystemVerilog

```bash
cd /home/chen/FUN/flow/design
sbt "runMain flow.top.GenerateTinyCoreLitexTop"
```

成功标准：

- 命令退出码为 0
- 生成文件存在：
  - `/home/chen/FUN/flow/design/build/tinycore/TinyCoreLitexTop.sv`

### 第 2 步：运行 LiteX Sim 最小验证

```bash
cd /home/chen/FUN/flow/litex_sim_dir/tinysoc
conda run -n flow python sim.py --cpu-type=tinycore --cpu-variant=tiny --no-compile-software --non-interactive
```

成功标准：

- 命令可以自行退出
- 终端日志中出现类似输出：
  - `[tinycore][cycle=0] reset release ...`
  - `[tinycore][cycle=4] ibus ack ...`
  - `[tinycore][cycle=21] dbus write ack ...`
  - `[tinycore][cycle=25] dbus read ack ...`
  - `[tinycore][cycle=26] done readback=...`

### 第 3 步：确认关键行为

必须确认以下事实：

- `ibus` 能对 ROM 发起读请求
- `dbus` 能对 SRAM 发起写请求
- `dbus` 能把写入的数据读回
- 仿真在 `done` 后自动结束

## 当前参考输出

当前一轮正常运行时，可看到类似日志：

```text
[tinycore][cycle=0] reset release reset_addr=0x0
[tinycore][cycle=4] ibus ack addr=0x0 data=0x0
[tinycore][cycle=8] ibus ack addr=0x8 data=0x0
[tinycore][cycle=12] ibus ack addr=0x10 data=0x0
[tinycore][cycle=16] ibus ack addr=0x18 data=0x0
[tinycore][cycle=21] dbus write ack adr_word=0x2000000 data=0x1122334455667788
[tinycore][cycle=25] dbus read ack adr_word=0x2000000 data=0x1122334455667788
[tinycore][cycle=26] done readback=0x1122334455667788
```

说明：

- `ibus` 当前从空 ROM 读到的值是 `0x0`，这是因为使用了 `--no-compile-software`
- `dbus` 读回的值必须和写入值一致

## 当前已知约束

- 本地 LiteX `sim.py` 没有 `--build` / `--run` 参数，直接执行就是 build + run
- 本地 LiteX Wishbone 接口参数名是 `adr_width`
- 当前 SoC 主总线是 32-bit，TinyCore 的两个 64-bit Wishbone 主口会被 LiteX 自动下变宽适配
- 当前 `dbus` 访问目标默认假设 LiteX SRAM 基址是 `0x1000_0000`
- 当前 `sim.py` 为了让日志显示，使用了本地 `_litex_sim_core` 补丁版本来打开 `PRINTF_COND`

## 修改完成后的要求

以后凡是完成和 TinyCore / LiteX Sim 相关的代码修改，默认都要：

1. 跑 `sbt "runMain flow.top.GenerateTinyCoreLitexTop"`
2. 跑 `conda run -n flow python sim.py --cpu-type=tinycore --cpu-variant=tiny --no-compile-software --non-interactive`
3. 确认日志行为仍然符合预期

如果这三步没有完成，就不能认为修改已经验证完毕。

## 协作确认规则

以后凡是要修改代码，必须先向用户明确说明：

- 准备怎么改
- 为什么这么改

并且必须先征得用户明确同意，之后才能执行实际文件修改。

禁止在未征得用户同意前直接改代码，再事后汇报。

## BreezeCache 当前联调流程

当前 `BreezeCache` 的工作方式不是先大改设计，再补测试；而是按时序逐拍把可观测信号暴露出来，再在测试里逐周期验证硬件行为。

当前工作区：

- `design/src/main/scala/cache/BreezeCache.scala`
- `design/src/test/scala/cache/breezecacheSpec.scala`

当前协作规则：

- 测试的目标是按用户给定的流水时序，一拍一拍检查 `s0/s1/s2/...` 的状态。
- 如果测试失败，必须先分析为什么失败：
  - 是测试持续驱动导致重复握手
  - 是 ready/valid 协议本身如此
  - 还是设计逻辑和预期时序不一致
- 每次遇到失败，先给出原因分析，再给出建议。
- 建议要分清两类：
  - 测试应该怎么改
  - 设计逻辑应该怎么改
- 当前目标是“用测试代码检测硬件设计”，不是为了让测试通过而掩盖设计问题。

### 当前允许直接修改的范围

以下改动，助手在先说明“准备怎么改、为什么改”并获得同意后，可以直接执行：

- 在 `BreezeCache.scala` 里新增 `debug/optionIO` 暴露信号
- 在 `breezecacheSpec.scala` 里新增 `assert/expect/println/clock.step`

其中，新增调试信号时遵循以下约束：

- 优先通过 `optionIO/debug` 暴露，不要直接改主功能接口
- 信号驱动统一放在源码靠后、已有 debug 驱动附近，保持风格一致

### 当前不允许直接修改的范围

以下改动，助手不能直接做，必须先给出分析和建议，由用户决定是否亲自修改或明确授权：

- 改动 `BreezeCache` 主功能逻辑
- 改动 ready/valid 握手策略
- 改动 miss/refill/替换策略
- 改动流水级之间的寄存和时序定义

### 当前测试写法约定

- 现有 `s0/s1` 的历史检查有的用 `assert`，先不统一重构，延续已有风格继续写
- 新增检查时必须带日志，说明当前在检查哪一拍、哪一个行为
- 日志要能把不同阶段隔开，便于定位是哪一拍失败
- 如果一个阶段的结果和预期不一致，先保留失败现场，再分析原因，不要立刻改设计掩盖问题

### 当前已形成的经验

- `s0_valid` 本质上是 `io.dreq.fire`
- 如果测试端把 `dreq.valid` 一直保持为高，那么后续只要 `ready` 再次拉高，就会再次握手，导致新的 `s0_valid`
- 因此，测试在验证“单次请求经过流水线”的时序时，必须明确区分：
  - 是要持续施压输入
  - 还是只发出一个单拍请求

每次遇到这类问题，优先先解释协议行为，再决定是修测试还是修设计。
