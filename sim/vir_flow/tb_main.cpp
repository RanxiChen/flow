#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include <verilated.h>
#include <verilated_vcd_c.h>

#include "VVirFlow.h"
#include "flow_vmem/virtual_memory_model.h"

struct TbConfig {
  std::string hex_path = std::string(VIR_FLOW_SOURCE_DIR) + "/example/start01.hex";
  uint64_t reset_addr = 0;
  uint64_t max_cycles = 2000;
  uint32_t response_latency = 12;
  uint32_t inter_beat_latency = 2;
  bool enable_vcd = true;
};

struct TbStats {
  uint64_t cycles = 0;
  uint64_t mem_req_hs = 0;
  uint64_t mem_resp_hs = 0;
};

static std::string Trim(const std::string &s) {
  size_t left = 0;
  while (left < s.size() && std::isspace(static_cast<unsigned char>(s[left])) != 0) {
    left += 1;
  }
  size_t right = s.size();
  while (right > left && std::isspace(static_cast<unsigned char>(s[right - 1])) != 0) {
    right -= 1;
  }
  return s.substr(left, right - left);
}

static std::string Hex64(uint64_t v) {
  std::ostringstream oss;
  oss << "0x" << std::hex << std::setw(16) << std::setfill('0') << v;
  return oss.str();
}

static TbConfig ParseArgs(int argc, char **argv) {
  TbConfig cfg;
  for (int i = 1; i < argc; ++i) {
    std::string arg(argv[i]);
    if (arg.rfind("--hex=", 0) == 0) {
      cfg.hex_path = arg.substr(6);
    } else if (arg.rfind("--reset-addr=", 0) == 0) {
      cfg.reset_addr = std::strtoull(arg.c_str() + 13, nullptr, 0);
    } else if (arg.rfind("--max-cycles=", 0) == 0) {
      cfg.max_cycles = std::strtoull(arg.c_str() + 13, nullptr, 10);
    } else if (arg.rfind("--latency=", 0) == 0) {
      cfg.response_latency = static_cast<uint32_t>(std::strtoul(arg.c_str() + 10, nullptr, 10));
    } else if (arg.rfind("--beat-gap=", 0) == 0) {
      cfg.inter_beat_latency = static_cast<uint32_t>(std::strtoul(arg.c_str() + 11, nullptr, 10));
    } else if (arg == "--no-vcd") {
      cfg.enable_vcd = false;
    } else if (arg == "--help") {
      std::cout
          << "Usage: vir_flow_sim [--hex=path] [--reset-addr=N] [--max-cycles=N] [--latency=N] "
             "[--beat-gap=N] [--no-vcd]\n";
      std::exit(0);
    } else {
      throw std::runtime_error("Unknown arg: " + arg);
    }
  }
  if (cfg.max_cycles == 0) {
    throw std::runtime_error("--max-cycles must be > 0");
  }
  return cfg;
}

