# TinySoC LiteX 仿真说明

## 1. 生成 TinyCore RTL

```bash
cd /home/chen/FUN/flow/design
sbt "runMain flow.top.GenerateTinyCoreLitexTop"
```

生成结果在：

- `/home/chen/FUN/flow/design/build/tinycore/TinyCoreLitexTop.sv`

## 2. 构建并运行 LiteX Sim

这版本地 LiteX `sim.py` 默认就会执行“构建 + 运行”，不需要 `--build` / `--run`。

```bash
cd /home/chen/FUN/flow/litex_sim_dir/tinysoc
conda run -n flow python sim.py --cpu-type=tinycore --cpu-variant=tiny --no-compile-software
```

如果只想快速验证，推荐非交互模式：

```bash
cd /home/chen/FUN/flow/litex_sim_dir/tinysoc
conda run -n flow python sim.py \
  --cpu-type=tinycore \
  --cpu-variant=tiny \
  --no-compile-software \
  --non-interactive
```

当前 `tinycore` stub 在完成一轮最小流程后会自动 `finish`，所以命令会自己退出。

## 3. 当前最小观测手段

当前不依赖额外 testbench，直接在仿真终端里看 `tinycore` 的总线日志。

终端里会看到类似输出：

- `reset release`
- `ibus ack addr=... data=...`
- `dbus write ack ...`
- `dbus read ack ...`
- `done readback=...`

这说明：

- `ibus` 已经在取 ROM
- `dbus` 已经在写 SRAM 并读回
- 整个 LiteX SoC 仿真链路是通的

实际示例：

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

## 4. 如果你想开波形

可以尝试：

```bash
conda run -n flow python sim.py --cpu-type=tinycore --cpu-variant=tiny --no-compile-software --non-interactive --trace
```

当前 `sim.py` 已经做了兼容处理：

- 如果缺少 `python vcd` 包，就跳过 `.gtkw` 保存文件生成
- 原始 VCD trace 仍然可以继续使用

所以现在可以直接试 `--trace`。

## 5. 关键观察点

- `ibus.cyc/stb/ack/adr/dat_r`
- `dbus.cyc/stb/we/sel/ack/adr/dat_w/dat_r`
- `reset_addr`

预期行为：

- 复位后先出现几次 `ibus` 读 ROM
- 然后出现一次 `dbus` 写 SRAM
- 接着出现一次 `dbus` 读 SRAM
- 最后打印 `done readback`

## 6. 容易踩坑的点

- 本地 LiteX 的 `wishbone.Interface` 参数名是 `adr_width`，不是 `address_width`
- 64-bit Wishbone 的 `adr` 是按 word 寻址，不是按 byte 寻址
- 当前 `dbus` 目标地址默认假设 LiteX SRAM 基址是 `0x1000_0000`
- `sim.py` 这版没有 `--build` / `--run` 参数，直接执行就是 build + run
- 当前 stub 完成最小访问流程后会自动结束仿真，这是刻意设计的 smoke test 行为
