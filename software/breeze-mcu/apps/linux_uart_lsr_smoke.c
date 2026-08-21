#include <stdint.h>

#define FLOW_UART_LSR_ADDR UINT64_C(0x13000005)
#define UART_LSR_THRE      UINT8_C(0x20)
#define UART_LSR_TEMT      UINT8_C(0x40)

int main(void)
{
    uint8_t lsr = *(volatile uint8_t *)(uintptr_t)FLOW_UART_LSR_ADDR;

    return (lsr & (UART_LSR_THRE | UART_LSR_TEMT)) ==
           (UART_LSR_THRE | UART_LSR_TEMT) ? 0 : 1;
}
