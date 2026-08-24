#include <stdint.h>

#include "gpio.h"
#include "interrupt.h"
#include "seg7.h"
#include "soc.h"
#include "timer.h"
#include "uart.h"

static volatile uint32_t seconds;
static uint32_t display_bcd;

void mcu_trap_handler(uint64_t cause, uint64_t epc)
{
    (void)epc;
    if (cause == (MCAUSE_INTERRUPT | MCAUSE_MACHINE_TIMER)) {
        timer0_ack();
        seconds++;
        display_bcd = seg7_bcd_increment(display_bcd);
        gpio_led_write(seconds & 0x0fu);
        seg7_write(display_bcd, 0x3fu, 0u);
        return;
    }

    gpio_led_write(1u);
    uart_puts("TRAP\r\n");
    for (;;) mcu_wfi();
}

int main(void)
{
    gpio_led_write(0u);
    display_bcd = 0u;
    seg7_write(display_bcd, 0x3fu, 0u);
    uart_puts("MCU IRQ READY\r\n");
    timer0_start_periodic(MCU_SYS_CLK_HZ);
    mcu_enable_timer_interrupt();

    for (;;) mcu_wfi();
}
