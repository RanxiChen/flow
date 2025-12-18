#include <iostream>
#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VSRAMMemory.h"
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
void step(VSRAMMemory * top,VerilatedVcdC* tfp){
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
void reset(VSRAMMemory * top, VerilatedVcdC* tfp){
    top->reset = 1;
    step(top, tfp);
    top->reset = 0;
}

void sram_fetch(VSRAMMemory * top, uint64_t addr,uint32_t & data,VerilatedVcdC* tfp){
    //just read 4 bytes
    top->io_imem_port_req_addr = addr;
    top->io_dmem_port_mem_valid = 0;
    while(!top->io_imem_port_can_next){
        step(top, tfp);
    }
    data = top->io_imem_port_data;
    assert(top->io_imem_port_resp_addr == addr);
    step(top, tfp);
    return;
}
void sram_fetch_test(VSRAMMemory * top, VerilatedVcdC* tfp){
    int index =0;
    reset(top, tfp);
    for(index =0; index <42;index++){
        uint32_t data;
        sram_fetch(top, index*4, data, tfp);
        assert(data == mem01[index]);
        //std::cout<<"fetch addr: 0x"<<std::hex<<index*4<<" data: 0x"<<data<<std::dec<<std::endl;
    }
}
void sram_mem_read(VSRAMMemory * top, VerilatedVcdC* tfp, uint64_t addr,uint64_t & data){
    //just read 8 bytes
    top->io_dmem_port_req_addr = addr;
    top->io_dmem_port_mem_valid = 1;
    top->io_dmem_port_wt_rd = 0;
    top->io_imem_port_req_addr = 0;
    while(!top->io_dmem_port_can_next){
        step(top, tfp);
    }
    data = top->io_dmem_port_rdata;
    assert(top->io_dmem_port_resp_addr == addr);
    step(top, tfp);
    return;
}
void sram_mem_read_test(VSRAMMemory * top, VerilatedVcdC* tfp){
    reset(top, tfp);
    for(int index =0; index < 21;index++){
        uint64_t data;
        sram_mem_read(top, tfp, index*8, data);
        uint64_t expect = ((uint64_t)mem01[index*2+1]<<32) | mem01[index*2];
        assert(data == expect);
        //std::cout<<"mem read addr: 0x"<<std::hex<<index*8<<" data: 0x"<<data<<std::dec<<std::endl;
    }
}
void sram_mem_dump(VSRAMMemory * top, VerilatedVcdC* tfp){
    reset(top, tfp);
    for(int index =0; index < 31;index++){
        uint64_t data;
        sram_mem_read(top, tfp, index*8, data);
        //uint64_t expect = ((uint64_t)mem01[index*2+1]<<32) | mem01[index*2];
        //assert(data == expect);
        std::cout<<"mem read addr: 0x"<<std::hex<<index*8<<" data: 0x"<<data<<std::dec<<std::endl;
    }
}
void sram_mem_write(VSRAMMemory * top, VerilatedVcdC* tfp, uint64_t addr,uint64_t data, uint8_t wmask){
    //just read 8 bytes
    top->io_dmem_port_req_addr = addr;
    top->io_dmem_port_mem_valid = 1;
    top ->io_dmem_port_wmask = wmask;
    top -> io_dmem_port_wdata = data;
    top->io_dmem_port_wt_rd = 1;
    top->io_imem_port_req_addr = 0;
    while(!top->io_dmem_port_can_next){
        step(top, tfp);
    }
    assert(top->io_dmem_port_resp_addr == addr);
    step(top, tfp);
    return;
}
void sram_mem_write_test(VSRAMMemory * top, VerilatedVcdC* tfp){
    reset(top, tfp);
    sram_mem_write(top, tfp, 0, 0x1122334455667788, 0xFF);
    uint64_t data;
    sram_mem_read(top, tfp, 0, data);
    std::cout<<"After write full word, read data: 0x"<<std::hex<<data<<std::dec<<std::endl;
    assert(data == 0x1122334455667788);
    sram_mem_write(top, tfp, 0x8, 0x1111111188888888, 0x0F);
    uint32_t data32;
    sram_fetch(top, 0x8, (uint32_t&)data32, tfp);
    std::cout<<"After write half word, read data: 0x"<<std::hex<<data32<<std::dec<<std::endl;
    assert(data32 == 0x88888888);
    return;
}

int main(int argc, char** argv){
    VSRAMMemory * top = new VSRAMMemory;
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("srammemory_waveform.vcd");
    uint64_t sim_time =0;

    sram_fetch_test(top, tfp);
    sram_mem_read_test(top, tfp);
    sram_mem_write_test(top, tfp);
    sram_mem_dump(top, tfp);

    tfp->close();
    delete top;
    delete tfp;
    return 0;
}