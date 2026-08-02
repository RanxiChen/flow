#ifndef BREEZE_TIMER_H
#define BREEZE_TIMER_H

#include <stdint.h>

uint64_t breeze_timer_read(void);
void breeze_timer_set_compare(uint64_t value);

#endif
