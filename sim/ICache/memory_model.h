#ifndef MEMORY_MODEL_H_
#define MEMORY_MODEL_H_

#include <cstdint>

class MemoryBackend {
public:
  virtual ~MemoryBackend() = default;

  virtual uint64_t read(uint64_t addr) = 0;

  // Reserved for future write-capable DUT interfaces.
  virtual bool write(uint64_t addr, uint64_t data, uint8_t mask = 0xFF) {
    (void)addr;
    (void)data;
    (void)mask;
    return false;
  }
};

class AddrFuncBackend final : public MemoryBackend {
public:
  uint64_t read(uint64_t addr) override;
};

class SingleOutstandingMemModel {
public:
  static constexpr uint32_t kBurstBeats = 4;
  static constexpr uint32_t kBeatBytes = 8;

  struct Outputs {
    bool req_ready;
    bool resp_valid;
    uint64_t resp_data;
  };

  explicit SingleOutstandingMemModel(MemoryBackend &backend, uint32_t response_latency = 12,
                                     uint32_t inter_beat_latency = 2);

  void reset();
  Outputs comb() const;
  void tick(bool req_fire, uint64_t req_addr, bool resp_fire);

  [[nodiscard]] bool busy() const { return busy_; }

private:
  MemoryBackend &backend_;
  uint32_t response_latency_;
  uint32_t inter_beat_latency_;

  bool busy_ = false;
  bool resp_valid_ = false;
  uint64_t resp_data_ = 0;
  uint32_t countdown_ = 0;
  uint64_t base_addr_ = 0;
  uint32_t beat_index_ = 0;
};

#endif
