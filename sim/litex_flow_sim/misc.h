//
// Created by chen on 2026/1/25.
//

#ifndef LITEX_FLOW_SIM_MISC_H
#define LITEX_FLOW_SIM_MISC_H
#include <iosfwd>

enum class Color : int {
    Red = 31,
    Green = 32,
    Yellow = 33,
    Blue = 34,
    Reset = 0
};

inline std::ostream& operator<<(std::ostream& os, Color c) {
    return os << "\033[" << static_cast<int>(c) << "m";
}
#endif //LITEX_FLOW_SIM_MISC_H