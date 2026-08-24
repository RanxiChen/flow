#ifndef EP4CE10_MCU_GPIO_H
#define EP4CE10_MCU_GPIO_H

#include "soc.h"

static inline void gpio_led_write(uint32_t value)
{
    mmio_write32(GPIO_OUT, value & 0x0fu);
}

#endif
