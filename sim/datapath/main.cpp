#include "simpleif.hpp"
#include <iostream>
#include <verilated.h>
#include <verilated_vcd_c.h>
#include "Vcore_in_order.h"
#define SIM_CYCLE_MAX 10000000
uint64_t sim_time = 0;
int main(int argc, char** argv) {
    bool dump_log = false;
    Vcore_in_order* top = new Vcore_in_order;
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("waveform.vcd");
    //add mem
    auto & mem = Mem::getInstance();
    uint32_t inst1[10] ={
        0x00100793, //addi x15, x0, 1
        0x00678813, //add x16, x15, x6
        0x0107f8b3, //add x17, x15, x16
        0x40f80433, //add x8, x15, x15
        0x10204293, //addi x5, x0, 0x102
        0x0057f313, //andi x6,x15,5
        0x0027d393, //slli x7,x15,2
        0x00500433, //add x8,x0,x5
        0x0062c4b3, //add x9,x5,x6
        0x0084e533 //add x10,x9,x8
    };
    mem.memcpy(0,4*10,(char*)inst1);

    //mem interface
    auto mem_if = SimpleMemIF(1,dump_log);
    while(sim_time < 30){
        //reset
        if(sim_time>=1 && sim_time <=15){
            top -> reset =1 ;   
        }else {
            top -> reset =0 ;
        }
        //posedge
        //itcm interface
        mem_if.itcm.req_addr = top->io_itcm_req_addr;
        top->io_itcm_data = mem_if.itcm.resp_data();
        top->io_itcm_resp_addr = mem_if.itcm.resp_addr();
        top->io_itcm_can_next = mem_if.itcm.can_next();
        //dtcm interface
        mem_if.dmem.req_addr = top->io_dtcm_req_addr;
        mem_if.dmem.wt_rd = top->io_dtcm_wt_rd;
        mem_if.dmem.mem_valid = top->io_dtcm_mem_valid;
        mem_if.dmem.wdata = top->io_dtcm_wdata;
        mem_if.dmem.wmask = top->io_dtcm_wmask;
        top->io_dtcm_rdata = mem_if.dmem.rdata();
        top->io_dtcm_resp_addr = mem_if.dmem.resp_addr();
        top->io_dtcm_can_next = mem_if.dmem.can_next();
        top->clock = 1;
        top->eval();
        mem_if.cycle();
        tfp->dump(sim_time);
        if(dump_log) std::cout << "Time: " << sim_time << std::endl;
        sim_time++;
        //negedge
        top -> clock = 0;
        top->eval();
        tfp->dump(sim_time);
        sim_time++;
    }

    tfp->close();
    delete top;
    delete tfp;
    return 0;
}