#include "flow_vmem/virtual_memory_model.h"

#include <limits>
#include <stdexcept>

namespace flow_vmem {
namespace {
constexpr uint64_t kDataSalt = 0x9e3779b97f4a7c15ULL;

bool add_overflow_u64(uint64_t a, uint64_t b, uint64_t *out) {
  if (b > std::numeric_limits<uint64_t>::max() - a) {
    return true;
  }
  *out = a + b;
  return false;
}
}

uint64_t AddressFunctionBackend::read(uint64_t addr) {
  return (addr ^ kDataSalt) + (addr << 1);
}

Word32SegmentStorage::Word32SegmentStorage(uint64_t base_addr, uint64_t word_count,
                                           uint32_t fill_value)
    : base_addr_(base_addr), words_(word_count, fill_value) {}

bool Word32SegmentStorage::contains(uint64_t addr, uint64_t bytes) const {
  uint64_t seg_end = 0;
  uint64_t req_end = 0;
  if (add_overflow_u64(base_addr_, size_bytes(), &seg_end)) {
    return false;
  }
  if (add_overflow_u64(addr, bytes, &req_end)) {
    return false;
  }
  return addr >= base_addr_ && req_end <= seg_end;
}

MemoryAccessResult Word32SegmentStorage::read64(uint64_t addr) const {
  if (!contains(addr, 8)) {
    return MemoryAccessResult{
        .hit = false,
        .bus_error = false,
        .data = 0,
    };
  }
  const uint64_t offset_bytes = addr - base_addr_;
  if ((offset_bytes % 4) != 0) {
    return MemoryAccessResult{
        .hit = false,
        .bus_error = false,
        .data = 0,
    };
  }
  const uint64_t low_index = offset_bytes / 4;
  const uint64_t high_index = low_index + 1;
  const uint64_t low = static_cast<uint64_t>(words_.at(low_index));
  const uint64_t high = static_cast<uint64_t>(words_.at(high_index));
  return MemoryAccessResult{
      .hit = true,
      .bus_error = false,
      .data = low | (high << 32),
  };
}

void Word32SegmentStorage::write32_by_index(uint64_t index, uint32_t data) {
  if (index >= words_.size()) {
    throw std::out_of_range("Word32SegmentStorage index out of range");
  }
  words_[index] = data;
}

Word64SegmentStorage::Word64SegmentStorage(uint64_t base_addr, uint64_t word_count,
                                           uint64_t fill_value)
    : base_addr_(base_addr), words_(word_count, fill_value) {}

bool Word64SegmentStorage::contains(uint64_t addr, uint64_t bytes) const {
  uint64_t seg_end = 0;
  uint64_t req_end = 0;
  if (add_overflow_u64(base_addr_, size_bytes(), &seg_end)) {
    return false;
  }
  if (add_overflow_u64(addr, bytes, &req_end)) {
    return false;
  }
  return addr >= base_addr_ && req_end <= seg_end;
}

MemoryAccessResult Word64SegmentStorage::read64(uint64_t addr) const {
  if (!contains(addr, 8)) {
    return MemoryAccessResult{
        .hit = false,
        .bus_error = false,
        .data = 0,
    };
  }
  const uint64_t offset_bytes = addr - base_addr_;
  if ((offset_bytes % 8) != 0) {
    return MemoryAccessResult{
        .hit = false,
        .bus_error = false,
        .data = 0,
    };
  }
  const uint64_t index = offset_bytes / 8;
  return MemoryAccessResult{
      .hit = true,
      .bus_error = false,
      .data = words_.at(index),
  };
}

void Word64SegmentStorage::write64_by_index(uint64_t index, uint64_t data) {
  if (index >= words_.size()) {
    throw std::out_of_range("Word64SegmentStorage index out of range");
  }
  words_[index] = data;
}

SegmentMapBackend::SegmentMapBackend(MemoryLayoutConfig config) : config_(config) {}

void SegmentMapBackend::add_segment(std::shared_ptr<ISegmentStorage> segment) {
  if (!segment) {
    throw std::invalid_argument("SegmentMapBackend::add_segment received null segment");
  }
  if (overlaps_any(*segment)) {
    throw std::runtime_error("SegmentMapBackend overlap is not allowed");
  }
  segments_.push_back(std::move(segment));
}

uint64_t SegmentMapBackend::read(uint64_t addr) {
  return try_read64(addr).data;
}

MemoryAccessResult SegmentMapBackend::try_read64(uint64_t addr) const {
  for (const auto &seg : segments_) {
    if (!seg->contains(addr, 8)) {
      continue;
    }
    const auto read_res = seg->read64(addr);
    if (read_res.hit || read_res.bus_error) {
      return read_res;
    }
  }
  // 预留：未来 enable_bus_error=true 时可返回 bus error。当前先统一毒值。
  (void)config_.enable_bus_error;
  return MemoryAccessResult{
      .hit = false,
      .bus_error = false,
      .data = config_.poison_value,
  };
}

bool SegmentMapBackend::overlaps_any(const ISegmentStorage &candidate) const {
  uint64_t cand_end = 0;
  if (add_overflow_u64(candidate.base_addr(), candidate.size_bytes(), &cand_end)) {
    throw std::runtime_error("SegmentMapBackend candidate range overflow");
  }
  for (const auto &seg : segments_) {
    uint64_t seg_end = 0;
    if (add_overflow_u64(seg->base_addr(), seg->size_bytes(), &seg_end)) {
      throw std::runtime_error("SegmentMapBackend existing range overflow");
    }
    const bool overlap = (candidate.base_addr() < seg_end) && (seg->base_addr() < cand_end);
    if (overlap) {
      return true;
    }
  }
  return false;
}

ConfigurableMemoryBackend::ConfigurableMemoryBackend(MemoryLayoutConfig config)
    : layout_config_(config), segment_backend_(config) {}

void ConfigurableMemoryBackend::set_layout_config(const MemoryLayoutConfig &config) {
  layout_config_ = config;
  segment_backend_.set_config(config);
}

uint64_t ConfigurableMemoryBackend::read(uint64_t addr) {
  if (layout_config_.mode == MemoryLayoutMode::kSegmentMap) {
    return segment_backend_.read(addr);
  }
  return address_backend_.read(addr);
}

SingleOutstandingVirtualMemoryModel::SingleOutstandingVirtualMemoryModel(
    VirtualMemoryBackend &backend, VirtualMemoryConfig config)
    : backend_(backend), config_(config) {
  if (config_.burst_beats == 0) {
    throw std::invalid_argument("VirtualMemoryConfig.burst_beats must be > 0");
  }
  if (config_.beat_bytes == 0) {
    throw std::invalid_argument("VirtualMemoryConfig.beat_bytes must be > 0");
  }
}

void SingleOutstandingVirtualMemoryModel::reset() {
  busy_ = false;
  resp_valid_ = false;
  resp_data_ = 0;
  countdown_ = 0;
  base_addr_ = 0;
  beat_index_ = 0;
}

SingleOutstandingVirtualMemoryModel::Outputs
SingleOutstandingVirtualMemoryModel::comb() const {
  return Outputs{
      .req_ready = !busy_,
      .resp_valid = resp_valid_,
      .resp_data = resp_data_,
  };
}

void SingleOutstandingVirtualMemoryModel::tick(bool req_fire, uint64_t req_addr,
                                               bool resp_fire) {
  // 响应握手推进优先：只有在 resp_valid 为真时，resp_fire 才合法。
  if (resp_fire) {
    if (!busy_ || !resp_valid_) {
      throw std::runtime_error(
          "Protocol violation: resp_fire without pending valid response");
    }

    beat_index_ += 1;
    if (beat_index_ >= config_.burst_beats) {
      // 最后一拍完成，释放单在途事务。
      busy_ = false;
      resp_valid_ = false;
      resp_data_ = 0;
      countdown_ = 0;
      base_addr_ = 0;
      beat_index_ = 0;
    } else {
      // 进入下一拍，先装载数据，再经过拍间延迟后对外拉高 resp_valid。
      resp_data_ =
          backend_.read(base_addr_ + static_cast<uint64_t>(beat_index_) * config_.beat_bytes);
      resp_valid_ = false;
      countdown_ = config_.inter_beat_latency;
    }
  }

  // 请求握手：单在途模型在 busy 期间不允许接收新请求。
  if (req_fire) {
    if (busy_) {
      throw std::runtime_error("Protocol violation: req_fire while model is busy");
    }
    if (!alignment_ok(req_addr)) {
      throw std::runtime_error("Protocol violation: req_addr is not burst aligned");
    }

    busy_ = true;
    resp_valid_ = false;
    base_addr_ = req_addr;
    beat_index_ = 0;
    resp_data_ = backend_.read(base_addr_);
    countdown_ = config_.response_latency;
  }

  // 倒计时归零时，当前拍对外变为有效；直到 resp_fire 发生才推进。
  if (busy_ && !resp_valid_) {
    if (countdown_ == 0) {
      resp_valid_ = true;
    } else {
      countdown_ -= 1;
    }
  }
}

uint64_t SingleOutstandingVirtualMemoryModel::burst_bytes() const {
  return static_cast<uint64_t>(config_.burst_beats) * config_.beat_bytes;
}

bool SingleOutstandingVirtualMemoryModel::alignment_ok(uint64_t addr) const {
  if (!config_.enforce_alignment) {
    return true;
  }
  const uint64_t bytes = burst_bytes();
  return (addr % bytes) == 0;
}

} // namespace flow_vmem
