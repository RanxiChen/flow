#include <cstdlib>
#include <exception>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include <verilated.h>
#include <verilated_vcd_c.h>

#include "VGustEngine.h"
#include "backend_model.h"
#include "memory_model.h"

enum class TestMode {
  kSequential,
  kBackpressure,
  kBranch,
  kMisalign,
};

struct TbConfig {
  TestMode mode = TestMode::kSequential;
  uint64_t max_cycles = 8000;
  uint32_t response_latency = 12;
  uint32_t inter_beat_latency = 2;
  uint64_t reset_addr = 0x1000;
  uint64_t target_commits = 16;
  std::string ready_pattern = "111001";
  uint64_t branch_cycle = 120;
  uint64_t branch_target = 0x2000;
  uint64_t branch_post_commits = 8;
  uint64_t post_cycles = 0;
  bool enable_vcd = true;
};

struct TbStats {
  uint64_t cycles = 0;
  uint64_t mem_req_hs = 0;
  uint64_t mem_resp_hs = 0;
};

static std::string Hex64(uint64_t v) {
  std::ostringstream oss;
  oss << "0x" << std::hex << std::setw(16) << std::setfill('0') << v;
  return oss.str();
}

static std::string Hex32(uint32_t v) {
  std::ostringstream oss;
  oss << "0x" << std::hex << std::setw(8) << std::setfill('0') << v;
  return oss.str();
}

static TestMode ParseMode(const std::string &value) {
  if (value == "sequential") {
    return TestMode::kSequential;
  }
  if (value == "backpressure") {
    return TestMode::kBackpressure;
  }
  if (value == "branch") {
    return TestMode::kBranch;
  }
  if (value == "misalign") {
    return TestMode::kMisalign;
  }
  throw std::runtime_error("Invalid --test mode, expected sequential|backpressure|branch|misalign");
}

static std::vector<bool> ParseReadyPattern(const std::string &pattern) {
  std::vector<bool> parsed;
  parsed.reserve(pattern.size());
  for (char c : pattern) {
    if (c == '1') {
      parsed.push_back(true);
    } else if (c == '0') {
      parsed.push_back(false);
    } else {
      throw std::runtime_error("Invalid --ready-pattern, expected only 0/1 chars");
    }
  }
  if (parsed.empty()) {
    throw std::runtime_error("Invalid --ready-pattern, expected non-empty string");
  }
  return parsed;
}

static TbConfig ParseArgs(int argc, char **argv) {
  TbConfig cfg;
  for (int i = 1; i < argc; ++i) {
    std::string arg(argv[i]);
    if (arg.rfind("--test=", 0) == 0) {
      cfg.mode = ParseMode(arg.substr(7));
    } else if (arg.rfind("--max-cycles=", 0) == 0) {
      cfg.max_cycles = std::strtoull(arg.c_str() + 13, nullptr, 10);
    } else if (arg.rfind("--latency=", 0) == 0) {
      cfg.response_latency = static_cast<uint32_t>(std::strtoul(arg.c_str() + 10, nullptr, 10));
    } else if (arg.rfind("--beat-gap=", 0) == 0) {
      cfg.inter_beat_latency = static_cast<uint32_t>(std::strtoul(arg.c_str() + 11, nullptr, 10));
    } else if (arg.rfind("--reset-addr=", 0) == 0) {
      cfg.reset_addr = std::strtoull(arg.c_str() + 13, nullptr, 0);
    } else if (arg.rfind("--target-commits=", 0) == 0) {
      cfg.target_commits = std::strtoull(arg.c_str() + 17, nullptr, 10);
    } else if (arg.rfind("--ready-pattern=", 0) == 0) {
      cfg.ready_pattern = arg.substr(16);
    } else if (arg.rfind("--branch-cycle=", 0) == 0) {
      cfg.branch_cycle = std::strtoull(arg.c_str() + 15, nullptr, 10);
    } else if (arg.rfind("--branch-target=", 0) == 0) {
      cfg.branch_target = std::strtoull(arg.c_str() + 16, nullptr, 0);
    } else if (arg.rfind("--branch-post-commits=", 0) == 0) {
      cfg.branch_post_commits = std::strtoull(arg.c_str() + 22, nullptr, 10);
    } else if (arg.rfind("--post-cycles=", 0) == 0) {
      cfg.post_cycles = std::strtoull(arg.c_str() + 14, nullptr, 10);
    } else if (arg == "--no-vcd") {
      cfg.enable_vcd = false;
    } else if (arg == "--help") {
      std::cout
          << "Usage: gustengine_sim [--test=sequential|backpressure|branch|misalign] [--max-cycles=N] "
             "[--latency=N] [--beat-gap=N] [--reset-addr=N] [--target-commits=N] [--ready-pattern=bits] "
             "[--branch-cycle=N] [--branch-target=N] [--branch-post-commits=N] [--post-cycles=N] [--no-vcd]\n";
      std::exit(0);
    }
  }

  if (cfg.target_commits == 0) {
    throw std::runtime_error("Invalid --target-commits, expected > 0");
  }

  if (cfg.mode == TestMode::kBackpressure) {
    (void)ParseReadyPattern(cfg.ready_pattern);
  }

  if (cfg.mode == TestMode::kBranch && cfg.branch_post_commits == 0) {
    throw std::runtime_error("Invalid --branch-post-commits, expected > 0");
  }

  if (cfg.mode == TestMode::kMisalign && cfg.reset_addr == 0x1000) {
    cfg.reset_addr = 0x1002;
  }

  return cfg;
}

