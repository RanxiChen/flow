#include <iostream>
#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VSRAMLayer.h"
#include <stdint.h>
uint64_t sim_time =0;
uint32_t mem01[] ={
        0x00100793,0x00678813,
        0x0107f8b3,0x40f80433,
        0x10204293,0x0057f313,
        0x0027d393,0x00500433,
        0x00002083,0x00402103,
        0x00002023,0x00002083,
        0x0ff11073,0x00000013,
        0x0ff8d073,0x00000013,
        0x0ff011f3,0x00000013,
        0x30101273,0x00000013,
        0x00100793,0x00678813,
        0x0107f8b3,0x40f80433,
        0x10204293,0x0057f313,
        0x0027d393,0x00500433,
        0x00002083,0x00402103,
        0x00002023,0x00002083,
        0x0ff11073,0x00000013,
        0x0ff8d073,0x00000013,
        0x0ff011f3,0x00000013,
        0x30101273,0x00000013,
        0x8ff21073,0x00000013,
    };
void step(VSRAMLayer * top,VerilatedVcdC* tfp){
    top->clock = 0;
    top->eval();
    tfp->dump(sim_time);
    sim_time++;
    top->clock = 1;
    top->eval();
    tfp->dump(sim_time);
    sim_time++;
    return;
}
void reset(VSRAMLayer * top, VerilatedVcdC* tfp){
    top->reset = 1;
    step(top, tfp);
    top->reset = 0;
}

void sram_read(VSRAMLayer * top, uint64_t addr, uint64_t & rdata, VerilatedVcdC* tfp){
    top->io_in_valid = 1;
    top->io_in_bits_wt_rd = 0; // read
    top->io_in_bits_req_addr = addr;
    top->io_out_ready =0;
    //wait for ready
    while(!top->io_in_ready){
        step(top, tfp);
    }
    step(top, tfp);
    //std::cout << "SRAM read request accepted at addr: 0x" << std::hex << addr << std::endl;
    top->io_in_valid = 0;
    //wait for response
    top->io_out_ready =1;
    while(!top->io_out_valid){
        step(top, tfp);
    }
    rdata = top->io_out_bits_rdata;
    step(top, tfp);
    //std::cout << "SRAM read data: 0x" << std::hex << rdata << std::endl;

    return;
}
void sram_write(VSRAMLayer * top, uint64_t addr, uint64_t wdata, VerilatedVcdC* tfp){
    top->io_in_valid = 1;
    top->io_in_bits_wt_rd = 1; // write
    top->io_in_bits_req_addr = addr;
    top->io_in_bits_wdata = wdata;
    top->io_out_ready =0;
    //wait for ready
    while(!top->io_in_ready){
        step(top, tfp);
    }
    step(top, tfp);
    std::cout << "SRAM write request accepted at addr: 0x" << std::hex << addr << ", data: 0x" << wdata << std::endl;
    top->io_in_valid = 0;
    //wait for response
    top->io_out_ready =1;
    while(!top->io_out_valid){
        step(top, tfp);
    }
    step(top, tfp);
    std::cout << "SRAM write response received." << std::endl;

    return;
}
void sram_read_test(VSRAMLayer * top, VerilatedVcdC* tfp){
    uint64_t rdata;
    reset(top, tfp);
    for(int i=0;i<21;i++){
        uint64_t addr = i*8;
        sram_read(top, addr, rdata, tfp);
        uint64_t expected = uint64_t(mem01[i*2+1]) << 32 | uint64_t(mem01[i*2]);
        //std::cout << "Expected data: 0x" << std::hex << expected << std::endl;
        assert(rdata == expected);
        std::cout << "SRAM read addr: 0x" << std::hex << addr << ", data: 0x" << rdata << std::endl;
    }
    return;
}
void sram_dump(VSRAMLayer * top, VerilatedVcdC* tfp){
    uint64_t rdata;
    reset(top, tfp);
    for(int i=0;i<30;i++){
        uint64_t addr = i*8;
        sram_read(top, addr, rdata, tfp);
        std::cout << "SRAM read addr: 0x" << std::hex << addr << ", data: 0x" << rdata << std::endl;
    }
    return;
}
void sram_write_test_single(VSRAMLayer * top, VerilatedVcdC* tfp){
    uint64_t rdata;
    reset(top, tfp);
    uint64_t test_addr = 128;
    uint64_t test_data = 0xdeadbeefcafebabe;
    sram_write(top, test_addr, test_data, tfp);
    sram_read(top, test_addr, rdata, tfp);
    assert(rdata == test_data);
    std::cout << "SRAM write-read single test passed at addr: 0x" << std::hex << test_addr << ", data: 0x" << rdata << std::endl;
    sram_dump(top, tfp);
    return;
}

int main(int argc, char** argv){
    VSRAMLayer * top = new VSRAMLayer;
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("sramlayer_waveform.vcd");
    uint64_t sim_time =0;

    sram_read_test(top, tfp);
    sram_write_test_single(top, tfp);

    tfp->close();
    delete top;
    delete tfp;
    return 0;
}