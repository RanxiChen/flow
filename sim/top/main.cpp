#include <verilated.h>
#include <verilated_vcd_c.h>
#include "Vflow_top.h"
#include <stdint.h>
#include <iostream>
uint64_t sim_time = 0;

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);
    Vflow_top *top = new Vflow_top;
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("flow_top.vcd");
    std::cout << "simulate flow_top module" << std::endl;
    while(sim_time < 50){
        if(sim_time <=10 && sim_time >= 3){
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
    std::cout << "finish simulation" << std::endl;
    tfp->close();
    delete top;
    return 0;
}