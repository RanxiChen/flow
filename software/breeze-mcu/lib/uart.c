#include <stdint.h>

#include "breeze/platform.h"
#include "breeze/uart.h"

static inline uint32_t mmio_read32(uint64_t address)
{
    return *(volatile uint32_t *)(uintptr_t)address;
}

static inline void mmio_write32(uint64_t address, uint32_t value)
{
    *(volatile uint32_t *)(uintptr_t)address = value;
}

void breeze_uart_putc(char value)
{
    while (mmio_read32(BREEZE_UART_TXFULL_ADDR) != 0u) {
    }
    mmio_write32(BREEZE_UART_RXTX_ADDR, (uint8_t)value);
}

void breeze_uart_puts(const char *text)
{
    while (*text != '\0') {
        breeze_uart_putc(*text++);
    }
}

void breeze_uart_put_hex64(uint64_t value)
{
    static const char digits[] = "0123456789abcdef";
    unsigned shift;

    breeze_uart_puts("0x");
    for (shift = 60; shift < 64; shift -= 4) {
        breeze_uart_putc(digits[(value >> shift) & 0xfu]);
        if (shift == 0) {
            break;
        }
    }
}

void breeze_uart_set_event_enable(uint32_t mask)
{
    mmio_write32(BREEZE_UART_EV_ENABLE_ADDR, mask);
}

uint32_t breeze_uart_get_event_enable(void)
{
    return mmio_read32(BREEZE_UART_EV_ENABLE_ADDR);
}
