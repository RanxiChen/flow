#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VSRAMMem.h"
#include <iostream>
#include <stdint.h>
#include <assert.h>
int main(int argc, char** argv){
    VSRAMMem * top = new VSRAMMem;
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("sram_waveform.vcd");
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
    //reset
    top -> reset =1 ;
    top-> clock =0 ;
    top -> eval();
    top-> clock =1 ;
    top -> eval();
    top -> reset =0 ;
    //just read,disable mem port
    top -> io_dmem_port_mem_valid =0 ;
    for(int index =0;index < 42;index ++){
        //imem read
        top-> io_imem_port_req_addr = index*4 ;
        top -> clock =0 ;
        top -> eval();
        top -> clock =1 ;
        top -> eval();
        std::cout << "line: " <<std::dec <<index << ", ";
        std::cout << "resp_addr: 0x" << std::hex << top-> io_imem_port_resp_addr << ", data: 0x" << top-> io_imem_port_data<< ";";
        assert(top-> io_imem_port_resp_addr == index*4);
        assert(top-> io_imem_port_data == mem01[index]);
        assert(top-> io_imem_port_can_next ==1 );
        std::cout << "mem01: 0x" << std::hex << mem01[index] << std::endl;
    }
    //write double
    top-> io_dmem_port_mem_valid =1 ;
    top-> io_dmem_port_wt_rd =1 ; //write
    top-> io_dmem_port_wmask =0xFF ; //full mask
    //write mem[0],mem[1] to 0x0;
    top-> io_dmem_port_req_addr =0 ;
    top-> io_dmem_port_wdata =0x0;
    top-> clock =0 ;
    top-> eval();
    top-> clock =1 ;
    top-> eval();
    //check
    top -> io_dmem_port_mem_valid =0 ;
    top-> io_imem_port_req_addr =0 ;
    top -> clock =0 ;
    top -> eval();
    top -> clock =1 ;
    top -> eval();
    //read mem 0
    assert(top-> io_dmem_port_can_next ==1 );
    assert(top-> io_dmem_port_resp_addr ==0 );
    assert(top-> io_dmem_port_rdata ==0x0 );
    top -> io_dmem_port_mem_valid =0 ;
    top-> io_imem_port_req_addr =4 ;
    top -> clock =0 ;
    top -> eval();
    top -> clock =1 ;
    top -> eval();
    assert(top-> io_imem_port_resp_addr ==4 );
    assert(top-> io_dmem_port_rdata ==0x0 );
    assert(top-> io_dmem_port_can_next ==1 );
    //write mem[0] to 0x11;
    top-> io_dmem_port_mem_valid =1 ;
    top-> io_dmem_port_wt_rd =1 ; //write
    top-> io_dmem_port_wmask =0xF ; //full mask

    top-> io_dmem_port_req_addr =0 ;
    top-> io_dmem_port_wdata =0x11;
    top-> clock =0 ;
    top-> eval();
    top-> clock =1 ;
    top-> eval();
    //check
    top -> io_dmem_port_mem_valid =0 ;
    top-> io_imem_port_req_addr =0 ;
    top -> clock =0 ;
    top -> eval();
    top -> clock =1 ;
    top -> eval();
    //read mem 0
    assert(top-> io_dmem_port_can_next ==1 );
    assert(top-> io_dmem_port_resp_addr ==0 );
    assert(top-> io_dmem_port_rdata ==0x11 );
    top -> io_dmem_port_mem_valid =0 ;
    top-> io_imem_port_req_addr =4 ;
    top -> clock =0 ;
    top -> eval();
    top -> clock =1 ;
    top -> eval();
    assert(top-> io_imem_port_resp_addr ==4 );
    assert(top-> io_dmem_port_rdata ==0x0 );
    assert(top-> io_dmem_port_can_next ==1 );

    tfp->close();
    delete top;
    delete tfp;
    return 0;

}