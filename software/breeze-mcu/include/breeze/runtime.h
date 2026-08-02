#ifndef BREEZE_RUNTIME_H
#define BREEZE_RUNTIME_H

#include <stdint.h>

#define BREEZE_PASS_MAGIC UINT64_C(0x425245455a455001)
#define BREEZE_FAIL_MAGIC UINT64_C(0x425245455a45f001)

extern volatile uint64_t __breeze_result;

void breeze_runtime_entry(void) __attribute__((noreturn));
void breeze_trap_handler(uint64_t mcause, uint64_t mepc, uint64_t mtval);
void breeze_fail(void) __attribute__((noreturn));

#endif
