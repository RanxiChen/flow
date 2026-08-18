#include <stdint.h>

#include "breeze/uart.h"

/* L2 eviction data-integrity test (single hart; the stride scales with the
 * profile so the same source also drives the four-hart small case via
 * multicore_t5.c). Writing LINES_PER_SET (>8) distinct dirty lines that all
 * land in one L2 set forces L1 victim evictions into the L2 (PutM) and then
 * L2 victim writebacks to RAM. Reading every line back and comparing it
 * against its unique pattern proves no dirty data was lost or corrupted
 * anywhere in the chain. */

#define EVICT_BASE    0x80000000ull

#ifndef BREEZE_L2_BYTES
#error "BREEZE_L2_BYTES must be provided by the runner (frozen L2 formula)"
#endif

/* Set stride = L2 capacity / ways keeps the L2 set index constant while
 * bumping the tag: single 16384/8 = 2048, dual 32768/8 = 4096,
 * small 65536/8 = 8192.  Every stride is a multiple of 2048, so the L1D
 * set bits addr[10:5] stay constant too and each line store exercises the
 * L1D victim -> L2 -> RAM chain of the profile that is actually running. */
#define LINE_STRIDE ((uint64_t)BREEZE_L2_BYTES / 8u)
#define NUM_SETS      4u
#ifndef LINES_PER_SET
#define LINES_PER_SET 12u      /* 12 > 8 L2 ways: forces L2 eviction */
#endif

int main(void)
{
    unsigned int set, index;
    volatile uint64_t *line;

    for (set = 0; set < NUM_SETS; ++set) {
        for (index = 0; index < LINES_PER_SET; ++index) {
            line = (volatile uint64_t *)(
                EVICT_BASE + (uint64_t)set * 32u + (uint64_t)index * LINE_STRIDE);
            *line = pattern(set, index);
        }
    }

    unsigned int mismatches = 0;

    for (set = 0; set < NUM_SETS; ++set) {
        for (index = 0; index < LINES_PER_SET; ++index) {
            uint64_t got;
            line = (volatile uint64_t *)(
                EVICT_BASE + (uint64_t)set * 32u + (uint64_t)index * LINE_STRIDE);
            got = *line;
            if (got != pattern(set, index)) {
                ++mismatches;
                /* UART output costs ~87 cycles/char in simulation: full
                 * detail for the first bad line only, then count; the
                 * complete report must fit the 20000-cycle watchdog. */
                if (mismatches == 1u) {
                    breeze_uart_puts("L2-EVICTION mismatch set=");
                    breeze_uart_put_hex64(set);
                    breeze_uart_puts(" index=");
                    breeze_uart_put_hex64(index);
                    breeze_uart_puts(" expected=");
                    breeze_uart_put_hex64(pattern(set, index));
                    breeze_uart_puts(" got=");
                    breeze_uart_put_hex64(got);
                    breeze_uart_puts("\r\n");
                }
            }
        }
    }

    if (mismatches != 0u) {
        breeze_uart_puts("L2-EVICTION data mismatch count=");
        breeze_uart_put_hex64(mismatches);
        breeze_uart_puts("\r\n");
        return 1;
    }

    breeze_uart_puts("L2-EVICTION data intact\r\n");
    return 0;
}
