#include <stdint.h>

#include "breeze/uart.h"

static uint64_t fibonacci(uint32_t count)
{
    uint64_t previous = 0;
    uint64_t current = 1;
    uint32_t index;

    for (index = 0; index < count; ++index) {
        uint64_t next = previous + current;

        previous = current;
        current = next;
    }
    return previous;
}

int main(void)
{
    volatile uint32_t count = 40;
    uint64_t result = fibonacci(count);

    breeze_uart_puts("Breeze MCU Fibonacci example\r\n");
    breeze_uart_puts("fib(40) = ");
    breeze_uart_put_hex64(result);
    breeze_uart_puts("\r\n");

    return result == UINT64_C(102334155) ? 0 : 1;
}
