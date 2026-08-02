#include <stdint.h>

#include "breeze/csr.h"
#include "breeze/runtime.h"

extern int main(void);

static void breeze_report_result(uint64_t result)
{
    __asm__ volatile ("fence iorw, w" ::: "memory");
    __breeze_result = result;
}

void breeze_fail(void)
{
    breeze_clear_mstatus(BREEZE_MSTATUS_MIE);
    breeze_report_result(BREEZE_FAIL_MAGIC);
    for (;;) {
        __asm__ volatile ("nop");
    }
}

__attribute__((weak))
void breeze_trap_handler(uint64_t mcause, uint64_t mepc, uint64_t mtval)
{
    (void)mcause;
    (void)mepc;
    (void)mtval;
    breeze_fail();
}

void breeze_runtime_entry(void)
{
    int result;

    breeze_report_result(0);
    result = main();

    if (result == 0) {
        breeze_report_result(BREEZE_PASS_MAGIC);
    } else {
        breeze_report_result(BREEZE_FAIL_MAGIC);
    }

    for (;;) {
        __asm__ volatile ("nop");
    }
}
