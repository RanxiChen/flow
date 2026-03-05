## ICache Verilator Testbench 规划（单在途内存模型 + 可插拔数据源）

### Summary
目标是为 `rtl/ICache.sv` 构建 C++ Verilator testbench，基于端口名按标准 ready-valid 语义仿真 `req/resp` 通道。  
内存模型采用“单请求在途（single outstanding）”策略：忙时对新 `req` 拉低 `ready` 背压；`resp` 延迟做成可配置（默认 1 拍）；数据源抽象为可插拔后端（默认地址函数），为后续文件镜像/随机模式留扩展点。

### Key Changes
1. 代码组织（拆分多文件）
- 新增 `tb_main.cpp`：时钟推进、reset、主循环、结束判定、统计输出。
- 新增 `memory_model.h/.cpp`：协议状态机与后端接口实现。
- `main.cpp` 可替换或保留为入口转发，CMake 里把可执行入口切到新 testbench 主文件。

2. 内存模型抽象与接口
- 定义 `MemoryBackend` 抽象接口（面向“按地址读数据”，并预留未来写接口扩展位）：
  - `uint64_t read(uint64_t addr)`（当前必需）
  - 预留写相关方法签名（当前不接入 DUT，先留空实现或默认返回 unsupported）
- 默认后端 `AddrFuncBackend`：`data = f(addr)`（可配置函数策略，默认先用稳定可读模式，如 `addr ^ const`）。
- 定义 `SingleOutstandingMemModel`：
  - 输入：DUT 的 `req_valid/req_addr` 与 `resp_ready`
  - 输出：驱动 DUT 的 `req_ready` 与 `resp_valid/resp_data`
  - 规则：
    - 空闲时 `req_ready=1`，握手成功后锁存请求并进入 busy
    - busy 时 `req_ready=0`
    - 响应按 `response_latency` 计时后拉高 `resp_valid`
    - 仅在 `resp_valid && resp_ready` 完成后清 busy，允许下一条 req
    - 保证任意时刻最多 1 条在途事务

3. 仿真控制与可观测性
- 引入 `TbConfig`（命令行或常量配置）：
  - `max_transactions`（达到后 PASS）
  - `max_cycles`（超时 FAIL）
  - `response_latency`（默认 1，可配置）
  - `enable_vcd`（是否生成波形）
- 统计与摘要：
  - req/resp 成功握手计数
  - busy 周期数、背压周期数
  - 超时/协议错误计数
- 协议检查（断言式）：
  - busy 时禁止接受新 req（即不允许 req handshake）
  - resp 只能对应当前在途事务
  - 在途计数永不超过 1

### Test Plan
1. 基线握手
- 配置 `response_latency=1`，跑到 `max_transactions=N`，期望 PASS，req/resp 数量一致。

2. 背压正确性
- 人工让 DUT 持续发 req（若 DUT 行为允许），检查 busy 期间 `req_ready=0`，无第二条 req 被接受。

3. 可配置延迟
- 分别测试 `latency=1/2/4`，验证 resp 出现周期与配置一致，事务仍串行完成。

4. 超时路径
- 设置极小 `max_cycles` 或人为阻塞 resp 完成，验证 testbench 以 FAIL 退出并给出原因。

5. 数据后端一致性
- 默认 `AddrFuncBackend` 下抽样比对多个地址返回值稳定、可重复。

### Assumptions
- 不参考当前 `ICache.sv` 内部逻辑，仅使用现有端口命名与 ready-valid 语义。
- 当前 DUT 接口未暴露写请求字段，因此“读写框架”先体现在后端抽象层预留写接口，不在本版连线生效。
- resp 通道严格遵循 ready-valid：仅 `resp_valid && resp_ready` 视为响应完成。
- 第一版以自动检查和统计摘要为主要验收方式，波形仅作辅助手段。