static std::vector<uint32_t> LoadHexWords32(const std::string &path) {
  std::ifstream ifs(path);
  if (!ifs.is_open()) {
    throw std::runtime_error("Failed to open hex file: " + path);
  }

  std::vector<uint32_t> words;
  std::string line;
  uint64_t line_no = 0;
  while (std::getline(ifs, line)) {
    line_no += 1;
    std::string t = Trim(line);
    if (t.empty() || t[0] == '#') {
      continue;
    }
    if (t.size() >= 2 && t[0] == '/' && t[1] == '/') {
      continue;
    }
    if (t.rfind("0x", 0) == 0 || t.rfind("0X", 0) == 0) {
      t = t.substr(2);
    }
    if (t.empty() || t.size() > 8) {
      throw std::runtime_error("Invalid hex word at line " + std::to_string(line_no) + " in " +
                               path);
    }
    for (char c : t) {
      if (std::isxdigit(static_cast<unsigned char>(c)) == 0) {
        throw std::runtime_error("Non-hex char at line " + std::to_string(line_no) + " in " + path);
      }
    }
    const uint32_t word = static_cast<uint32_t>(std::stoul(t, nullptr, 16));
    words.push_back(word);
  }

  if (words.empty()) {
    throw std::runtime_error("Hex file has no data words: " + path);
  }
  return words;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const TbConfig cfg = ParseArgs(argc, argv);

  auto *dut = new VVirFlow;
  VerilatedVcdC *trace = nullptr;
  if (cfg.enable_vcd) {
    Verilated::traceEverOn(true);
    trace = new VerilatedVcdC;
    dut->trace(trace, 99);
    trace->open("vir_flow_tb.vcd");
  }

  // Hex is interpreted as one 32-bit instruction word per line.
  // The first line is mapped to address 0x0, second to 0x4, and so on.
  const std::vector<uint32_t> words = LoadHexWords32(cfg.hex_path);
  flow_vmem::ConfigurableMemoryBackend backend;
  flow_vmem::MemoryLayoutConfig layout_cfg;
  layout_cfg.mode = flow_vmem::MemoryLayoutMode::kSegmentMap;
  backend.set_layout_config(layout_cfg);

  const uint64_t word_count = std::max<uint64_t>(2, static_cast<uint64_t>(words.size()) + 1);
  auto itcm = std::make_shared<flow_vmem::Word32SegmentStorage>(0, word_count, 0U);
  for (uint64_t i = 0; i < words.size(); ++i) {
    itcm->write32_by_index(i, words[static_cast<size_t>(i)]);
  }
  backend.segment_backend().add_segment(itcm);

  flow_vmem::VirtualMemoryConfig mem_cfg;
  mem_cfg.response_latency = cfg.response_latency;
  mem_cfg.inter_beat_latency = cfg.inter_beat_latency;
  flow_vmem::SingleOutstandingVirtualMemoryModel mem(backend, mem_cfg);

  vluint64_t sim_time = 0;
  auto eval_clock = [&](int clk) {
    dut->clock = clk;
    dut->eval();
    if (trace != nullptr) {
      trace->dump(sim_time);
    }
    sim_time += 1;
  };

  TbStats stats;
  dut->reset = 1;
  dut->io_reset_addr = cfg.reset_addr;
  dut->io_mem_req_ready = 0;
  dut->io_mem_resp_valid = 0;
  dut->io_mem_resp_bits_data = 0;
  for (int i = 0; i < 5; ++i) {
    eval_clock(0);
    eval_clock(1);
  }

  dut->reset = 0;
  mem.reset();

  bool pass = false;
  std::string fail_reason;
  try {
    while (!Verilated::gotFinish() && stats.cycles < cfg.max_cycles) {
      const auto mem_out = mem.comb();
      dut->io_reset_addr = cfg.reset_addr;
      dut->io_mem_req_ready = mem_out.req_ready;
      dut->io_mem_resp_valid = mem_out.resp_valid;
      dut->io_mem_resp_bits_data = mem_out.resp_data;

      eval_clock(0);

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

      eval_clock(1);
      stats.cycles += 1;
    }

    // Stage-1 success criteria: run for fixed cycles without protocol exceptions.
    pass = true;
  } catch (const std::exception &e) {
    fail_reason = e.what();
  }

  if (trace != nullptr) {
    trace->close();
    delete trace;
  }
  dut->final();
  delete dut;

  std::cout << "[tb] hex=" << cfg.hex_path << "\n";
  std::cout << "[tb] reset_addr=" << Hex64(cfg.reset_addr) << "\n";
  std::cout << "[tb] cycles=" << stats.cycles << ", mem_req_hs=" << stats.mem_req_hs
            << ", mem_resp_hs=" << stats.mem_resp_hs << "\n";

  if (!pass) {
    std::cerr << "[tb] FAIL: " << fail_reason << "\n";
    return 1;
  }

  std::cout << "[tb] PASS: finished fixed-cycle run without protocol exception\n";
  return 0;
}
