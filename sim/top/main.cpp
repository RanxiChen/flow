#include <verilated.h>
#include <verilated_vcd_c.h>
#include "Vflow_top.h"
#include <stdint.h>
#include <iostream>
uint64_t sim_time = 0;
#ifndef MAX_SIM_TIME
#define MAX_SIM_TIME 1000
#endif

void step(Vflow_top* top, VerilatedVcdC* tfp){
    top ->clock = 0;
    top ->eval();
    tfp->dump(sim_time++);
    top ->clock = 1;
    top ->eval();
    tfp->dump(sim_time++);
    return;
}
void reset(Vflow_top* top, VerilatedVcdC* tfp){
    top -> reset = 1;
   step(top, tfp);
    top -> reset = 0;
    return;
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);
    Vflow_top *top = new Vflow_top;
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("flow_top.vcd");
    std::cout << "simulate flow_top module" << std::endl;
    reset(top, tfp);
    while(sim_time < MAX_SIM_TIME){
        step(top, tfp);
    }
    std::cout << "finish simulation" << std::endl;
    tfp->close();
    delete top;
    return 0;
}