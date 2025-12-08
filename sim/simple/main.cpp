#include "mem.hpp"
#include "simpleif.hpp"
#include <assert.h>
int main(void){
    bool dmp_log = true;
    auto& mem = Mem::getInstance();
    uint32_t inst1[7] ={
        0x00100793, //addi x15, x0, 1
        0x10204293, //addi x5, x0, 0x102
        0x0057f313, //andi x6,x15,5
        0x0027d393, //slli x7,x15,2
        0x00500433, //add x8,x0,x5
        0x0062c4b3, //add x9,x5,x6
        0x0084e533 //add x10,x9,x8
    };
    mem.memcpy(0,7*4,(char*)inst1);
    SimpleMemIF memif(1);
    //response in 1 cycle
    //itcm read test
    printf("itcm read test\n");
    printf("read mem 0x0\n");
    memif.itcm.req_addr = 0;
    if(dmp_log){
    std::cout << "itcm req_addr: " << std::hex << memif.itcm.req_addr << std::endl;
    std::cout << "itcm resp_addr: " << std::hex << memif.itcm.resp_addr() << std::endl;
    std::cout << "itcm resp_data: 0x" << std::hex << memif.itcm.resp_data() << std::endl;
    std::cout << "itcm can_next: " << std::hex << memif.itcm.can_next() << std::endl;
    }
    assert(memif.itcm.resp_addr() == 0);
    assert(memif.itcm.resp_data() == 0x00100793);
    assert(memif.itcm.can_next());
    printf("read mem 0x4\n");
    memif.itcm.req_addr = 4;
    if(dmp_log){
    std::cout << "itcm req_addr: " << std::hex << memif.itcm.req_addr << std::endl;
    std::cout << "itcm resp_addr: " << std::hex << memif.itcm.resp_addr() << std::endl;
    std::cout << "itcm resp_data: 0x" << std::hex << memif.itcm.resp_data() << std::endl;
    std::cout << "itcm can_next: " << std::hex << memif.itcm.can_next() << std::endl;
    }
    assert(memif.itcm.resp_addr() == 4);
    assert(memif.itcm.resp_data() == inst1[1]);
    assert(memif.itcm.can_next());
    return 0;
}