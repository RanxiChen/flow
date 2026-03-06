#include <cstdlib>
#include <exception>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <deque>
#include <vector>

#include <verilated.h>
#include <verilated_vcd_c.h>

#include "VICache.h"
#include "memory_model.h"

struct TbConfig {
  uint64_t max_cycles = 2000;
  uint32_t response_latency = 12;
  uint32_t inter_beat_latency = 2;
  std::string test_mode = "single"; // single | double
  uint64_t single_req_addr = 0x1000;
  uint64_t single_timeout_cycles = 300;
  uint64_t post_cycles = 80;
  bool enable_vcd = true;
};

struct TbStats {
  uint64_t cycles = 0;
  uint64_t commer_req_handshakes = 0;
  uint64_t commer_resp_handshakes = 0;
  uint64_t bursts_completed = 0;
  uint64_t busy_cycles = 0;
  uint64_t backpressure_cycles = 0;
  uint64_t post_cycles = 0;
  uint64_t post_commer_req_handshakes = 0;
  uint64_t post_commer_resp_handshakes = 0;
  uint64_t post_dst_resp_handshakes = 0;
};

struct AccessTimeline {
  bool dst_req_seen = false;
  bool dst_resp_seen = false;
  uint64_t dst_req_cycle = 0;
  uint64_t dst_resp_cycle = 0;

  bool commer_req_seen = false;
  bool commer_last_resp_seen = false;
  uint64_t commer_req_cycle = 0;
  uint64_t commer_last_resp_cycle = 0;
};

struct PendingCommerTxn {
  bool valid = false;
  uint32_t beats_seen = 0;
};

struct AccessTiming {
  bool req_seen = false;
  bool resp_seen = false;
  uint64_t req_cycle = 0;
  uint64_t resp_cycle = 0;
};

