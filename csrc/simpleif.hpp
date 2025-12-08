#ifndef __SIMPLEIF_HPP__
#define __SIMPLEIF_HPP__
#include <iostream>
#include <stdint.h>
#include <functional>
#include "mem.hpp"
struct FetchPort {
        uint64_t req_addr;
        std::function<uint64_t()> resp_addr;
        std::function<uint32_t()> resp_data;
        std::function<bool()> can_next;//alias for mem_valid
};

class SimpleMemIF {
    private:
    Mem& mem;
    int if_version;//v1 response in one cycle; v2 response in 2 cycles
    bool dump_log;
    public:
    FetchPort itcm;
    void binding2v1(){
        itcm.resp_addr = [this]() -> uint64_t {
            return this->itcm.req_addr;
        };
        itcm.resp_data = [this]() -> uint32_t {
            return this->mem.read(this->itcm.req_addr,4) & 0xffffffff;
        };
        itcm.can_next = [this]() -> bool {
            return true;//can response in same cycle
        };
    }
    SimpleMemIF(int version=2,bool dumplog=false):mem(Mem::getInstance()){
        if_version = version;
        dump_log = dumplog;
        if(if_version == 1) binding2v1();
    }
    struct {
        uint64_t req_addr;
        bool wt_rd;
        bool mem_valid;
        uint64_t wdata;
        uint8_t wmask;
        uint64_t resp_addr;
        uint64_t rdata;
        bool can_next;
    } dtcm;
    /*
    bool cyclev2(){
        if(dump_log) std::cout << "SimpleMemIF cyclev2 called" << std::endl;
        dtcm.can_next = false;
        itcm.can_next = false;
        //dtcm port
        if(dtcm.mem_valid){
            //actually write or read
            if(dtcm.wt_rd){
                //mem write
                dtcm.resp_addr = dtcm.req_addr;
                char write_length =0;
                switch (dtcm.wmask)
                {
                case 0x1:
                    write_length = 1;
                    break;
                case 0x3:
                    write_length =2;
                    break;
                case 0xf:
                    write_length =4;
                    break;
                case 0xff:
                    write_length =8;
                    break;
                default:
                    write_length =0;
                    std::cout << "Warning: unsupported wmask " << std::hex << (int)dtcm.wmask << std::endl;
                    break;
                }
                bool write_ok = mem.write(dtcm.req_addr,write_length,dtcm.wdata);
                dtcm.can_next = write_ok;
                if(dump_log) std::cout << "Writing memory at addr: 0x" << std::hex << dtcm.req_addr << 
                    " data: 0x" << dtcm.wdata << " wmask: 0x" << std::hex << (int)dtcm.wmask << 
                    " write_ok: " << std::boolalpha << write_ok << std::endl;
                return write_ok;   
            }else {
                //mem read
                dtcm.resp_addr = dtcm.req_addr;
                dtcm.rdata = mem.read(dtcm.req_addr,8);
                dtcm.can_next = true;
                if(dump_log) std::cout << "Reading memory at addr: 0x" << std::hex << dtcm.req_addr << 
                    " data: 0x" << dtcm.rdata << std::endl;
                return true;
            }
        }else {
            dtcm.can_next = false;
        }
        //itcm port
        if(dump_log) std::cout << "itcm_req addr: 0x" << std::hex << itcm.req_addr << std::endl;
        itcm.resp_addr = itcm.req_addr;
        if(dump_log) std::cout << "Reading memory at addr: 0x" << std::hex << itcm.req_addr << std::endl;
        itcm.data = mem.read(itcm.req_addr,4);
        if(dump_log) std::cout << "itcm_resp data: 0x" << std::hex << itcm.data << std::endl;
        itcm.can_next = true;
        if(dump_log) std::cout << "itcm_resp can_next: " << std::boolalpha << itcm.can_next << std::endl;
        return true;
    }
    */
    /**
     * force mem step to next cycle
     * @return true if the cycle is successful, false otherwise.
     */
    bool cycle(){
        if(if_version==2){
            return true;//cyclev2();
        }
        return true;        
    }

};
#endif
