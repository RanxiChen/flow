#ifndef EP4CE10_MCU_TIMER_H
#define EP4CE10_MCU_TIMER_H

#include "soc.h"

static inline void timer0_ack(void)
{
    mmio_write32(TIMER0_EV_PENDING, 1u);
}

static inline void timer0_start_periodic(uint32_t cycles)
{
    mmio_write32(TIMER0_ENABLE, 0u);
    mmio_write32(TIMER0_LOAD, 0u);
    mmio_write32(TIMER0_RELOAD, cycles - 1u);
    timer0_ack();
    mmio_write32(TIMER0_EV_ENABLE, 1u);
    mmio_write32(TIMER0_ENABLE, 1u);
}

#endif
