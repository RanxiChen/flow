#include <iostream>
#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VSRAMMem.h"
#include <iostream>
#include <stdint.h>
#include <assert.h>
/**
 * 这个文件用来描述使用sram作为mem的内存应该有的行为
 * 包括读写操作的时序等
 * 核心是sram，但是会有一个只读的端口用来作为imem
 * 另一个读写端口作为dmem
 * 使用状态机来处理一致性问题
 * 同时遇到两个读请求时，可以并行处理
 * 遇到写请求时，优先处理写请求，读请求等待
 */
struct imem_port_t {
    uint64_t req_addr;
    uint64_t resp_addr;
    uint64_t data;
    bool can_next;
};
struct dmem_port_t {
    uint64_t req_addr;
    uint64_t resp_addr;
    bool mem_valid;
    bool wt_rd; // 1 for write, 0 for read
    uint64_t wdata;
    uint8_t wmask;
    uint64_t rdata;
    bool can_next;
};
// mem01.hex content
//cpu will guarantee aligned access, I mean addr % 4 ==0 for word access
// and addr %8 ==0 for double access
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

void step(VSRAMMem * top){
    //after posedge and port has been set
    top -> clock =0 ;
    top -> eval();
    top -> clock =1 ;
    top -> eval();
    // return and next sentences can check top
}
void reset(VSRAMMem * top){
    top -> reset =1 ;
    step(top);
    top -> reset =0;
}
void fetch_read_s(VSRAMMem * top, uint64_t addr, uint32_t & data){
    top-> io_imem_port_req_addr = addr ;
    top -> io_dmem_port_mem_valid =0 ;
    do{
        step(top);
    }while(!top-> io_imem_port_can_next );
    data = top-> io_imem_port_data ;
    assert(top-> io_imem_port_resp_addr == addr );
    return;
}
void dual_read_s(VSRAMMem * top,uint64_t imem_addr, uint32_t & imem_data,
               uint64_t dmem_addr, uint64_t & dmem_data){
    //independent read
    //return data in same cycle
    top-> io_imem_port_req_addr = imem_addr ;
    top-> io_dmem_port_req_addr = dmem_addr ;
    top -> io_dmem_port_mem_valid =1;
    top -> io_dmem_port_wt_rd =0 ; //read
    do {
        step(top);
    }while(!top-> io_imem_port_can_next);
    imem_data = top-> io_imem_port_data ;
    dmem_data = top-> io_dmem_port_rdata ;
    assert(top-> io_imem_port_resp_addr == imem_addr );
    assert(top-> io_dmem_port_resp_addr == dmem_addr );
    assert(top-> io_dmem_port_can_next ==1 );
    return;
}
void fetch_write_s(VSRAMMem * top, uint64_t imem_addr,uint32_t & imem_data,
                    uint64_t dmem_addr, uint64_t wdata, uint8_t wmask){
    //write dmem first, then read imem
    top-> io_imem_port_req_addr = imem_addr ;
    top-> io_dmem_port_req_addr = dmem_addr ;
    top -> io_dmem_port_mem_valid =1;
    top -> io_dmem_port_wt_rd =0 ; //read
    top -> io_dmem_port_wdata = wdata ;
    top -> io_dmem_port_wmask = wmask ;
    do {
        step(top);
    }while(!top-> io_dmem_port_can_next || !top-> io_imem_port_can_next);
    imem_data = top-> io_imem_port_data ;
    assert(top-> io_imem_port_resp_addr == imem_addr );
    assert(top-> io_dmem_port_resp_addr == dmem_addr );
    return;
}

void assert_mem01_fetch_read(VSRAMMem * top){
    uint32_t data;
    reset(top);
    for(int i=0;i<42;i++){
        fetch_read_s(top,i*4,data);// 4-bytes aligned access
        assert(data == mem01[i]);
        //std::cout << "Fetch read addr: 0x" << std::hex << i*4 << ", data: 0x" << data << std::endl;
    }
    return;
}
void assert_mem01_mem_read(VSRAMMem * top){
    uint32_t imem_data;
    uint64_t dmem_data;
    reset(top);
    for(int i=0;i<42;i+=2){
        uint64_t addr = i*4;
        dual_read_s(top,addr,imem_data,addr,dmem_data);// 8-bytes aligned access, 64-bit data
        //assert(imem_data == mem01[i]);
        uint64_t buf;
        buf = uint64_t(mem01[i+1]) << 32 | uint64_t(mem01[i]);
        //assert(dmem_data == buf);
        std::cout << "Dual read imem addr: 0x" << std::hex << i << ", imem data: 0x" << imem_data;
        std::cout << "; dmem addr: 0x" << std::hex << i << ", dmem data should be: 0x" << buf << std::endl;
    }
    return;
}
void assert_mem01_fetch_write_dependent(VSRAMMem * top){
}
void assert_mem01_fetch_write(VSRAMMem * top){
    //write to dmem addr 0, then read imem addr 4
    uint32_t imem_data;
    uint64_t dmem_wdata ;
    reset(top);
    //clear by write double, read mem0
    for( int index=0;index<42;index+=2){
        uint64_t addr = index*4;
        dmem_wdata =0;
        fetch_write_s(top,0,imem_data,addr,dmem_wdata,0xFF);
        assert(imem_data == mem01[0]);
        fetch_read_s(top,addr,imem_data);
        std::cout << "After clear, read imem addr: 0x" << std::hex << addr << ", data: 0x" << imem_data<<";should be 0" << std::endl;
        //assert(imem_data == 0);
    }
    //read after clear


}
    
        

int main(int argc, char** argv){
    VSRAMMem * top = new VSRAMMem;
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("sram_waveform.vcd");
    uint64_t sim_time =0;
    assert_mem01_fetch_read(top);
    //assert_mem01_mem_read(top);
    assert_mem01_fetch_write(top);

    tfp->close();
    delete top;
    delete tfp;
    return 0;

}