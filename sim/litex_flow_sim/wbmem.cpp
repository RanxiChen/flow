//
// Created by chen on 2026/1/24.
//

#include "wbmem.h"

#include <cstring>

uint8_t wbmem::read(uint64_t addr) {
    if (addr >= max_addr) {
        return 0xff;
    }else {
        return mem[addr];
    }
}

bool wbmem::write(uint64_t addr, uint8_t val) {
    // a little bundle check
    if (addr <= max_addr) {
        mem[addr] = val;
        return true;
    } else {
        std::cout << "write memory error,addr out of range" << std::endl;
        return false;
    }
}

void wbmem::loadmem(uint64_t start_addr, char *content, int len) {
    memcpy(this->mem+start_addr, content, len);
}


