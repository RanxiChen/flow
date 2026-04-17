# VirFlow Testbench Guide

## 1. 目标

这个 testbench 用 Verilator 驱动 `VirFlow` 顶层，并用 `flow_lib` 的内存模型响应取指访存请求。

当前阶段目标是先跑通基础链路：

- 复位地址默认 `0x0`
- 从 hex 文件加载程序到地址 `0x0` 起始
- 固定周期运行，无协议异常即 PASS

后续可以在这个基础上逐步增加“功能正确性”检查（例如寄存器状态、提交序列、PASS/FAIL 指令约定）。

## 2. 关键文件

- `CMakeLists.txt`：接入 Verilator、读取 `rtl/filelist.f`、链接 `flow::vmem`。
- `tb_main.cpp`：testbench 主循环，负责时钟、复位、hex 加载、内存握手。
- `example/start01.hex`：默认加载的程序（每行一个 32-bit 指令字）。

## 3. 数据流和握手

`VirFlow` 与 testbench 的接口（只看取指访存）：

- DUT 输出请求：
  - `io_mem_req_valid`
  - `io_mem_req_bits_addr`
- DUT 输入响应：
  - `io_mem_req_ready`
  - `io_mem_resp_valid`
  - `io_mem_resp_bits_data`
  - `io_mem_resp_ready`（由 DUT 给出，testbench 采样）

testbench 中的桥接逻辑：

1. 每周期先调用内存模型 `comb()`，把 `ready/valid/data` 驱动到 DUT 输入。
2. 评估 DUT 后采样 `req_fire/resp_fire`。
3. 用握手结果调用 `mem.tick(req_fire, req_addr, resp_fire)` 推进内存模型状态。

这三步对应标准 valid-ready 时序，不要随意改顺序。

## 4. HEX 加载规则

- 文件按行读取，每行解析为一个 32-bit hex。
- 第 0 行放到地址 `0x0`，第 1 行放到 `0x4`，依次递增。
- 底层存储用 `Word32SegmentStorage(base=0)`，并通过 `kSegmentMap` 模式接入。
- 未覆盖区域当前填 `0`。

如果要换程序，不需要改代码，直接传参数：

```bash
./vir_flow_sim --hex=/absolute/or/relative/path/to/your.hex
```

## 5. 常用运行方式

配置和构建：

```bash
cmake -S . -B build
cmake --build build -j
```

默认运行（`reset_addr=0`, `example/start01.hex`）：

```bash
./build/vir_flow_sim
```

自定义参数：

```bash
./build/vir_flow_sim --hex=example/start01.hex --max-cycles=5000 --latency=12 --beat-gap=2
```

关闭波形：

```bash
./build/vir_flow_sim --no-vcd
```

## 6. 后续开发流程建议

1. 先保证“能跑通”：
   - 不改握手时序，先只替换 hex。
2. 再加可观测性：
   - 在 `tb_main.cpp` 中增加关键统计（握手次数、首个请求地址、首个响应数据）。
3. 最后加正确性判定：
   - 定义明确的 PASS/FAIL 规则（例如程序写 CSR/tohost）。
   - 在 testbench 中加入断言和错误信息，保证失败时能快速定位。

## 7. 新手改动入口

- 改默认程序：`TbConfig::hex_path`
- 改默认复位地址：`TbConfig::reset_addr`
- 改内存时序：`TbConfig::response_latency` / `inter_beat_latency`
- 改 PASS 条件：`main()` 末尾的 `pass/fail` 判定区

建议每次只改一个点，并保留可运行基线。
