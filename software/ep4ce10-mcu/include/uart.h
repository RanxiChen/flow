#ifndef EP4CE10_MCU_UART_H
#define EP4CE10_MCU_UART_H

#include "soc.h"

static inline void uart_putc(char value)
{
    while (mmio_read32(UART_TXFULL) != 0u) {}
    mmio_write32(UART_RXTX, (uint32_t)(uint8_t)value);
}

static inline void uart_puts(const char *text)
{
    while (*text != '\0') uart_putc(*text++);
}

#endif
