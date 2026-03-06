#include "backend_model.h"

#include <stdexcept>

BackendModel::BackendModel() = default;

void BackendModel::reset() {
  cycle_ = 0;
  branch_fired_ = false;
  handshakes_ = 0;
  stalled_valid_cycles_ = 0;
  commits_.clear();
}

bool BackendModel::ready_for_cycle(uint64_t cycle) const {
  if (always_ready_) {
    return true;
  }
  if (ready_pattern_.empty()) {
    return true;
  }
  const size_t idx = static_cast<size_t>(cycle % ready_pattern_.size());
  return ready_pattern_[idx];
}

BackendModel::Outputs BackendModel::comb() const {
  const bool fire_branch = branch_enabled_ && !branch_fired_ && cycle_ == branch_trigger_cycle_;
  return Outputs{
      .instpack_ready = ready_for_cycle(cycle_),
      .pc_misfetch = fire_branch,
      .pc_redir = branch_target_pc_,
  };
}

void BackendModel::tick(bool instpack_valid, uint32_t instpack_data, uint64_t instpack_pc,
                       bool instpack_misaligned) {
  const bool ready = ready_for_cycle(cycle_);
  if (instpack_valid && !ready) {
    stalled_valid_cycles_ += 1;
  }

  if (instpack_valid && ready) {
    commits_.push_back(Commit{
        .cycle = cycle_,
        .pc = instpack_pc,
        .data = instpack_data,
        .misaligned = instpack_misaligned,
    });
    handshakes_ += 1;
  }

  if (branch_enabled_ && !branch_fired_ && cycle_ == branch_trigger_cycle_) {
    branch_fired_ = true;
  }

  cycle_ += 1;
}

void BackendModel::set_always_ready(bool always_ready) {
  always_ready_ = always_ready;
}

void BackendModel::set_ready_pattern(const std::vector<bool> &pattern) {
  ready_pattern_ = pattern;
  if (ready_pattern_.empty()) {
    throw std::runtime_error("ready pattern cannot be empty");
  }
}

void BackendModel::schedule_branch(uint64_t trigger_cycle, uint64_t target_pc) {
  branch_enabled_ = true;
  branch_trigger_cycle_ = trigger_cycle;
  branch_target_pc_ = target_pc;
}
