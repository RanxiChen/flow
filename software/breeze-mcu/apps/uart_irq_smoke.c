#include <stdint.h>

#include "breeze/csr.h"
#include "breeze/platform.h"
#include "breeze/runtime.h"
#include "breeze/uart.h"

static volatile uint64_t uart_irq_seen;

void breeze_trap_handler(uint64_t mcause, uint64_t mepc, uint64_t mtval)
{
    (void)mepc;
    (void)mtval;
    if (mcause != BREEZE_MCAUSE_EXTERNAL) {
        breeze_fail();
    }

    /* LiteX TX-ready is a level event; disabling it deasserts IRQ0. */
    breeze_uart_set_event_enable(0);
    breeze_clear_mie(BREEZE_MIE_MEIE);
    uart_irq_seen = 1;
}

int main(void)
{
    breeze_uart_puts("UART_IRQ: ARM\r\n");
    uart_irq_seen = 0;
    breeze_uart_set_event_enable(0);
    breeze_set_mie(BREEZE_MIE_MEIE);
    breeze_uart_set_event_enable(BREEZE_UART_EVENT_TX);
    breeze_set_mstatus(BREEZE_MSTATUS_MIE);

    while (uart_irq_seen == 0) {
        __asm__ volatile ("nop");
    }

    if (breeze_uart_get_event_enable() != 0) {
        return 1;
    }
    breeze_uart_puts("UART_IRQ: PASS\r\n");
    return 0;
}
