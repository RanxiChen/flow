#include <iostream>
#include <verilated.h>
#include <verilated_vcd_c.h>
#include <stdint.h>
using namespace std;
uint64_t sim_time =0; //current simulation time
#include "VLitexFlowTop.h"
#include "wbmem.h"
#include "wbif.h"
#include "misc.h"

uint64_t initial_data[16] = {
        0xffb0011300a00093ULL,
        0x0ff0021300300193ULL,
        0x40308333002082b3ULL,
        0x0040f433000003b3ULL,
        0x0040c5330030e4b3ULL,
        0x00113633001125b3ULL,
        0x00f2771301408693ULL,
        0xfff1c8130101e793ULL,
        0x00209913fff12893ULL,
        0x00425a13003099b3ULL,
        0x40115b1300325ab3ULL,
        0x00100c1340315bb3ULL,
        0x00100c9301fc1c13ULL,
        0x40308dbb019c0d3bULL,
        0x00119e9bfffc0e1bULL,
        0x40115f9b00125f1bULL
};
      void step(VLitexFlowTop *top,VerilatedVcdC* tfp,wbif*mem) {
        // before posedge rising
        top->clock = 1;
        //get inputs
        top -> io_bus_ack = mem ->io.ack();
        top -> io_bus_dat_r = mem ->io.data_r();
        top -> io_bus_err = mem ->io.err();

        //model update
        mem -> io.adr = top ->io_bus_adr;
        mem -> io.data_w = top ->io_bus_dat_w;
        mem -> io.sel = top ->io_bus_sel;
        mem -> io.cyc = top ->io_bus_cyc;
        mem -> io.stb = top ->io_bus_stb;
        mem -> io.we = top ->io_bus_we;
        mem -> io.bte = top ->io_bus_bte;
        mem -> io.cti = top ->io_bus_cti;
        std::cout << Color::Green<< "=========== Posedge at " << sim_time << "===========" << Color::Reset <<std::endl;
        top-> eval();
        tfp-> dump(sim_time);
        sim_time++;
        //mem ->tick();
        mem ->step();
        top ->clock =0;
        top -> eval();
        tfp -> dump(sim_time);
        sim_time++;

}

int main(int argc, char **argv) {
        Verilated::commandArgs(argc, argv);
        Verilated::traceEverOn(true);
        VLitexFlowTop *top = new VLitexFlowTop;
        VerilatedVcdC * tfp = new VerilatedVcdC;
        top->trace(tfp, 99);
        tfp->open("litex_flow_top.vcd");
        //initial sim model
        wbif *mem = new wbif(2);
        mem->initial_mem_hex(0,initial_data,16);
        std::cout << "simulate flow_top module" << std::endl;
        top -> io_reset_addr = 0;
        while (sim_time < 100) {
                if (sim_time < 10) {
                        top -> reset = 1;
                }else {
                        top -> reset = 0;
                }
                step(top,tfp,mem);
        }
        std::cout << "finish simulation" << std::endl;
        tfp->close();
        delete top;
        return 0;
}
