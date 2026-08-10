#include <stdint.h>

#include "breeze/uart.h"

enum {
    VECTOR_LENGTH = 8,
    REPEAT_COUNT = 64,
};

/* Volatile inputs keep the compiler from replacing the workload with a
 * precomputed constant.  The inner loop naturally emits MUL followed by a
 * dependent accumulator update, exercising the completion bypass as well.
 */
static volatile int64_t left[VECTOR_LENGTH] = {
    3, -7, 11, 13, -17, 19, 23, -29,
};

static volatile int64_t right[VECTOR_LENGTH] = {
    5, 2, -3, 7, 4, -6, 8, -9,
};

static __attribute__((noinline)) int64_t repeated_dot_product(void)
{
    int64_t sum = 0;
    uint32_t repeat;
    uint32_t index;

    for (repeat = 0; repeat < REPEAT_COUNT; ++repeat) {
        for (index = 0; index < VECTOR_LENGTH; ++index) {
            sum += left[index] * right[index];
        }
    }
    return sum;
}

int main(void)
{
    const int64_t expected = INT64_C(20608);
    int64_t result = repeated_dot_product();

    breeze_uart_puts("Breeze MCU RV64M multiply workload\r\n");
    breeze_uart_puts("dot-product checksum = ");
    breeze_uart_put_hex64((uint64_t)result);
    breeze_uart_puts("\r\n");

    return result == expected ? 0 : 1;
}