struct InflightReq {
  uint32_t idx = 0;
  uint64_t addr = 0;
  uint32_t expected_data = 0;
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

static std::string PercentStr(uint64_t part, uint64_t total) {
  if (total == 0) {
    return "0.00%";
  }
  const double ratio = static_cast<double>(part) * 100.0 / static_cast<double>(total);
  std::ostringstream oss;
  oss << std::fixed << std::setprecision(2) << ratio << "%";
  return oss.str();
}

static TbConfig ParseArgs(int argc, char **argv) {
  TbConfig cfg;
  for (int i = 1; i < argc; ++i) {
    std::string arg(argv[i]);
    if (arg.rfind("--max-cycles=", 0) == 0) {
      cfg.max_cycles = std::strtoull(arg.c_str() + 13, nullptr, 10);
    } else if (arg.rfind("--test=", 0) == 0) {
      cfg.test_mode = arg.substr(7);
    } else if (arg.rfind("--latency=", 0) == 0) {
      cfg.response_latency = static_cast<uint32_t>(std::strtoul(arg.c_str() + 10, nullptr, 10));
    } else if (arg.rfind("--beat-gap=", 0) == 0) {
      cfg.inter_beat_latency = static_cast<uint32_t>(std::strtoul(arg.c_str() + 11, nullptr, 10));
    } else if (arg.rfind("--single-addr=", 0) == 0) {
      cfg.single_req_addr = std::strtoull(arg.c_str() + 14, nullptr, 0);
    } else if (arg.rfind("--single-timeout=", 0) == 0) {
      cfg.single_timeout_cycles = std::strtoull(arg.c_str() + 17, nullptr, 10);
    } else if (arg.rfind("--post-cycles=", 0) == 0) {
      cfg.post_cycles = std::strtoull(arg.c_str() + 14, nullptr, 10);
    } else if (arg == "--no-vcd") {
      cfg.enable_vcd = false;
    } else if (arg == "--help") {
      std::cout
          << "Usage: icache_sim [--test=single|double] [--max-cycles=N] [--latency=N] "
             "[--beat-gap=N] [--single-addr=N] [--single-timeout=N] [--post-cycles=N] [--no-vcd]\n";
      std::exit(0);
    }
  }
  if (cfg.test_mode != "single" && cfg.test_mode != "double") {
    throw std::runtime_error("Invalid --test mode, expected single or double");
  }
  return cfg;
}

static uint32_t ExpectedInstWord(MemoryBackend &backend, uint64_t req_addr) {
  if ((req_addr & 0x3ULL) != 0) {
    throw std::runtime_error("Testcase configuration error: req_addr must be 4-byte aligned");
  }
  const uint64_t beat_addr = req_addr & ~0x7ULL;
  const uint64_t beat_data = backend.read(beat_addr);
  return ((req_addr & 0x4ULL) != 0) ? static_cast<uint32_t>((beat_data >> 32) & 0xffffffffULL)
                                    : static_cast<uint32_t>(beat_data & 0xffffffffULL);
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const TbConfig cfg = ParseArgs(argc, argv);

  auto *dut = new VICache;
  VerilatedVcdC *trace = nullptr;
  if (cfg.enable_vcd) {
    Verilated::traceEverOn(true);
    trace = new VerilatedVcdC;
    dut->trace(trace, 99);
    trace->open("icache_tb.vcd");
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

  AddrFuncBackend backend;
  SingleOutstandingMemModel mem(backend, cfg.response_latency, cfg.inter_beat_latency);
  TbStats stats;
  PendingCommerTxn pending_commer;
  AccessTimeline timeline;
  const uint32_t target_requests = (cfg.test_mode == "double") ? 2 : 1;
  const uint64_t req_addr_base = cfg.single_req_addr;
  std::vector<AccessTiming> access_timing(target_requests);
  std::deque<InflightReq> inflight_reqs;
  uint32_t req_sent = 0;
  uint32_t resp_completed = 0;

  dut->reset = 1;
  dut->io_commer_req_ready = 0;
  dut->io_commer_resp_valid = 0;
  dut->io_commer_resp_bits_data = 0;
  dut->io_dst_req_valid = 0;
  dut->io_dst_req_bits_addr = 0;
  dut->io_dst_resp_ready = 0;
  for (int i = 0; i < 5; ++i) {
    eval_clock(0);
    eval_clock(1);
  }

  dut->reset = 0;
  mem.reset();

  bool pass = false;
  std::string fail_reason;

  bool test_completed = false;
  try {
    while (!Verilated::gotFinish()) {
      const bool issue_req = (req_sent < target_requests);
      const uint64_t req_addr = (cfg.test_mode == "double" && req_sent == 1) ? (req_addr_base + 4ULL) : req_addr_base;
      dut->io_dst_req_valid = issue_req ? 1 : 0;
      dut->io_dst_req_bits_addr = req_addr;
      dut->io_dst_resp_ready = 1;

      const auto mem_out = mem.comb();
      dut->io_commer_req_ready = mem_out.req_ready;
      dut->io_commer_resp_valid = mem_out.resp_valid;
      dut->io_commer_resp_bits_data = mem_out.resp_data;

      eval_clock(0);
      const uint64_t current_cycle = stats.cycles;
      const bool commer_req_fire = dut->io_commer_req_valid && dut->io_commer_req_ready;
      const bool commer_resp_fire = dut->io_commer_resp_valid && dut->io_commer_resp_ready;
      const uint64_t commer_req_addr = dut->io_commer_req_bits_addr;

      const bool dst_req_fire = dut->io_dst_req_valid && dut->io_dst_req_ready;
      const bool dst_resp_fire = dut->io_dst_resp_valid && dut->io_dst_resp_ready;
      const uint32_t dst_resp_data = dut->io_dst_resp_bits_data;
      eval_clock(1);
      stats.cycles += 1;

      if (!mem_out.req_ready) {
        stats.backpressure_cycles += 1;
      }
      if (mem.busy()) {
        stats.busy_cycles += 1;
      }

      if (commer_req_fire) {
        stats.commer_req_handshakes += 1;
        pending_commer.valid = true;
        pending_commer.beats_seen = 0;
        if (!timeline.commer_req_seen) {
          timeline.commer_req_seen = true;
          timeline.commer_req_cycle = current_cycle;
        }
      }
      if (commer_resp_fire) {
        if (!pending_commer.valid) {
          throw std::runtime_error("protocol_error | commer resp without pending burst");
        }
        stats.commer_resp_handshakes += 1;
        timeline.commer_last_resp_seen = true;
        timeline.commer_last_resp_cycle = current_cycle;
        pending_commer.beats_seen += 1;
        if (pending_commer.beats_seen > SingleOutstandingMemModel::kBurstBeats) {
          throw std::runtime_error("protocol_error | commer resp beats exceed burst length");
        }
        if (pending_commer.beats_seen == SingleOutstandingMemModel::kBurstBeats) {
          pending_commer.valid = false;
          stats.bursts_completed += 1;
          timeline.commer_last_resp_seen = true;
          timeline.commer_last_resp_cycle = current_cycle;
        }
      }
      mem.tick(commer_req_fire, commer_req_addr, commer_resp_fire);

      if (dst_req_fire) {
        if (req_sent >= target_requests) {
          fail_reason = "protocol_error | dst req handshakes exceed target requests";
          break;
        }
        const uint32_t expected_data_for_req = ExpectedInstWord(backend, req_addr);
        access_timing[req_sent].req_seen = true;
        access_timing[req_sent].req_cycle = current_cycle;
        inflight_reqs.push_back(InflightReq{
            .idx = req_sent,
            .addr = req_addr,
            .expected_data = expected_data_for_req,
        });
        req_sent += 1;
        if (!timeline.dst_req_seen) {
          timeline.dst_req_seen = true;
          timeline.dst_req_cycle = current_cycle;
        }
      }

      if (dst_resp_fire) {
        if (inflight_reqs.empty()) {
          fail_reason = "protocol_error | dst resp without inflight request";
          break;
        }
        const InflightReq inflight = inflight_reqs.front();
        inflight_reqs.pop_front();
        const uint32_t idx = inflight.idx;
        access_timing[idx].resp_seen = true;
        access_timing[idx].resp_cycle = current_cycle;
        if (dst_resp_data != inflight.expected_data) {
          fail_reason = "data_mismatch | access=" + std::to_string(idx) + " addr=" + Hex64(inflight.addr) +
                        " expected=" + Hex32(inflight.expected_data) + " got=" + Hex32(dst_resp_data);
          break;
        }
        resp_completed += 1;
        if (!timeline.dst_resp_seen) {
          timeline.dst_resp_seen = true;
          timeline.dst_resp_cycle = current_cycle;
        }
      }

      for (const InflightReq &inflight : inflight_reqs) {
        const uint32_t idx = inflight.idx;
        if (access_timing[idx].req_seen &&
            (current_cycle - access_timing[idx].req_cycle + 1 > cfg.single_timeout_cycles)) {
          fail_reason = "timeout_wait_resp | access=" + std::to_string(idx) + " addr=" + Hex64(inflight.addr) +
                        " timeout_cycles=" + std::to_string(cfg.single_timeout_cycles);
          break;
        }
      }
      if (!fail_reason.empty()) {
        break;
      }

      if (req_sent == target_requests && resp_completed == target_requests) {
        if (!inflight_reqs.empty()) {
          fail_reason = "protocol_error | completed counts but inflight queue is not empty";
          break;
        }
        test_completed = true;
        break;
      }
      if (stats.cycles >= cfg.max_cycles) {
        fail_reason = "timeout_global | max_cycles=" + std::to_string(cfg.max_cycles);
        break;
      }
    }

    if (test_completed) {
      for (uint64_t i = 0; i < cfg.post_cycles && !Verilated::gotFinish(); ++i) {
        dut->io_dst_req_valid = 0;
        dut->io_dst_req_bits_addr = 0;
        dut->io_dst_resp_ready = 1;

        const auto mem_out = mem.comb();
        dut->io_commer_req_ready = mem_out.req_ready;
        dut->io_commer_resp_valid = mem_out.resp_valid;
        dut->io_commer_resp_bits_data = mem_out.resp_data;

        eval_clock(0);
        const bool commer_req_fire = dut->io_commer_req_valid && dut->io_commer_req_ready;
        const bool commer_resp_fire = dut->io_commer_resp_valid && dut->io_commer_resp_ready;
        const uint64_t commer_req_addr = dut->io_commer_req_bits_addr;
        const bool dst_resp_fire = dut->io_dst_resp_valid && dut->io_dst_resp_ready;
        eval_clock(1);

        if (commer_req_fire) {
          stats.post_commer_req_handshakes += 1;
        }
        if (commer_resp_fire) {
          stats.post_commer_resp_handshakes += 1;
        }
        if (dst_resp_fire) {
          stats.post_dst_resp_handshakes += 1;
        }
        mem.tick(commer_req_fire, commer_req_addr, commer_resp_fire);
        stats.post_cycles += 1;
        stats.cycles += 1;
      }
      pass = true;
    }
  } catch (const std::exception &ex) {
    fail_reason = ex.what();
  }

  if (trace != nullptr) {
    trace->close();
    delete trace;
  }

  dut->final();
  delete dut;

  std::cout << "==== TB Summary ====\n";
  std::cout << "cycles=" << stats.cycles << " commer_req_hs=" << stats.commer_req_handshakes
            << " commer_resp_hs=" << stats.commer_resp_handshakes << " bursts_done=" << stats.bursts_completed
            << " busy_cycles=" << stats.busy_cycles << " backpressure_cycles=" << stats.backpressure_cycles
            << " req_sent=" << req_sent << "/" << target_requests << " resp_done=" << resp_completed << "/"
            << target_requests << " test_mode=" << cfg.test_mode << "\n";
  std::cout << "post_cycles=" << stats.post_cycles << " post_commer_req_hs=" << stats.post_commer_req_handshakes
            << " post_commer_resp_hs=" << stats.post_commer_resp_handshakes
            << " post_dst_resp_hs=" << stats.post_dst_resp_handshakes << "\n";

  if (timeline.dst_req_seen && timeline.dst_resp_seen) {
    const uint64_t dst_round_trip_cycles = timeline.dst_resp_cycle - timeline.dst_req_cycle + 1;
    std::cout << "dst_req_cycle=" << timeline.dst_req_cycle << " dst_resp_cycle=" << timeline.dst_resp_cycle
              << " dst_round_trip_cycles=" << dst_round_trip_cycles
              << " (" << PercentStr(dst_round_trip_cycles, stats.cycles) << " of total)\n";
  } else {
    std::cout << "dst_req_cycle=<none> dst_resp_cycle=<none> dst_round_trip_cycles=<none>\n";
  }

  if (timeline.commer_req_seen && timeline.commer_last_resp_seen) {
    const uint64_t commer_transfer_cycles = timeline.commer_last_resp_cycle - timeline.commer_req_cycle + 1;
    std::cout << "commer_req_cycle=" << timeline.commer_req_cycle
              << " commer_last_resp_cycle=" << timeline.commer_last_resp_cycle
              << " commer_transfer_cycles=" << commer_transfer_cycles
              << " (" << PercentStr(commer_transfer_cycles, stats.cycles) << " of total)\n";
  } else {
    std::cout << "commer_req_cycle=<none> commer_last_resp_cycle=<none> commer_transfer_cycles=<none>\n";
  }

  for (uint32_t i = 0; i < target_requests; ++i) {
    if (access_timing[i].req_seen && access_timing[i].resp_seen) {
      const uint64_t latency = access_timing[i].resp_cycle - access_timing[i].req_cycle + 1;
      std::cout << "access[" << i << "] req_cycle=" << access_timing[i].req_cycle
                << " resp_cycle=" << access_timing[i].resp_cycle << " latency_cycles=" << latency << " ("
                << PercentStr(latency, stats.cycles) << " of total)\n";
    } else {
      std::cout << "access[" << i << "] req_cycle=<none> resp_cycle=<none> latency_cycles=<none>\n";
    }
  }

  if (target_requests == 1 && access_timing[0].req_seen && access_timing[0].resp_seen) {
    const uint64_t latency = access_timing[0].resp_cycle - access_timing[0].req_cycle + 1;
    std::cout << "single_access_total_cycles(dst_req_fire->dst_resp_fire)=" << latency << "\n";
    std::cout << "single_access_ch: 从dst端请求握手到响应握手返回，总耗时 " << latency << " 个周期\n";
  }
  if (target_requests == 2 && access_timing[0].req_seen && access_timing[0].resp_seen && access_timing[1].req_seen &&
      access_timing[1].resp_seen) {
    const uint64_t latency0 = access_timing[0].resp_cycle - access_timing[0].req_cycle + 1;
    const uint64_t latency1 = access_timing[1].resp_cycle - access_timing[1].req_cycle + 1;
    const uint64_t total_window = access_timing[1].resp_cycle - access_timing[0].req_cycle + 1;
    std::cout << "double_access_total_cycles(first_req_fire->second_resp_fire)=" << total_window << "\n";
    std::cout << "double_access_compare first=" << latency0 << " second=" << latency1
              << " second_vs_first=" << PercentStr(latency1, latency0) << "\n";
    std::cout << "double_access_ch: 第1次访问耗时 " << latency0 << " 周期, 第2次访问耗时 " << latency1
              << " 周期, 两次窗口总耗时 " << total_window << " 周期\n";
  }

  if (!pass) {
    std::cerr << "TB FAIL: " << (fail_reason.empty() ? "unknown" : fail_reason) << "\n";
    return 1;
  }

  std::cout << "TB PASS: test_mode=" << cfg.test_mode << " completed_requests=" << resp_completed << "\n";
  return 0;
}
