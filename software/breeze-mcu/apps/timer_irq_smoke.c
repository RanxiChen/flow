#include <stdint.h>

#include "breeze/csr.h"
#include "breeze/runtime.h"
#include "breeze/timer.h"
#include "breeze/uart.h"

static volatile uint64_t timer_irq_seen;

void breeze_trap_handler(uint64_t mcause, uint64_t mepc, uint64_t mtval)
{
    (void)mepc;
    (void)mtval;
    if (mcause != BREEZE_MCAUSE_TIMER) {
        breeze_fail();
    }

    /* MTIP is level-sensitive: move mtimecmp out before returning. */
    breeze_timer_set_compare(UINT64_MAX);
    breeze_clear_mie(BREEZE_MIE_MTIE);
    timer_irq_seen = 1;
}

int main(void)
{
    uint64_t now;

    breeze_uart_puts("TIMER_IRQ: ARM\r\n");
    timer_irq_seen = 0;
    breeze_timer_set_compare(UINT64_MAX);
    now = breeze_timer_read();
    breeze_timer_set_compare(now + UINT64_C(64));
    breeze_set_mie(BREEZE_MIE_MTIE);
    breeze_set_mstatus(BREEZE_MSTATUS_MIE);

    while (timer_irq_seen == 0) {
        __asm__ volatile ("nop");
    }

    breeze_uart_puts("TIMER_IRQ: PASS\r\n");
    return 0;
}
