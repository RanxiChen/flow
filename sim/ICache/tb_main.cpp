#include <cstdlib>
#include <exception>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>

#include <verilated.h>
#include <verilated_vcd_c.h>

#include "VICache.h"
#include "memory_model.h"

struct TbConfig {
  uint64_t max_transactions = 32;
  uint64_t max_cycles = 500;
  uint32_t response_latency = 1;
  bool enable_vcd = true;
};

struct TbStats {
  uint64_t cycles = 0;
  uint64_t req_handshakes = 0;
  uint64_t resp_handshakes = 0;
  uint64_t busy_cycles = 0;
  uint64_t backpressure_cycles = 0;
};

struct PendingTxn {
  bool valid = false;
  uint64_t req_addr = 0;
  uint64_t accepted_cycle = 0;
  bool observed_resp_data_valid = false;
  uint64_t observed_resp_data = 0;
};

static std::string Hex64(uint64_t v) {
  std::ostringstream oss;
  oss << "0x" << std::hex << std::setw(16) << std::setfill('0') << v;
  return oss.str();
}

static TbConfig ParseArgs(int argc, char **argv) {
  TbConfig cfg;
  for (int i = 1; i < argc; ++i) {
    std::string arg(argv[i]);
    if (arg.rfind("--max-transactions=", 0) == 0) {
      cfg.max_transactions = std::strtoull(arg.c_str() + 19, nullptr, 10);
    } else if (arg.rfind("--max-cycles=", 0) == 0) {
      cfg.max_cycles = std::strtoull(arg.c_str() + 13, nullptr, 10);
    } else if (arg.rfind("--latency=", 0) == 0) {
      cfg.response_latency = static_cast<uint32_t>(std::strtoul(arg.c_str() + 10, nullptr, 10));
    } else if (arg == "--no-vcd") {
      cfg.enable_vcd = false;
    } else if (arg == "--help") {
      std::cout << "Usage: icache_sim [--max-transactions=N] [--max-cycles=N] [--latency=N] [--no-vcd]\n";
      std::exit(0);
    }
  }
  return cfg;
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
  SingleOutstandingMemModel mem(backend, cfg.response_latency);
  TbStats stats;
  PendingTxn pending;

  dut->reset = 1;
  dut->io_commer_req_ready = 0;
  dut->io_commer_resp_valid = 0;
  dut->io_commer_resp_bits_data = 0;
  for (int i = 0; i < 5; ++i) {
    eval_clock(0);
    eval_clock(1);
  }

  dut->reset = 0;
  mem.reset();

  bool pass = false;
  std::string fail_reason;

  try {
    while (!Verilated::gotFinish()) {
      const auto out = mem.comb();
      dut->io_commer_req_ready = out.req_ready;
      dut->io_commer_resp_valid = out.resp_valid;
      dut->io_commer_resp_bits_data = out.resp_data;

      eval_clock(0);
      const bool req_fire = dut->io_commer_req_valid && dut->io_commer_req_ready;
      const bool resp_fire = dut->io_commer_resp_valid && dut->io_commer_resp_ready;
      const uint64_t req_addr = dut->io_commer_req_bits_addr;
      const uint64_t resp_data = dut->io_commer_resp_bits_data;
      eval_clock(1);

      if (out.req_ready == 0) {
        stats.backpressure_cycles += 1;
      }
      if (mem.busy()) {
        stats.busy_cycles += 1;
      }
      if (req_fire) {
        stats.req_handshakes += 1;
        pending.valid = true;
        pending.req_addr = req_addr;
        pending.accepted_cycle = stats.cycles;
        pending.observed_resp_data_valid = false;
        pending.observed_resp_data = 0;
      }
      if (resp_fire) {
        if (!pending.valid) {
          fail_reason = "protocol_error | resp completed without pending txn";
          break;
        }
        stats.resp_handshakes += 1;
        pending.valid = false;
      }

      mem.tick(req_fire, req_addr, resp_fire);
      if (pending.valid && dut->io_commer_resp_valid) {
        pending.observed_resp_data_valid = true;
        pending.observed_resp_data = resp_data;
      }

      stats.cycles += 1;

      if (stats.resp_handshakes >= cfg.max_transactions) {
        pass = true;
        break;
      }
      if (stats.cycles >= cfg.max_cycles) {
        fail_reason = "timeout";
        if (pending.valid) {
          const uint64_t wait_cycles = stats.cycles - pending.accepted_cycle;
          fail_reason += " | pending_req_addr=" + Hex64(pending.req_addr) +
                         " wait_cycles=" + std::to_string(wait_cycles);
          if (pending.observed_resp_data_valid) {
            fail_reason += " observed_pending_resp_data=" + Hex64(pending.observed_resp_data);
          } else {
            fail_reason += " observed_pending_resp_data=<none>";
          }
        } else {
          fail_reason += " | no_pending_txn";
        }
        fail_reason +=
            " | last_signals:req_v=" + std::to_string(static_cast<int>(dut->io_commer_req_valid)) +
            " req_r=" + std::to_string(static_cast<int>(dut->io_commer_req_ready)) +
            " resp_v=" + std::to_string(static_cast<int>(dut->io_commer_resp_valid)) +
            " resp_r=" + std::to_string(static_cast<int>(dut->io_commer_resp_ready)) +
            " resp_data=" + Hex64(resp_data);
        break;
      }
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
  std::cout << "cycles=" << stats.cycles << " req_hs=" << stats.req_handshakes
            << " resp_hs=" << stats.resp_handshakes << " busy_cycles=" << stats.busy_cycles
            << " backpressure_cycles=" << stats.backpressure_cycles << "\n";

  if (!pass) {
    std::cerr << "TB FAIL: " << (fail_reason.empty() ? "unknown" : fail_reason) << "\n";
    return 1;
  }

  std::cout << "TB PASS: reached " << stats.resp_handshakes << " responses\n";
  return 0;
}
