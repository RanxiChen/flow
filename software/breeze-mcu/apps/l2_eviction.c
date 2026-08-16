#include <stdint.h>

#include "breeze/uart.h"

/* L2 eviction data-integrity test (single hart).
 *
 * The L1D (8 KiB, 64 sets x 4 ways) and the L2 (16 KiB, 64 sets x 8 ways)
 * share set-index bits [10:5] with 32-byte lines. Writing LINES_PER_SET
 * (>8) distinct dirty lines that all land in one set forces L1 victim
 * evictions into the L2 (PutM) and then L2 victim writebacks to RAM.
 * Reading every line back and comparing against its unique pattern proves
 * no dirty data was lost or corrupted anywhere in the chain.
 */

#define EVICT_BASE    0x80000000ull
#define LINE_STRIDE   2048ull  /* keeps addr[10:5] constant, bumps the tag */
#define NUM_SETS      4u
#define LINES_PER_SET 12u      /* 12 > 8 L2 ways: forces L2 eviction */

static uint64_t pattern(unsigned int set, unsigned int index)
{
    return 0xe71ca11000000000ull | ((uint64_t)set << 8) | (uint64_t)index;
}

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

    for (set = 0; set < NUM_SETS; ++set) {
        for (index = 0; index < LINES_PER_SET; ++index) {
            line = (volatile uint64_t *)(
                EVICT_BASE + (uint64_t)set * 32u + (uint64_t)index * LINE_STRIDE);
            if (*line != pattern(set, index)) {
                breeze_uart_puts("L2-EVICTION data mismatch\r\n");
                return 1;
            }
        }
    }

    breeze_uart_puts("L2-EVICTION data intact\r\n");
    return 0;
}
