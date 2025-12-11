#ifndef __FLOW_SIM_HPP__
#define __FLOW_SIM_HPP__
#include <iostream>
#include <stdint.h>
#include <map>
#include "util.hpp"
#define PRINTER 0xf0
struct CSRBase {
    virtual uint16_t addr()=0;
    virtual void set(uint64_t val)=0;
    virtual uint64_t get()=0;
    virtual ~CSRBase() = default;
};

template<uint16_t csr_addr>
class CSR: public CSRBase {
private:
    uint64_t value=0;
    CSR(){
        std::cout << "CSR 0x" << std::hex << csr_addr << " created" << std::endl;
    }
public:
    static CSR& getInstance(){
        static CSR csr;
        return csr;
    }
    void set(uint64_t val) override{
        value = val;
    }
    uint64_t get() override{
        return value;
    }
    CSR(const CSR&) = delete;
    CSR& operator=(const CSR&) = delete;
    uint16_t addr() override{
        return csr_addr;
    }
};
template<uint16_t csr_addr>
void add_csr_instance(std::map<uint16_t, CSRBase*>& csr_map){
    csr_map[csr_addr] = &CSR<csr_addr>::getInstance();
} 
void add_csr_instance(std::map<uint16_t, CSRBase*>& csr_map, CSRBase& csr){
    uint16_t addr = csr.addr();
    csr_map[addr] = &csr;
}

class State {
    public:
    uint64_t rf[32];    
    std::map<uint16_t, CSRBase*> csr_map;
    State(){
        //initialize csr map
        add_csr_instance<PRINTER>(csr_map);
    }
};
struct IMemPort {
    
};
struct DMemPort {

};
class FlowSim {
    private:
    State state;
    uint32_t instbuf=0;
    Zicsr cmd;
    uint8_t rd;
    uint8_t rs1;
    uint8_t imm_inst;
    public:
    IMemPort imem;
    DMemPort dmem;
    FlowSim(){
        //init state
        for(int i=0;i<32;i++){
            state.rf[i] = 0;
        }
    }
    void Zicsr_run(){
        uint64_t imm = this -> instbuf >> 20;
        switch(cmd){
            case Zicsr::CSRRW:
                if(rd != 0){
                uint64_t csr_old_val =  state.csr_map[imm]->get();
                uint64_t rs1_val = state.rf[rs1];
                state.rf[rd] = csr_old_val;
                state.csr_map[imm]->set(rs1_val);
                }
                break;
            case Zicsr::CSRRS:
                uint64_t csr_old_val =  state.csr_map[imm]->get();
                uint64_t rs1_val = state.rf[rs1];
                state.rf[rd] = csr_old_val;
                if(rs1_val!=0)state.csr_map[imm]->set(csr_old_val | rs1_val);
                break;
            case Zicsr::CSRRC:
                uint64_t csr_old_val =  state.csr_map[imm]->get();
                uint64_t rs1_val = state.rf[rs1];
                state.rf[rd] = csr_old_val;
                if(rs1_val!=0)state.csr_map[imm]->set(csr_old_val & (~rs1_val));
                break;
            case Zicsr::CSRRWI:
                if(rd != 0){
                uint64_t csr_old_val =  state.csr_map[imm]->get();
                uint64_t zimm = rs1;
                state.rf[rd] = csr_old_val;
                state.csr_map[imm]->set(zimm);
                }
                break;
            case Zicsr::CSRRSI:
                uint64_t csr_old_val =  state.csr_map[imm]->get();
                uint64_t zimm = rs1;
                state.rf[rd] = csr_old_val;
                if(zimm!=0)state.csr_map[imm]->set(csr_old_val | zimm);
                break;
            case Zicsr::CSRRCI:
                uint64_t csr_old_val =  state.csr_map[imm]->get(); 
                uint64_t zimm = rs1;
                state.rf[rd] = csr_old_val;
                if(zimm!=0)state.csr_map[imm]->set(csr_old_val & (~zimm));
                break;
            default:
                break;
        } 
    }
    void cycle(){
        //fetch instruction,such as things fllowed
        //this -> instbuf = this-> imem.read(this->state.pc);
        //decode instruction
        //execute instruction
        //write back result
        //update pc
    }
};
#endif
