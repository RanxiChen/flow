//
// Created by chen on 2026/1/24.
//

#ifndef LITEX_FLOW_SIM_WBMEM_H
#define LITEX_FLOW_SIM_WBMEM_H
#include <cstdint>
#include <iostream>
/**
 * @brief 这里将会是一个纯粹的内存模型,字节寻址
 * 对外提供c++的读写函数，由其他模块实现对该内存模型的访问
 */


class wbmem {
public:
    uint8_t read(uint64_t addr);
    bool write(uint64_t addr,uint8_t data);
    void loadmem(uint64_t start_addr,char*content,int len);
    wbmem() {
        /**
         * now we assume the memory is fully mapped, no hole
         */
        std::cout << "build virtual memory" << std::endl;
    }
private:
    uint64_t max_addr = 1024*1024*16 -1; //16MB memory
    uint8_t mem[1024*1024*16]; //16MB memory
};

#endif //LITEX_FLOW_SIM_WBMEM_H