#ifndef EP4CE10_MCU_INTERRUPT_H
#define EP4CE10_MCU_INTERRUPT_H

#include <stdint.h>

static inline void mcu_enable_timer_interrupt(void)
{
    const uint64_t mtie = UINT64_C(1) << 7;
    const uint64_t mie  = UINT64_C(1) << 3;
    __asm__ volatile ("csrs mie, %0" :: "r"(mtie) : "memory");
    __asm__ volatile ("csrs mstatus, %0" :: "r"(mie) : "memory");
}

static inline void mcu_wfi(void)
{
    __asm__ volatile ("wfi" ::: "memory");
}

#endif
