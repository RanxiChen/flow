#ifndef BACKEND_MODEL_H_
#define BACKEND_MODEL_H_

#include <cstdint>
#include <vector>

class BackendModel {
public:
  // Outputs drives DUT's backend-facing control pins for the current cycle.
  struct Outputs {
    bool instpack_ready;
    bool pc_misfetch;
    uint64_t pc_redir;
  };

  // Commit captures one instpack handshake observed by the model.
  struct Commit {
    uint64_t cycle;
    uint64_t pc;
    uint32_t data;
    bool misaligned;
  };

  BackendModel();

  void reset();
  Outputs comb() const;
  void tick(bool instpack_valid, uint32_t instpack_data, uint64_t instpack_pc, bool instpack_misaligned);

  void set_always_ready(bool always_ready);
  void set_ready_pattern(const std::vector<bool> &pattern);
  void schedule_branch(uint64_t trigger_cycle, uint64_t target_pc);

  [[nodiscard]] uint64_t cycle() const { return cycle_; }
  [[nodiscard]] bool branch_fired() const { return branch_fired_; }
  [[nodiscard]] uint64_t branch_cycle() const { return branch_trigger_cycle_; }
  [[nodiscard]] uint64_t branch_target() const { return branch_target_pc_; }
  [[nodiscard]] uint64_t handshakes() const { return handshakes_; }
  [[nodiscard]] uint64_t stalled_valid_cycles() const { return stalled_valid_cycles_; }
  [[nodiscard]] const std::vector<Commit> &commits() const { return commits_; }

private:
  bool ready_for_cycle(uint64_t cycle) const;

  uint64_t cycle_ = 0;
  bool always_ready_ = true;
  std::vector<bool> ready_pattern_;

  bool branch_enabled_ = false;
  bool branch_fired_ = false;
  uint64_t branch_trigger_cycle_ = 0;
  uint64_t branch_target_pc_ = 0;

  uint64_t handshakes_ = 0;
  uint64_t stalled_valid_cycles_ = 0;
  std::vector<Commit> commits_;
};

#endif
