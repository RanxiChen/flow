#ifndef FLOW_VMEM_VIRTUAL_MEMORY_MODEL_H_
#define FLOW_VMEM_VIRTUAL_MEMORY_MODEL_H_

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace flow_vmem {

class VirtualMemoryBackend {
public:
  virtual ~VirtualMemoryBackend() = default;
  virtual uint64_t read(uint64_t addr) = 0;

  // 预留给未来可写接口，当前默认返回 false 表示未实现。
  virtual bool write(uint64_t addr, uint64_t data, uint8_t mask = 0xFF) {
    (void)addr;
    (void)data;
    (void)mask;
    return false;
  }
};

class AddressFunctionBackend final : public VirtualMemoryBackend {
public:
  uint64_t read(uint64_t addr) override;
};

enum class MemoryLayoutMode {
  kAddressFunction = 0,
  kSegmentMap = 1,
};

enum class MissPolicy {
  kPoison = 0,
};

struct MemoryLayoutConfig {
  MemoryLayoutMode mode = MemoryLayoutMode::kAddressFunction;
  MissPolicy miss_policy = MissPolicy::kPoison;
  uint64_t poison_value = 0xDEADDEADDEADDEADULL;
  bool enable_bus_error = false; // 预留：后续可支持总线错误返回。
  bool page_mode = false;        // 预留：后续可支持分页布局。
  bool permission_check = false; // 预留：后续可支持权限校验。
};

struct MemoryAccessResult {
  bool hit = true;
  bool bus_error = false;
  uint64_t data = 0;
};

class ISegmentStorage {
public:
  virtual ~ISegmentStorage() = default;
  [[nodiscard]] virtual uint64_t base_addr() const = 0;
  [[nodiscard]] virtual uint64_t size_bytes() const = 0;
  [[nodiscard]] virtual bool contains(uint64_t addr, uint64_t bytes) const = 0;
  [[nodiscard]] virtual MemoryAccessResult read64(uint64_t addr) const = 0;
};

class Word32SegmentStorage final : public ISegmentStorage {
public:
  Word32SegmentStorage(uint64_t base_addr, uint64_t word_count, uint32_t fill_value = 0);

  [[nodiscard]] uint64_t base_addr() const override { return base_addr_; }
  [[nodiscard]] uint64_t size_bytes() const override { return words_.size() * 4ULL; }
  [[nodiscard]] bool contains(uint64_t addr, uint64_t bytes) const override;
  [[nodiscard]] MemoryAccessResult read64(uint64_t addr) const override;

  void write32_by_index(uint64_t index, uint32_t data);
  [[nodiscard]] uint64_t word_count() const { return words_.size(); }

private:
  uint64_t base_addr_ = 0;
  std::vector<uint32_t> words_;
};

class Word64SegmentStorage final : public ISegmentStorage {
public:
  Word64SegmentStorage(uint64_t base_addr, uint64_t word_count, uint64_t fill_value = 0);

  [[nodiscard]] uint64_t base_addr() const override { return base_addr_; }
  [[nodiscard]] uint64_t size_bytes() const override { return words_.size() * 8ULL; }
  [[nodiscard]] bool contains(uint64_t addr, uint64_t bytes) const override;
  [[nodiscard]] MemoryAccessResult read64(uint64_t addr) const override;

  void write64_by_index(uint64_t index, uint64_t data);
  [[nodiscard]] uint64_t word_count() const { return words_.size(); }

private:
  uint64_t base_addr_ = 0;
  std::vector<uint64_t> words_;
};

class SegmentMapBackend final : public VirtualMemoryBackend {
public:
  explicit SegmentMapBackend(MemoryLayoutConfig config = {});

  void set_config(const MemoryLayoutConfig &config) { config_ = config; }
  [[nodiscard]] const MemoryLayoutConfig &config() const { return config_; }

  void add_segment(std::shared_ptr<ISegmentStorage> segment);
  [[nodiscard]] uint64_t read(uint64_t addr) override;
  [[nodiscard]] MemoryAccessResult try_read64(uint64_t addr) const;
  [[nodiscard]] size_t segment_count() const { return segments_.size(); }

private:
  [[nodiscard]] bool overlaps_any(const ISegmentStorage &candidate) const;

  MemoryLayoutConfig config_;
  std::vector<std::shared_ptr<ISegmentStorage>> segments_;
};

class ConfigurableMemoryBackend final : public VirtualMemoryBackend {
public:
  explicit ConfigurableMemoryBackend(MemoryLayoutConfig config = {});

  void set_layout_config(const MemoryLayoutConfig &config);
  [[nodiscard]] const MemoryLayoutConfig &layout_config() const { return layout_config_; }

  [[nodiscard]] SegmentMapBackend &segment_backend() { return segment_backend_; }
  [[nodiscard]] const SegmentMapBackend &segment_backend() const { return segment_backend_; }
  [[nodiscard]] uint64_t read(uint64_t addr) override;

private:
  MemoryLayoutConfig layout_config_;
  AddressFunctionBackend address_backend_;
  SegmentMapBackend segment_backend_;
};

struct VirtualMemoryConfig {
  uint32_t response_latency = 12;
  uint32_t inter_beat_latency = 2;
  uint32_t burst_beats = 4;
  uint32_t beat_bytes = 8;
  bool enforce_alignment = true;
};

class SingleOutstandingVirtualMemoryModel {
public:
  struct Outputs {
    bool req_ready = true;
    bool resp_valid = false;
    uint64_t resp_data = 0;
  };

  explicit SingleOutstandingVirtualMemoryModel(VirtualMemoryBackend &backend,
                                               VirtualMemoryConfig config = {});

  void reset();
  Outputs comb() const;
  void tick(bool req_fire, uint64_t req_addr, bool resp_fire);

  [[nodiscard]] bool busy() const { return busy_; }
  [[nodiscard]] const VirtualMemoryConfig &config() const { return config_; }

private:
  [[nodiscard]] uint64_t burst_bytes() const;
  [[nodiscard]] bool alignment_ok(uint64_t addr) const;

  VirtualMemoryBackend &backend_;
  VirtualMemoryConfig config_;

  bool busy_ = false;
  bool resp_valid_ = false;
  uint64_t resp_data_ = 0;
  uint32_t countdown_ = 0;
  uint64_t base_addr_ = 0;
  uint32_t beat_index_ = 0;
};

} // namespace flow_vmem

#endif
