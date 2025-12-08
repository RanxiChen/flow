#include <iostream>
#include <stdint.h>
#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VhelloModule.h"
int main(int argc, char** argv) {
    uint64_t sim_time =0;
    std::cout << "simulate hello module" << std::endl;
    Verilated::commandArgs(argc, argv);
    VhelloModule*top = new VhelloModule;
    Verilated::traceEverOn(true);
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("waveform.vcd");
    while(sim_time < 200){
        if(sim_time <=17 && sim_time >= 6 ){
            top -> reset =1 ;   
        }else {
            top -> reset =0 ;
        }
        top -> clock =1 ;
        top->eval();
        tfp->dump(sim_time);
        sim_time++;
        top -> clock =0 ;
        top->eval();
        tfp->dump(sim_time);
        sim_time++;
    }
    tfp->close();
    delete top;
    return 0;
}