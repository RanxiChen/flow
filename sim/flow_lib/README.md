# flow_lib / flow_vmem

`flow_vmem` 是一个可复用的虚拟存储静态库，用于仿真中为 cache 或取指前端提供
`valid-ready` 风格的内存响应模型。

## 目录组织

- `include/flow_vmem/virtual_memory_model.h`：公共接口与配置。
- `src/virtual_memory_model.cpp`：状态机实现。
- `tests/vmem_smoke.cpp`：最小行为验证。
- `tests/vmem_layout_smoke.cpp`：段映射与布局切换验证。
- `docs/virtual_memory_handshake_timing.md`：握手逻辑与时序说明。
- `docs/memory_layout_architecture.md`：内存布局架构与扩展点。

## 构建静态库

```bash
cmake -S flow_lib -B flow_lib/build
cmake --build flow_lib/build -j
```

构建后可得到静态库 `libflow_vmem.a`（位于 `flow_lib/build` 对应产物目录）。

## 在仿真工程中引用

推荐使用 `add_subdirectory`：

```cmake
add_subdirectory(path/to/flow_lib)
target_link_libraries(your_sim_target PRIVATE flow::vmem)
```

## 核心实现说明

- 模型采用“单在途事务”（single outstanding）：
  一次请求未完成前不会接收新请求。
- 每个请求默认对应 4 个 beat，单 beat 默认 8 字节。
- 首 beat 延迟与拍间延迟可通过 `VirtualMemoryConfig` 参数化。
- 可选地址对齐检查（默认开启）：请求地址需按一个 burst 总字节对齐。

## 向后兼容与新功能切换

- 默认行为保持兼容：
  - 继续使用 `AddressFunctionBackend` 时，地址到数据关系与旧代码一致。
  - 现有 `SingleOutstandingVirtualMemoryModel` 用法无需修改。
- 新布局能力按需启用：
  - `SegmentMapBackend`：地址段映射管理。
  - `Word32SegmentStorage`：32 位索引组织，读时小端拼装 64 位。
  - `Word64SegmentStorage`：64 位索引组织。
  - `ConfigurableMemoryBackend`：通过 `MemoryLayoutConfig.mode` 在旧行为和段映射之间切换。

示例（切换到段映射模式）：

```cpp
flow_vmem::ConfigurableMemoryBackend backend;
flow_vmem::MemoryLayoutConfig cfg;
cfg.mode = flow_vmem::MemoryLayoutMode::kSegmentMap;
backend.set_layout_config(cfg);

auto seg = std::make_shared<flow_vmem::Word32SegmentStorage>(0x1000, 1024);
seg->write32_by_index(0, 0x12345678U);
seg->write32_by_index(1, 0x9ABCDEF0U);
backend.segment_backend().add_segment(seg);
```

详细说明见：
- `docs/virtual_memory_handshake_timing.md`
- `docs/memory_layout_architecture.md`
