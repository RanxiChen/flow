# 内存布局架构与扩展预留

本文档说明 `flow_vmem` 新增的布局管理能力，以及后续复杂布局的扩展方向。

## 1. 设计目标

- 保持旧代码可用：默认行为不变。
- 新功能可选开启：通过配置切换到段映射模式。
- 接口先稳定：后续可替换内部实现（大文件优化、分页、权限）而不改上层调用。

## 2. 架构分层

### 2.1 后端入口层

- `VirtualMemoryBackend`：统一 `read(addr)` 入口。
- `AddressFunctionBackend`：兼容旧行为。
- `ConfigurableMemoryBackend`：根据 `MemoryLayoutConfig.mode` 路由到旧模式或段映射模式。

### 2.2 布局管理层

- `SegmentMapBackend`：
  - 管理多个段对象（每段有起始地址与长度）。
  - 访问时先按地址查命中段，再调用段对象读取。
  - 默认禁止段重叠，防止读路径歧义。

### 2.3 子存储层

- `Word32SegmentStorage`：
  - 数据组织单位：32 位。
  - 写入接口：`write32_by_index(index, data32)`。
  - 读取接口：`read64(addr)`，按小端拼装两个连续 32 位词。
- `Word64SegmentStorage`：
  - 数据组织单位：64 位。
  - 写入接口：`write64_by_index(index, data64)`。
  - 读取接口：`read64(addr)`，直接返回对应槽位。

## 3. 配置与默认值

`MemoryLayoutConfig` 默认值：

- `mode = kAddressFunction`
- `miss_policy = kPoison`
- `poison_value = 0xDEADDEADDEADDEAD`
- `enable_bus_error = false`（预留）
- `page_mode = false`（预留）
- `permission_check = false`（预留）

含义：

- 默认完全兼容旧行为。
- 启用 `kSegmentMap` 后，地址未命中返回毒值。
- 总线错误/分页/权限先保留配置位与 API，不在当前版本实现具体行为。

## 4. 读路径

### 4.1 默认模式（兼容路径）

`addr -> AddressFunctionBackend::read(addr) -> data64`

### 4.2 段映射模式

`addr -> SegmentMapBackend`
`-> 命中某段 -> Word32/Word64SegmentStorage::read64(addr) -> data64`

未命中：

`addr -> SegmentMapBackend -> miss -> poison_value`

## 5. 可扩展点（后续复杂布局）

- 段检索算法：
  - 当前线性扫描，可替换为有序区间索引或分页目录。
- 段内存储容器：
  - 当前连续数组，可替换为稀疏页表/分块缓存。
- 访问结果语义：
  - 当前 `read(addr)` 返回数据，`try_read64` 仅预留错误字段。
  - 后续可启用总线错误、权限错误、不可执行段错误等细分状态。

## 6. 与握手模型的边界

- `SingleOutstandingVirtualMemoryModel` 只关心握手时序与 beat 推进。
- 内存布局变化仅影响 `backend.read(addr)` 的数据来源，不改变 ready/valid 语义。
