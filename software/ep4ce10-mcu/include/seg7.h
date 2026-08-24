#ifndef EP4CE10_MCU_SEG7_H
#define EP4CE10_MCU_SEG7_H

#include "soc.h"

static inline void seg7_write(uint32_t packed_digits, uint32_t enabled,
    uint32_t dots)
{
    mmio_write32(SEG7_DIGITS, packed_digits & 0x00ffffffu);
    mmio_write32(SEG7_DOTS, dots & 0x3fu);
    mmio_write32(SEG7_ENABLE, enabled & 0x3fu);
}

static inline uint32_t seg7_bcd_increment(uint32_t packed_digits)
{
    for (uint32_t shift = 0; shift < 24u; shift += 4u) {
        uint32_t digit = (packed_digits >> shift) & 0x0fu;
        packed_digits &= ~(0x0fu << shift);
        if (digit < 9u)
            return packed_digits | ((digit + 1u) << shift);
    }
    return 0u;
}

#endif
