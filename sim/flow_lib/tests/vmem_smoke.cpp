#include "flow_vmem/virtual_memory_model.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <stdexcept>

int main() {
  flow_vmem::AddressFunctionBackend backend;
  flow_vmem::SingleOutstandingVirtualMemoryModel model(backend);
  model.reset();

  // 发送一个对齐请求，默认配置下应返回 4 个 beat。
  constexpr uint64_t kReqAddr = 0x1000;
  model.tick(/*req_fire=*/true, kReqAddr, /*resp_fire=*/false);

  uint32_t beats = 0;
  for (uint32_t cycle = 0; cycle < 128 && beats < 4; ++cycle) {
    const auto out = model.comb();
    if (out.resp_valid) {
      const uint64_t expected = backend.read(kReqAddr + static_cast<uint64_t>(beats) * 8);
      assert(out.resp_data == expected);
      model.tick(/*req_fire=*/false, /*req_addr=*/0, /*resp_fire=*/true);
      beats += 1;
    } else {
      model.tick(/*req_fire=*/false, /*req_addr=*/0, /*resp_fire=*/false);
    }
  }
  assert(beats == 4);
  assert(!model.busy());

  // 未对齐地址应触发异常。
  try {
    model.tick(/*req_fire=*/true, /*req_addr=*/0x1004, /*resp_fire=*/false);
  } catch (const std::runtime_error &) {
    std::cout << "flow_vmem_smoke PASS\n";
    return 0;
  }

  std::cerr << "Expected alignment exception was not raised\n";
  return 1;
}
