#include <stdint.h>

#include "breeze/platform.h"
#include "breeze/timer.h"

uint64_t breeze_timer_read(void)
{
    return *(volatile uint64_t *)(uintptr_t)BREEZE_MTIME_ADDR;
}

void breeze_timer_set_compare(uint64_t value)
{
    *(volatile uint64_t *)(uintptr_t)BREEZE_MTIMECMP_ADDR = value;
}
