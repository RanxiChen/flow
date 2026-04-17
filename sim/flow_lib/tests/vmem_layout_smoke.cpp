#include "flow_vmem/virtual_memory_model.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <memory>
#include <stdexcept>

int main() {
  using namespace flow_vmem;

  SegmentMapBackend map_backend;
  auto seg32 = std::make_shared<Word32SegmentStorage>(0x1000, 8);
  seg32->write32_by_index(0, 0x11111111U);
  seg32->write32_by_index(1, 0x22222222U);
  seg32->write32_by_index(2, 0x33333333U);
  seg32->write32_by_index(3, 0x44444444U);
  map_backend.add_segment(seg32);

  auto seg64 = std::make_shared<Word64SegmentStorage>(0x2000, 2);
  seg64->write64_by_index(0, 0xAAAABBBBCCCCDDDDULL);
  seg64->write64_by_index(1, 0x1111222233334444ULL);
  map_backend.add_segment(seg64);

  // 32-bit segment uses little-endian packing to 64-bit.
  assert(map_backend.read(0x1000) == 0x2222222211111111ULL);
  assert(map_backend.read(0x1008) == 0x4444444433333333ULL);

  // 64-bit segment returns native slot directly.
  assert(map_backend.read(0x2000) == 0xAAAABBBBCCCCDDDDULL);
  assert(map_backend.read(0x2008) == 0x1111222233334444ULL);

  // Miss should return poison value by default.
  assert(map_backend.read(0x3000) == 0xDEADDEADDEADDEADULL);

  // Overlap should be rejected.
  bool overlap_thrown = false;
  try {
    auto overlap = std::make_shared<Word64SegmentStorage>(0x2008, 2);
    map_backend.add_segment(overlap);
  } catch (const std::runtime_error &) {
    overlap_thrown = true;
  }
  assert(overlap_thrown);

  // Default configurable backend keeps old behavior (address function).
  ConfigurableMemoryBackend cfg_backend;
  AddressFunctionBackend addr_func;
  assert(cfg_backend.read(0x80) == addr_func.read(0x80));

  // Explicitly switch to segment mode.
  MemoryLayoutConfig layout_cfg;
  layout_cfg.mode = MemoryLayoutMode::kSegmentMap;
  layout_cfg.poison_value = 0xDEADDEADDEADDEADULL;
  cfg_backend.set_layout_config(layout_cfg);
  cfg_backend.segment_backend().add_segment(seg32);
  assert(cfg_backend.read(0x1000) == 0x2222222211111111ULL);
  assert(cfg_backend.read(0x9990) == 0xDEADDEADDEADDEADULL);

  std::cout << "flow_vmem_layout_smoke PASS\n";
  return 0;
}
