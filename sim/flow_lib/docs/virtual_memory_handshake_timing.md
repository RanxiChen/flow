# 虚拟存储握手与时序行为

本文档描述 `flow_vmem::SingleOutstandingVirtualMemoryModel` 的握手规则与周期级行为。
无论底层采用地址函数后端还是段映射后端，握手时序都保持一致。

## 1. 接口信号语义

- 请求通道
  - `req_valid`：上游声明请求有效（由使用方驱动）。
  - `req_ready`：模型可接收请求（由模型输出）。
  - `req_addr`：请求基地址。
- 响应通道
  - `resp_valid`：模型声明当前 beat 数据有效。
  - `resp_ready`：下游可接收响应（由使用方驱动）。
  - `resp_data`：当前 beat 数据。

握手判定统一为：

- `req_fire = req_valid && req_ready`
- `resp_fire = resp_valid && resp_ready`

## 2. 单在途约束

- `busy = false` 时，`req_ready = true`，可接收新请求。
- 一旦 `req_fire` 发生，模型进入 `busy = true`。
- `busy = true` 期间，`req_ready = false`，禁止第二个请求进入。
- 只有当前 burst 全部 beat 完成（最后一次 `resp_fire`）后，`busy` 才清零。

## 3. 周期级时序行为

### 3.1 请求受理到首拍响应

1. `req_fire` 发生：
   - 锁存 `base_addr = req_addr`
   - 预先计算第 0 拍数据到内部寄存
   - 启动首拍倒计时 `response_latency`
2. 倒计时每周期递减。
3. 倒计时到 0 时，`resp_valid` 置 1。
4. 若 `resp_ready = 1`，当周期形成 `resp_fire`，第 0 拍完成。

### 3.2 拍间行为

每次 `resp_fire` 后：

- 若未到最后一拍：
  - `beat_index += 1`
  - 计算下一拍数据
  - `resp_valid` 拉低
  - 启动拍间倒计时 `inter_beat_latency`
- 倒计时归零后再次拉高 `resp_valid`。

- 若已是最后一拍：
  - 当前事务结束，`busy = false`
  - `req_ready` 在后续组合逻辑恢复为可接收状态

### 3.3 下游背压（resp_ready=0）

当 `resp_valid=1` 但 `resp_ready=0` 时：

- 不发生 `resp_fire`
- `beat_index` 不前进
- `resp_data` 保持当前拍
- 直到 `resp_ready` 重新为 1 才继续

## 4. ASCII 时序示意

### 4.1 正常无背压

```text
cycle:      0 1 2 3 4 5 6 7 8 9
req_valid:  1 0 0 0 0 0 0 0 0 0
req_ready:  1 0 0 0 0 0 0 0 0 1
req_fire:   1 . . . . . . . . .

resp_valid: 0 0 0 1 0 0 1 0 0 1 ...
resp_ready: 1 1 1 1 1 1 1 1 1 1 ...
resp_fire:  . . . 1 . . 1 . . 1 ...
```

说明：示例中首拍延迟为 3，拍间延迟为 2。

### 4.2 响应侧背压

```text
cycle:      0 1 2 3 4 5 6
resp_valid: 0 0 1 1 1 0 1
resp_ready: 1 1 0 0 1 1 1
resp_fire:  . . 0 0 1 . 1
```

说明：cycle2~3 因 `resp_ready=0`，同一拍数据保持不前进。

## 5. 数据来源路径（新增布局模式）

`resp_data` 的来源可通过后端配置切换，但不改变握手：

- 默认模式（兼容旧代码）：
  - `AddressFunctionBackend::read(addr)` 直接按地址函数生成数据。
- 段映射模式：
  - `addr -> SegmentMapBackend` 段匹配 -> 对应 `Word32/Word64` 子存储读取。
  - `Word32SegmentStorage` 在 `read64` 时按小端拼装：
    - 低 32 位来自 `addr`
    - 高 32 位来自 `addr + 4`
  - 未命中地址返回毒值（默认 `0xDEADDEADDEADDEAD`）。

## 6. 异常与错误行为

- `resp_fire` 发生时若 `busy=false` 或 `resp_valid=false`：协议错误，抛异常。
- `req_fire` 发生时若 `busy=true`：协议错误，抛异常。
- 启用对齐检查时，`req_addr` 未按 burst 字节对齐：协议错误，抛异常。
- 段映射后端新增：
  - 段重叠注册：报错。
  - 地址未命中：默认毒值返回（预留总线错误接口，暂不启用）。

## 7. 默认参数（与 ICache 旧模型兼容）

- `response_latency = 12`
- `inter_beat_latency = 2`
- `burst_beats = 4`
- `beat_bytes = 8`
- `enforce_alignment = true`
