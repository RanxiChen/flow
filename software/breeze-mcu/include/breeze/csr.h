#ifndef BREEZE_CSR_H
#define BREEZE_CSR_H

#include <stdint.h>

#define BREEZE_MSTATUS_MIE (UINT64_C(1) << 3)
#define BREEZE_MIE_MTIE    (UINT64_C(1) << 7)
#define BREEZE_MIE_MEIE    (UINT64_C(1) << 11)

#define BREEZE_MCAUSE_INTERRUPT (UINT64_C(1) << 63)
#define BREEZE_MCAUSE_TIMER     (BREEZE_MCAUSE_INTERRUPT | UINT64_C(7))
#define BREEZE_MCAUSE_EXTERNAL  (BREEZE_MCAUSE_INTERRUPT | UINT64_C(11))

static inline uint64_t breeze_read_mcycle(void)
{
    uint64_t value;
    __asm__ volatile ("csrr %0, mcycle" : "=r"(value));
    return value;
}

static inline uint64_t breeze_read_minstret(void)
{
    uint64_t value;
    __asm__ volatile ("csrr %0, minstret" : "=r"(value));
    return value;
}

static inline void breeze_set_mstatus(uint64_t mask)
{
    __asm__ volatile ("csrs mstatus, %0" :: "r"(mask) : "memory");
}

static inline void breeze_clear_mstatus(uint64_t mask)
{
    __asm__ volatile ("csrc mstatus, %0" :: "r"(mask) : "memory");
}

static inline void breeze_set_mie(uint64_t mask)
{
    __asm__ volatile ("csrs mie, %0" :: "r"(mask) : "memory");
}

static inline void breeze_clear_mie(uint64_t mask)
{
    __asm__ volatile ("csrc mie, %0" :: "r"(mask) : "memory");
}

#endif
