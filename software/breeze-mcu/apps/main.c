#include "breeze/uart.h"

int main(void)
{
    volatile unsigned value = 0;
    unsigned index;

    breeze_uart_puts("Breeze MCU application start\r\n");
    for (index = 0; index < 32; ++index) {
        value += index;
    }
    return value == 496u ? 0 : 1;
}
