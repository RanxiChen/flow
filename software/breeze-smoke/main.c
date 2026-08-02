#include <stdint.h>

#define UART_RXTX_ADDR    ((uintptr_t)0x12001000u)
#define UART_TXFULL_ADDR  ((uintptr_t)0x12001004u)
#define MAIN_RAM_TEST_ADDR ((uintptr_t)0x80000000u)

static inline uint32_t mmio_read32(uintptr_t address)
{
    return *(volatile uint32_t *)address;
}

static inline void mmio_write32(uintptr_t address, uint32_t value)
{
    *(volatile uint32_t *)address = value;
}

static void uart_putc(char value)
{
    while (mmio_read32(UART_TXFULL_ADDR) != 0u) {
    }
    mmio_write32(UART_RXTX_ADDR, (uint8_t)value);
}

static void uart_puts(const char *text)
{
    while (*text != '\0') {
        uart_putc(*text++);
    }
}

int main(void)
{
    volatile uint64_t *const test_word =
        (volatile uint64_t *)MAIN_RAM_TEST_ADDR;
    const uint64_t pattern = UINT64_C(0x0123456789abcdef);

    uart_puts("BreezeCore ROM boot\r\n");

    *test_word = pattern;
    if (*test_word == pattern) {
        uart_puts("main RAM cached R/W: PASS\r\n");
    } else {
        uart_puts("main RAM cached R/W: FAIL\r\n");
    }

    for (;;) {
        __asm__ volatile ("nop");
    }
}
