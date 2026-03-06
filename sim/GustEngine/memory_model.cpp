#include "memory_model.h"

#include <stdexcept>

namespace {
constexpr uint64_t kDataSalt = 0x9e3779b97f4a7c15ULL;
}

uint64_t AddrFuncBackend::read(uint64_t addr) {
  return (addr ^ kDataSalt) + (addr << 1);
}

SingleOutstandingMemModel::SingleOutstandingMemModel(MemoryBackend &backend, uint32_t response_latency,
                                                     uint32_t inter_beat_latency)
    : backend_(backend), response_latency_(response_latency), inter_beat_latency_(inter_beat_latency) {}

void SingleOutstandingMemModel::reset() {
  busy_ = false;
  resp_valid_ = false;
  resp_data_ = 0;
  countdown_ = 0;
  base_addr_ = 0;
  beat_index_ = 0;
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
    beat_index_ += 1;
    if (beat_index_ >= kBurstBeats) {
      busy_ = false;
      resp_valid_ = false;
      resp_data_ = 0;
      countdown_ = 0;
      base_addr_ = 0;
      beat_index_ = 0;
    } else {
      resp_data_ = backend_.read(base_addr_ + static_cast<uint64_t>(beat_index_) * kBeatBytes);
      resp_valid_ = false;
      countdown_ = inter_beat_latency_;
    }
  }

  if (req_fire) {
    if (busy_) {
      throw std::runtime_error("Protocol violation: req_fire while model is busy");
    }
    if ((req_addr & ((kBurstBeats * kBeatBytes) - 1)) != 0) {
      throw std::runtime_error("Protocol violation: req_addr is not cache-line aligned");
    }
    busy_ = true;
    resp_valid_ = false;
    base_addr_ = req_addr;
    beat_index_ = 0;
    resp_data_ = backend_.read(base_addr_);
    countdown_ = response_latency_;
  }

  if (busy_ && !resp_valid_) {
    if (countdown_ == 0) {
      resp_valid_ = true;
    } else {
      countdown_ -= 1;
    }
  }
}
