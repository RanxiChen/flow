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
