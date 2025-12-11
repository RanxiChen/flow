#ifndef __UTIL_HPP__
#define __UTIL_HPP__
#include <iostream>
#include <stdint.h>
enum class Zicsr {
    CSRRW,
    CSRRS,
    CSRRC,
    CSRRWI,
    CSRRSI,
    CSRRCI
};
#endif