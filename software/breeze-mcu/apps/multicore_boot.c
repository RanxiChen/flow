#include <stdint.h>

#include "breeze/uart.h"

/* Boot smoke for the cluster: the runtime entered main, so the hart booted
 * from the shared reset vector through the coherent I$/L2 path. On the
 * single profile the only hart must observe mhartid 0. (Multi-hart stack
 * splitting and the boot barrier arrive with the dual/small phases.)
 */
int main(void)
{
    uint64_t hart;
    __asm__ volatile ("csrr %0, mhartid" : "=r"(hart));
    if (hart != 0u) {
        breeze_uart_puts("MULTICORE-BOOT unexpected hart id\r\n");
        return 1;
    }
    breeze_uart_puts("MULTICORE-BOOT hart0 running\r\n");
    return 0;
}