static uint32_t ExpectedInstWord(MemoryBackend &backend, uint64_t req_addr) {
  if ((req_addr & 0x3ULL) != 0) {
    throw std::runtime_error("ExpectedInstWord requires 4-byte aligned address");
  }
  const uint64_t beat_addr = req_addr & ~0x7ULL;
  const uint64_t beat_data = backend.read(beat_addr);
  return ((req_addr & 0x4ULL) != 0) ? static_cast<uint32_t>((beat_data >> 32) & 0xffffffffULL)
                                    : static_cast<uint32_t>(beat_data & 0xffffffffULL);
}

static bool DataMatchesExpectedWindow(MemoryBackend &backend, uint64_t pc, uint32_t data) {
  if ((pc & 0x3ULL) != 0) {
    return false;
  }

  const uint32_t at_pc = ExpectedInstWord(backend, pc);
  if (data == at_pc) {
    return true;
  }
  const uint32_t at_next = ExpectedInstWord(backend, pc + 4ULL);
  if (data == at_next) {
    return true;
  }
  if (pc >= 4ULL) {
    const uint32_t at_prev = ExpectedInstWord(backend, pc - 4ULL);
    if (data == at_prev) {
      return true;
    }
  }
  return false;
}

static size_t FindFirstCommitAfterCycle(const std::vector<BackendModel::Commit> &commits, uint64_t cycle) {
  for (size_t i = 0; i < commits.size(); ++i) {
    if (commits[i].cycle > cycle) {
      return i;
    }
  }
  return commits.size();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const TbConfig cfg = ParseArgs(argc, argv);

  auto *dut = new VGustEngine;
  VerilatedVcdC *trace = nullptr;
  if (cfg.enable_vcd) {
    Verilated::traceEverOn(true);
    trace = new VerilatedVcdC;
    dut->trace(trace, 99);
    trace->open("gustengine_tb.vcd");
  }

  vluint64_t sim_time = 0;
  auto eval_clock = [&](int clk) {
    dut->clock = clk;
    dut->eval();
    if (trace != nullptr) {
      trace->dump(sim_time);
    }
    sim_time += 1;
  };

  AddrFuncBackend backend_data;
  SingleOutstandingMemModel mem(backend_data, cfg.response_latency, cfg.inter_beat_latency);
  BackendModel backend;
  TbStats stats;

  backend.set_always_ready(cfg.mode != TestMode::kBackpressure);
  if (cfg.mode == TestMode::kBackpressure) {
    backend.set_ready_pattern(ParseReadyPattern(cfg.ready_pattern));
  }
  if (cfg.mode == TestMode::kBranch) {
    backend.schedule_branch(cfg.branch_cycle, cfg.branch_target);
  }

  dut->reset = 1;
  dut->io_reset_addr = cfg.reset_addr;
  dut->io_instpack_ready = 0;
  dut->io_be_ctrl_flush = 0;
  dut->io_be_ctrl_pc_misfetch = 0;
  dut->io_be_ctrl_pc_redir = 0;
  dut->io_mem_req_ready = 0;
  dut->io_mem_resp_valid = 0;
  dut->io_mem_resp_bits_data = 0;
  for (int i = 0; i < 5; ++i) {
    eval_clock(0);
    eval_clock(1);
  }

  dut->reset = 0;
  mem.reset();
  backend.reset();

  bool pass = false;
  std::string fail_reason;
  bool reached_goal = false;
  uint64_t post_left = cfg.post_cycles;

  try {
    while (!Verilated::gotFinish() && stats.cycles < cfg.max_cycles) {
      const auto mem_out = mem.comb();
      const auto be_out = backend.comb();

      // Drive all DUT inputs for this cycle before evaluating combinational outputs.
      dut->io_reset_addr = cfg.reset_addr;
      dut->io_mem_req_ready = mem_out.req_ready;
      dut->io_mem_resp_valid = mem_out.resp_valid;
      dut->io_mem_resp_bits_data = mem_out.resp_data;
      dut->io_instpack_ready = be_out.instpack_ready;
      dut->io_be_ctrl_flush = 0;
      dut->io_be_ctrl_pc_misfetch = be_out.pc_misfetch;
      dut->io_be_ctrl_pc_redir = be_out.pc_redir;

      eval_clock(0);

      // Sample handshake events on the low phase, then tick models once per cycle.
      const bool mem_req_fire = dut->io_mem_req_valid && dut->io_mem_req_ready;
      const bool mem_resp_fire = dut->io_mem_resp_valid && dut->io_mem_resp_ready;
      const uint64_t mem_req_addr = dut->io_mem_req_bits_addr;

      if (mem_req_fire) {
        stats.mem_req_hs += 1;
      }
      if (mem_resp_fire) {
        stats.mem_resp_hs += 1;
      }

      mem.tick(mem_req_fire, mem_req_addr, mem_resp_fire);
      backend.tick(dut->io_instpack_valid, dut->io_instpack_bits_data, dut->io_instpack_bits_pc,
                   dut->io_instpack_bits_instruction_address_misaligned);

      eval_clock(1);
      stats.cycles += 1;

      const auto &commits_now = backend.commits();
      if (!reached_goal) {
        if (cfg.mode == TestMode::kBranch) {
          // Branch mode waits until there are enough post-redirect commits to validate.
          const size_t branch_idx = FindFirstCommitAfterCycle(commits_now, cfg.branch_cycle);
          const bool branch_window_ready =
              (branch_idx < commits_now.size()) &&
              (commits_now.size() >= (branch_idx + static_cast<size_t>(cfg.branch_post_commits)));
          if (backend.branch_fired() && branch_window_ready &&
              commits_now.size() >= static_cast<size_t>(cfg.target_commits)) {
            reached_goal = true;
          }
        } else if (backend.handshakes() >= cfg.target_commits) {
          reached_goal = true;
        }
      } else if (post_left > 0) {
        post_left -= 1;
      } else {
        break;
      }
    }

    if (!reached_goal) {
      throw std::runtime_error("timeout | test goal not reached before max cycles");
    }

    const auto &commits = backend.commits();
    if (commits.size() < static_cast<size_t>(cfg.target_commits)) {
      throw std::runtime_error("check_fail | committed instpack count is smaller than target");
    }

    if (cfg.mode == TestMode::kSequential || cfg.mode == TestMode::kBackpressure) {
      if (commits[0].pc != cfg.reset_addr) {
        throw std::runtime_error("first_pc_mismatch | got=" + Hex64(commits[0].pc) +
                                 " expected=" + Hex64(cfg.reset_addr));
      }
      for (size_t i = 0; i < static_cast<size_t>(cfg.target_commits); ++i) {
        if (i > 0) {
          if (commits[i].pc < commits[i - 1].pc) {
            throw std::runtime_error("pc_decrease_invalid | idx=" + std::to_string(i));
          }
          const uint64_t delta = commits[i].pc - commits[i - 1].pc;
          if ((delta & 0x3ULL) != 0ULL || delta > 32ULL) {
            throw std::runtime_error("pc_step_invalid | idx=" + std::to_string(i) + " prev=" +
                                     Hex64(commits[i - 1].pc) + " curr=" + Hex64(commits[i].pc));
          }
        }
        if (commits[i].misaligned) {
          throw std::runtime_error("misalign_flag_unexpected | idx=" + std::to_string(i));
        }
        if (cfg.mode == TestMode::kSequential &&
            !DataMatchesExpectedWindow(backend_data, commits[i].pc, commits[i].data)) {
          throw std::runtime_error("data_mismatch_window | idx=" + std::to_string(i) +
                                   " pc=" + Hex64(commits[i].pc) + " data=" +
                                   Hex32(commits[i].data));
        }
      }
      if (cfg.mode == TestMode::kBackpressure && backend.stalled_valid_cycles() == 0) {
        throw std::runtime_error("backpressure_not_observed | no valid-stall cycles detected");
      }
    } else if (cfg.mode == TestMode::kMisalign) {
      if (commits[0].pc != cfg.reset_addr) {
        throw std::runtime_error("first_pc_mismatch | got=" + Hex64(commits[0].pc) +
                                 " expected=" + Hex64(cfg.reset_addr));
      }
      for (size_t i = 0; i < static_cast<size_t>(cfg.target_commits); ++i) {
        if (i > 0) {
          if (commits[i].pc < commits[i - 1].pc) {
            throw std::runtime_error("pc_decrease_invalid | misalign idx=" + std::to_string(i));
          }
          const uint64_t delta = commits[i].pc - commits[i - 1].pc;
          if ((delta & 0x3ULL) != 0ULL || delta > 32ULL) {
            throw std::runtime_error("pc_step_invalid | misalign idx=" + std::to_string(i) +
                                     " prev=" + Hex64(commits[i - 1].pc) + " curr=" +
                                     Hex64(commits[i].pc));
          }
        }
        if (!commits[i].misaligned) {
          throw std::runtime_error("misalign_flag_missing | idx=" + std::to_string(i));
        }
      }
    } else {
      if (!backend.branch_fired()) {
        throw std::runtime_error("branch_not_fired | backend did not issue redirect");
      }

      const size_t branch_idx = FindFirstCommitAfterCycle(commits, cfg.branch_cycle);
      if (branch_idx >= commits.size()) {
        throw std::runtime_error("branch_effect_missing | no committed instpack after branch cycle");
      }
      if (commits[branch_idx].pc != cfg.branch_target) {
        throw std::runtime_error("branch_target_mismatch | got=" + Hex64(commits[branch_idx].pc) +
                                 " expected=" + Hex64(cfg.branch_target));
      }

      for (size_t i = 1; i < branch_idx; ++i) {
        if (commits[i].pc < commits[i - 1].pc) {
          throw std::runtime_error("pre_branch_pc_decrease_invalid | idx=" + std::to_string(i));
        }
        const uint64_t delta = commits[i].pc - commits[i - 1].pc;
        if ((delta & 0x3ULL) != 0ULL || delta > 32ULL) {
          throw std::runtime_error("pre_branch_pc_step_invalid | idx=" + std::to_string(i));
        }
      }

      const bool expected_misaligned = (cfg.branch_target & 0x3ULL) != 0;
      const size_t post_needed = branch_idx + static_cast<size_t>(cfg.branch_post_commits);
      if (commits.size() < post_needed) {
        throw std::runtime_error("branch_window_short | not enough commits after redirect");
      }
      for (size_t i = branch_idx; i < post_needed; ++i) {
        if (i == branch_idx) {
          if (commits[i].pc != cfg.branch_target) {
            throw std::runtime_error("post_branch_first_pc_mismatch | got=" + Hex64(commits[i].pc) +
                                     " expected=" + Hex64(cfg.branch_target));
          }
        } else {
          if (commits[i].pc < commits[i - 1].pc) {
            throw std::runtime_error("post_branch_pc_decrease_invalid | idx=" + std::to_string(i));
          }
          const uint64_t delta = commits[i].pc - commits[i - 1].pc;
          if ((delta & 0x3ULL) != 0ULL || delta > 32ULL) {
            throw std::runtime_error("post_branch_pc_step_invalid | idx=" + std::to_string(i));
          }
        }
        if (commits[i].misaligned != expected_misaligned) {
          throw std::runtime_error("post_branch_misaligned_flag_mismatch | idx=" + std::to_string(i));
        }
      }
    }

    pass = true;
  } catch (const std::exception &e) {
    fail_reason = e.what();
  }

  std::cout << "[GustEngine TB] cycles=" << stats.cycles << " mem_req_hs=" << stats.mem_req_hs
            << " mem_resp_hs=" << stats.mem_resp_hs << " instpack_hs=" << backend.handshakes()
            << " stalled_valid_cycles=" << backend.stalled_valid_cycles() << '\n';

  const auto &commits = backend.commits();
  if (!commits.empty()) {
    std::cout << "[GustEngine TB] first_commit: cycle=" << commits.front().cycle
              << " pc=" << Hex64(commits.front().pc) << " data=" << Hex32(commits.front().data)
              << " misaligned=" << commits.front().misaligned << '\n';
    std::cout << "[GustEngine TB] last_commit : cycle=" << commits.back().cycle
              << " pc=" << Hex64(commits.back().pc) << " data=" << Hex32(commits.back().data)
              << " misaligned=" << commits.back().misaligned << '\n';
  }

  if (pass) {
    std::cout << "TB PASS\n";
  } else {
    std::cout << "TB FAIL: " << fail_reason << "\n";
  }

  if (trace != nullptr) {
    trace->close();
    delete trace;
  }
  delete dut;

  return pass ? 0 : 1;
}
