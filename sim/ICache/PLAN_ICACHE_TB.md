## ICache Verilator Testbench 现状文档（2026-03-06）

### 1. 当前范围
- TB 已实现 `dst` 主动驱动 + `commer` 虚拟内存驱动的闭环测试。
- 支持两种模式：
- `single`：单次读取。
- `double`：两次流水线读取，第 2 次地址为第 1 次地址的下一个 32-bit 字（`addr + 4`）。
- `stream`：从 `--single-addr` 开始，连续读取多次 32-bit 字（地址每次 `+4`），用于长流量不间断请求验证。

### 2. 关键文件
- `tb_main.cpp`：主测试逻辑、`dst` 驱动、严格检查、统计、日志、post-run。
- `memory_model.h` / `memory_model.cpp`：`SingleOutstandingMemModel` 内存模型与延迟策略。
- `rtl/ICache.sv`：DUT 顶层。

### 3. 接口与协议
- `dst`（TB <-> ICache）：
- `io_dst_req_valid/ready/bits_addr`
- `io_dst_resp_valid/ready/bits_data`
- `commer`（ICache <-> MemModel）：
- `io_commer_req_valid/ready/bits_addr`
- `io_commer_resp_valid/ready/bits_data`
- burst 约定：
- 1 个 `commer.req` 对应 4 个 `commer.resp` beat。
- beat 数据宽度 64-bit，地址步进 8B，覆盖 32B line。

### 4. 虚拟内存模型
- 单在途事务：busy 时 `req_ready=0`。
- 地址约束：req 必须 32B 对齐，否则报错。
- 延迟策略：
- 首 beat 延迟：`response_latency`（默认 `12` cycle）。
- beat 间延迟：`inter_beat_latency`（默认 `2` cycle）。
- 每个 beat 仅在 `resp_valid && resp_ready` 时推进。

### 5. 检查机制（已强化）
- 请求/响应严格一一对应：
- 每次 `dst.req.fire` 入队：`{idx, addr, expected_data}`。
- 每次 `dst.resp.fire` 必须匹配队首请求并校验数据。
- 数据不一致立即 `data_mismatch`。
- 无在途请求却收到响应立即 `protocol_error`。
- 请求数与响应数不一致、或完成时队列未清空，立即报错。
- 超时检查：
- 在途请求超过 `single_timeout_cycles` 未收到对应响应立即报错。

### 6. 地址与数据期望
- `single`：
- 仅 1 次读取，地址为 `--single-addr`。
- `double`：
- 第 1 次地址：`--single-addr`。
- 第 2 次地址：`--single-addr + 4`。
- `stream`：
- 第 `i` 次地址：`--single-addr + i * 4`，`i ∈ [0, --stream-count-1]`。
- 期望值计算：
- `beat_data = backend.read(req_addr & ~0x7)`。
- `req_addr[2]==0` 取低 32-bit，`req_addr[2]==1` 取高 32-bit。

### 7. 统计与日志
- 汇总统计：
- `cycles`
- `commer_req_hs` / `commer_resp_hs`
- `bursts_done`
- `busy_cycles`
- `backpressure_cycles`
- `req_sent` / `resp_done`
- `test_mode`
- post 统计：
- `post_cycles`
- `post_commer_req_hs`
- `post_commer_resp_hs`
- `post_dst_resp_hs`
- 时序统计：
- `dst_req_cycle`、`dst_resp_cycle`、`dst_round_trip_cycles`、百分比
- `commer_req_cycle`、`commer_last_resp_cycle`、`commer_transfer_cycles`、百分比
- `access[i] req_cycle/resp_cycle/latency_cycles`、百分比
- 中文结论日志：
- `single_access_ch: 从dst端请求握手到响应握手返回，总耗时 X 个周期`
- `double_access_ch: 第1次访问耗时 A 周期, 第2次访问耗时 B 周期, 两次窗口总耗时 C 周期`

### 8. 运行与参数
- 构建：
```bash
cmake --build cmake-build-debug -j4
```
- 运行示例：
```bash
./cmake-build-debug/icache_sim --no-vcd --test=single --single-addr=0x1000 --post-cycles=0
./cmake-build-debug/icache_sim --no-vcd --test=double --single-addr=0x1000 --post-cycles=0
./cmake-build-debug/icache_sim --no-vcd --test=stream --single-addr=0x1000 --stream-count=320 --post-cycles=0
```
- 参数列表：
- `--test=single|double|stream`
- `--max-cycles=N`
- `--latency=N`
- `--beat-gap=N`
- `--single-addr=N`
- `--stream-count=N`
- `--single-timeout=N`
- `--post-cycles=N`
- `--no-vcd`

### 9. 当前状态说明
- `single` 模式可通过，并输出完整时序统计。
- `double` 模式已按“第二次地址为 `+4`”执行并严格校验；若第二次返回值不对应会直接失败并报 `data_mismatch`（这是预期的检查行为）。
- `stream` 模式用于连续数百条 32-bit 请求压力验证；默认 `stream-count=320`，并在未显式配置时自动放宽 `max-cycles` 与单请求超时阈值以适配长序列测试。
