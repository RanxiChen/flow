#include <stdint.h>

#define MTIMECMP_ADDR       UINT64_C(0x02004000)
#define MTIME_ADDR          UINT64_C(0x0200bff8)
#define UART_RXTX_ADDR      UINT64_C(0x12001000)
#define UART_TXFULL_ADDR    UINT64_C(0x12001004)

#define MSTATUS_MIE         (UINT64_C(1) << 3)
#define MIE_MTIE            (UINT64_C(1) << 7)
#define MCAUSE_INTERRUPT    (UINT64_C(1) << 63)
#define MCAUSE_MTIP         (MCAUSE_INTERRUPT | UINT64_C(7))
#define ONE_MINUTE_TICKS    UINT64_C(60000000)

static uint64_t next_deadline;

static inline uint32_t mmio_read32(uint64_t address)
{
    return *(volatile uint32_t *)(uintptr_t)address;
}

static inline void mmio_write32(uint64_t address, uint32_t value)
{
    *(volatile uint32_t *)(uintptr_t)address = value;
}

static inline uint64_t mmio_read64(uint64_t address)
{
    return *(volatile uint64_t *)(uintptr_t)address;
}

static inline void mmio_write64(uint64_t address, uint64_t value)
{
    *(volatile uint64_t *)(uintptr_t)address = value;
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

static inline void set_mie(uint64_t mask)
{
    __asm__ volatile ("csrs mie, %0" :: "r"(mask) : "memory");
}

static inline void set_mstatus(uint64_t mask)
{
    __asm__ volatile ("csrs mstatus, %0" :: "r"(mask) : "memory");
}

void tiny_trap_handler(uint64_t mcause, uint64_t mepc, uint64_t mtval)
{
    (void)mepc;
    (void)mtval;

    if (mcause != MCAUSE_MTIP) {
        mmio_write64(MTIMECMP_ADDR, UINT64_MAX);
        uart_puts("Unexpected trap\r\n");
        for (;;) {
            __asm__ volatile ("wfi");
        }
    }

    /* MTIP is level-sensitive: move the deadline before using the UART. */
    next_deadline += ONE_MINUTE_TICKS;
    if (next_deadline <= mmio_read64(MTIME_ADDR)) {
        next_deadline = mmio_read64(MTIME_ADDR) + ONE_MINUTE_TICKS;
    }
    mmio_write64(MTIMECMP_ADDR, next_deadline);
    uart_puts("Hello World\r\n");
}

int main(void)
{
    mmio_write64(MTIMECMP_ADDR, UINT64_MAX);
    next_deadline = mmio_read64(MTIME_ADDR) + ONE_MINUTE_TICKS;
    mmio_write64(MTIMECMP_ADDR, next_deadline);
    set_mie(MIE_MTIE);
    set_mstatus(MSTATUS_MIE);

    for (;;) {
        __asm__ volatile ("wfi");
    }
}
