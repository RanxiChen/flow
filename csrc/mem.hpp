#ifndef __MEM_HPP__
#define __MEM_HPP__
#include <stdint.h>
class Mem {
    private:
    int __size=-1;//in bytes
    char content[8*1024];//can put 1024 double
    Mem(){
        __size = 8*1024;
    }
    Mem(const Mem&) = delete;
    Mem& operator=(const Mem&) = delete;
    public:
    static Mem& getInstance(){
	    static Mem mem;
	    return mem;
    }
    int size(){
        return __size;
    }
    bool memcpy(uint64_t addr, int len, char* buf){
        if(!isValidAddr(addr) || addr+len > __size){
            return false;
        }
        for(int i=0;i<len;i++){
            content[addr+i] = buf[i];
        }
        return true;
    }
    uint64_t read(uint64_t addr, int len){
        if(!isValidAddr(addr) || addr+len > __size){
            return (uint64_t)(0xffffffff);
        }
        uint64_t data =0;
        for(int i=0;i<len;i++){
            data |= ((uint64_t)(content[addr+i]) & 0xff) << (8*i);
        }
        return data;
    }
    bool write(uint64_t addr, int len, uint64_t data){
        if(!isValidAddr(addr) || addr+len > __size){
            return false;
        }
        for(int i=0;i<len;i++){
            content[addr+i] = (char)((data >> (8*i)) & 0xff);
        }
        return true;
    }
    bool isValidAddr(uint64_t addr){
        return addr < __size;
    }
    int memsize_d() {
        return __size/8;
    }
};
#endif
