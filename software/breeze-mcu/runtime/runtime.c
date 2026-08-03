#include <stdint.h>

#include "breeze/csr.h"
#include "breeze/perf.h"
#include "breeze/runtime.h"

extern int main(void);

static void breeze_pmu_prepare(void)
{
    BREEZE_CSR_WRITE(mcountinhibit, BREEZE_PMU_INHIBIT_MASK);
    BREEZE_CSR_WRITE(mhpmevent3, BREEZE_HPM_CONTROL_RETIRED);
    BREEZE_CSR_WRITE(mhpmevent4, BREEZE_HPM_CONTROL_TAKEN);
    BREEZE_CSR_WRITE(mhpmevent5, BREEZE_HPM_PREDICTION_MISS);
    BREEZE_CSR_WRITE(mhpmevent6, BREEZE_HPM_ICACHE_MISS);
    BREEZE_CSR_WRITE(mhpmevent7, BREEZE_HPM_DCACHE_ACCESS);
    BREEZE_CSR_WRITE(mhpmevent8, BREEZE_HPM_DCACHE_MISS);
    BREEZE_CSR_WRITE(mhpmevent9, BREEZE_HPM_DCACHE_UNCACHED);
    BREEZE_CSR_WRITE(mhpmevent10, BREEZE_HPM_MEM_STALL_CYCLE);
    BREEZE_CSR_WRITE(mcycle, 0);
    BREEZE_CSR_WRITE(minstret, 0);
    BREEZE_CSR_WRITE(mhpmcounter3, 0);
    BREEZE_CSR_WRITE(mhpmcounter4, 0);
    BREEZE_CSR_WRITE(mhpmcounter5, 0);
    BREEZE_CSR_WRITE(mhpmcounter6, 0);
    BREEZE_CSR_WRITE(mhpmcounter7, 0);
    BREEZE_CSR_WRITE(mhpmcounter8, 0);
    BREEZE_CSR_WRITE(mhpmcounter9, 0);
    BREEZE_CSR_WRITE(mhpmcounter10, 0);
}

static void breeze_pmu_start(void)
{
    BREEZE_CSR_WRITE(mcountinhibit, 0);
}

static void breeze_pmu_stop_and_snapshot(void)
{
    BREEZE_CSR_WRITE(mcountinhibit, BREEZE_PMU_INHIBIT_MASK);
    __breeze_pmu_snapshot[0] = BREEZE_CSR_READ(mcycle);
    __breeze_pmu_snapshot[1] = BREEZE_CSR_READ(minstret);
    __breeze_pmu_snapshot[2] = BREEZE_CSR_READ(mhpmcounter3);
    __breeze_pmu_snapshot[3] = BREEZE_CSR_READ(mhpmcounter4);
    __breeze_pmu_snapshot[4] = BREEZE_CSR_READ(mhpmcounter5);
    __breeze_pmu_snapshot[5] = BREEZE_CSR_READ(mhpmcounter6);
    __breeze_pmu_snapshot[6] = BREEZE_CSR_READ(mhpmcounter7);
    __breeze_pmu_snapshot[7] = BREEZE_CSR_READ(mhpmcounter8);
    __breeze_pmu_snapshot[8] = BREEZE_CSR_READ(mhpmcounter9);
    __breeze_pmu_snapshot[9] = BREEZE_CSR_READ(mhpmcounter10);
    __asm__ volatile ("fence iorw, w" ::: "memory");
}

static void breeze_report_result(uint64_t result)
{
    __asm__ volatile ("fence iorw, w" ::: "memory");
    __breeze_result = result;
}

void breeze_fail(void)
{
    breeze_clear_mstatus(BREEZE_MSTATUS_MIE);
    breeze_pmu_stop_and_snapshot();
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

    breeze_pmu_prepare();
    breeze_report_result(0);
    breeze_pmu_start();
    result = main();
    breeze_pmu_stop_and_snapshot();

    if (result == 0) {
        breeze_report_result(BREEZE_PASS_MAGIC);
    } else {
        breeze_report_result(BREEZE_FAIL_MAGIC);
    }

    for (;;) {
        __asm__ volatile ("nop");
    }
}
