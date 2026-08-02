#include <stdint.h>

#include "breeze/csr.h"
#include "breeze/runtime.h"
#include "breeze/uart.h"

extern int main(void);

static void print_stats(uint64_t cycles, uint64_t instructions)
{
    breeze_uart_puts("BREEZE_STATS cycles=");
    breeze_uart_put_hex64(cycles);
    breeze_uart_puts(" instructions=");
    breeze_uart_put_hex64(instructions);
    breeze_uart_puts("\r\n");
}

void breeze_fail(void)
{
    breeze_clear_mstatus(BREEZE_MSTATUS_MIE);
    __breeze_result = BREEZE_FAIL_MAGIC;
    for (;;) {
        __asm__ volatile ("nop");
    }
}

__attribute__((weak))
void breeze_trap_handler(uint64_t mcause, uint64_t mepc, uint64_t mtval)
{
    (void)mcause;
    (void)mepc;
    (void)mtval;
    breeze_fail();
}

void breeze_runtime_entry(void)
{
    uint64_t cycle_start;
    uint64_t instret_start;
    uint64_t cycle_end;
    uint64_t instret_end;
    int result;

    __breeze_result = 0;
    cycle_start = breeze_read_mcycle();
    instret_start = breeze_read_minstret();
    result = main();
    instret_end = breeze_read_minstret();
    cycle_end = breeze_read_mcycle();

    print_stats(cycle_end - cycle_start, instret_end - instret_start);
    if (result == 0) {
        breeze_uart_puts("BREEZE_MCU: PASS\r\n");
        __breeze_result = BREEZE_PASS_MAGIC;
    } else {
        breeze_uart_puts("BREEZE_MCU: FAIL\r\n");
        __breeze_result = BREEZE_FAIL_MAGIC;
    }

    for (;;) {
        __asm__ volatile ("nop");
    }
}
