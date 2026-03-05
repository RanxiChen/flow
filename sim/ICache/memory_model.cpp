#include "memory_model.h"

#include <stdexcept>

namespace {
constexpr uint64_t kDataSalt = 0x9e3779b97f4a7c15ULL;
}

uint64_t AddrFuncBackend::read(uint64_t addr) {
  return (addr ^ kDataSalt) + (addr << 1);
}

SingleOutstandingMemModel::SingleOutstandingMemModel(MemoryBackend &backend, uint32_t response_latency)
    : backend_(backend), response_latency_(response_latency) {}

void SingleOutstandingMemModel::reset() {
  busy_ = false;
  resp_valid_ = false;
  resp_data_ = 0;
  countdown_ = 0;
}

SingleOutstandingMemModel::Outputs SingleOutstandingMemModel::comb() const {
  return Outputs{
      .req_ready = !busy_,
      .resp_valid = resp_valid_,
      .resp_data = resp_data_,
  };
}

void SingleOutstandingMemModel::tick(bool req_fire, uint64_t req_addr, bool resp_fire) {
  if (resp_fire) {
    if (!busy_ || !resp_valid_) {
      throw std::runtime_error("Protocol violation: resp_fire without pending valid response");
    }
    busy_ = false;
    resp_valid_ = false;
    resp_data_ = 0;
    countdown_ = 0;
  }

  if (req_fire) {
    if (busy_) {
      throw std::runtime_error("Protocol violation: req_fire while model is busy");
    }
    busy_ = true;
    resp_valid_ = false;
    resp_data_ = backend_.read(req_addr);
    countdown_ = response_latency_;
  }

  if (busy_ && !resp_valid_) {
    if (countdown_ > 0) {
      countdown_ -= 1;
    }
    if (countdown_ == 0) {
      resp_valid_ = true;
    }
  }
}
