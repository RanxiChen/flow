#include <verilated.h>
#include <verilated_vcd_c.h>
#include "Vtinymem.h"
#include <iostream>
#define SIM_CYCLE_MAX 200
uint64_t sim_time = 0;
int main(int argc, char** argv) {
    Vtinymem* top = new Vtinymem;
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("waveform_tinymem.vcd");
    while(sim_time < SIM_CYCLE_MAX){
        //reset
        if(sim_time==1){
            top -> reset =1 ;
        }else {
            top -> reset =0 ;
        }
        //posedge
        top->clock = 1;
        top->eval();
        tfp->dump(sim_time);
        top->io_addr = sim_time/2 % 128;
        std::cout << "Time: " << sim_time << " ";
        std::cout << "addr: " << std::hex << (int)top->io_addr << " ";
        std::cout << "data: 0x" << std::hex << (int)top->io_data << std::endl;
        sim_time++;
        //negedge
        top->clock = 0;
        top->eval();
        tfp->dump(sim_time);
        sim_time++;
    }
}
