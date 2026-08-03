#ifndef BREEZE_PERF_H
#define BREEZE_PERF_H

#include <stdint.h>

#define BREEZE_HPM_NONE             UINT64_C(0)
#define BREEZE_HPM_CONTROL_RETIRED  UINT64_C(1)
#define BREEZE_HPM_CONTROL_TAKEN    UINT64_C(2)
#define BREEZE_HPM_PREDICTION_MISS  UINT64_C(3)
#define BREEZE_HPM_ICACHE_ACCESS    UINT64_C(4)
#define BREEZE_HPM_ICACHE_MISS      UINT64_C(5)
#define BREEZE_HPM_DCACHE_ACCESS    UINT64_C(6)
#define BREEZE_HPM_DCACHE_MISS      UINT64_C(7)
#define BREEZE_HPM_DCACHE_UNCACHED  UINT64_C(8)
#define BREEZE_HPM_MEM_STALL_CYCLE  UINT64_C(9)
#define BREEZE_HPM_LOAD_USE_STALL   UINT64_C(10)

#define BREEZE_PMU_COUNTERS 8u
#define BREEZE_PMU_SNAPSHOT_WORDS (2u + BREEZE_PMU_COUNTERS)
#define BREEZE_PMU_INHIBIT_MASK UINT64_C(0x7fd)

#define BREEZE_CSR_WRITE(csr, value) \
    __asm__ volatile ("csrw " #csr ", %0" :: "r"((uint64_t)(value)) : "memory")
#define BREEZE_CSR_READ(csr) ({ \
    uint64_t _value; \
    __asm__ volatile ("csrr %0, " #csr : "=r"(_value)); \
    _value; \
})

extern volatile uint64_t __breeze_pmu_snapshot[BREEZE_PMU_SNAPSHOT_WORDS];

#endif
