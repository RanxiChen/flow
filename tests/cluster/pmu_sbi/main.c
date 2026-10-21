/* Directed SBI/RTL integration. No Linux, no synthetic SBI implementation. */
#include <stdint.h>
#include <breeze/observe.h>
#include "sbi-pmu-abi.h"

#define LCOFI (1UL << 13)
#define SIE 2UL
#define SAMPLES 6u
#define CSR_READ(csr) ({ unsigned long x; __asm__ volatile("csrr %0," #csr : "=r"(x)); x; })
struct sbiret { long error; unsigned long value; };
static inline struct sbiret pmu(unsigned long fn, unsigned long x0, unsigned long x1,
                               unsigned long x2, unsigned long x3, unsigned long x4) {
    register unsigned long a0 __asm__("a0") = x0;
    register unsigned long a1 __asm__("a1") = x1;
    register unsigned long a2 __asm__("a2") = x2;
    register unsigned long a3 __asm__("a3") = x3;
    register unsigned long a4 __asm__("a4") = x4;
    register unsigned long a5 __asm__("a5") = 0;
    register unsigned long a6 __asm__("a6") = fn;
    register unsigned long a7 __asm__("a7") = SBI_EXT_PMU;
    __asm__ volatile("ecall" : "+r"(a0), "+r"(a1) : "r"(a2), "r"(a3), "r"(a4),
                     "r"(a5), "r"(a6), "r"(a7) : "memory");
    return (struct sbiret){(long)a0,a1};
}
extern volatile unsigned long tohost;
static volatile unsigned long index, period, seen;
volatile unsigned long done;
static volatile unsigned long pcs[SAMPLES];
extern char pmu_hot_begin[], pmu_hot_end[];

static void puts_mmio(const char *s) {
    while (*s) *(volatile unsigned char *)0x12000000 = (unsigned char)*s++;
}
__attribute__((noreturn)) static void fail(unsigned code) {
    __asm__ volatile("csrc sie,%0" :: "r"(LCOFI) : "memory");
    tohost = ((unsigned long)code << 1) | 1;
    for (;;) __asm__ volatile("wfi");
}
static void check(long error, unsigned code) {
    if (error) {
        puts_mmio("PMU_SBI error=0x");
        for(int i=60;i>=0;i-=4) {
            unsigned digit=((unsigned long)error>>i)&15;
            *(volatile unsigned char *)0x12000000=(unsigned char)(digit<10?'0'+digit:'a'+digit-10);
        }
        puts_mmio("\n");
        fail(code);
    }
}

void pmu_irq(void) {
    if (CSR_READ(scause) != ((1UL << 63)|13)) fail(10);
    if (!(CSR_READ(0xda0) & (1UL << index))) fail(11);
    __asm__ volatile("csrc sip,%0" :: "r"(LCOFI) : "memory");
    if (seen >= SAMPLES) fail(13);
    const unsigned last = seen+1 == SAMPLES;
    check(pmu(SBI_EXT_PMU_COUNTER_STOP,index,1,last?SBI_PMU_STOP_FLAG_RESET:0,0,0).error,12);
    pcs[seen++] = CSR_READ(sepc);
    if (last) {
        done = 1;
    } else {
        check(pmu(SBI_EXT_PMU_COUNTER_START,index,1,SBI_PMU_START_FLAG_SET_INIT_VALUE,0UL-period,0).error,15);
    }
}

extern unsigned long hot(void);
__attribute__((noinline,aligned(64))) static void miss_hot(void) {
    unsigned budget=10000;
    while (!done && --budget) __asm__ volatile("fence.i" ::: "memory");
    if (!budget) fail(17);
}
static void run(unsigned event, unsigned long raw, unsigned long p, const char *msg) {
    seen=0; done=0; period=p;
    struct sbiret c=pmu(SBI_EXT_PMU_COUNTER_CFG_MATCH,0,0x7f8,SBI_PMU_CFG_FLAG_CLEAR_VALUE,event,raw);
    check(c.error,20);
    index=c.value;
    if (index<3 || index>10) fail(21);
    struct sbiret info=pmu(SBI_EXT_PMU_COUNTER_GET_INFO,index,0,0,0,0);
    check(info.error,22);
    if ((info.value & 0xfff) != 0xc00+index || ((info.value>>12)&63) != 63) fail(23);
    __asm__ volatile("csrs sie,%0" :: "r"(LCOFI) : "memory");
    __asm__ volatile("csrs sstatus,%0" :: "r"(SIE) : "memory");
    check(pmu(SBI_EXT_PMU_COUNTER_START,index,1,SBI_PMU_START_FLAG_SET_INIT_VALUE,0UL-period,0).error,24);
    if (event==1 && !raw) {
        __asm__ volatile("wfi" ::: "memory");
        if (!seen) fail(28); // programmable cycles must wake a sleeping S hart
    }
    if (raw) miss_hot(); else if (!hot()) fail(16);
    __asm__ volatile("csrc sie,%0" :: "r"(LCOFI) : "memory");
    if (seen != SAMPLES) fail(25);
    if (!raw) {
        unsigned in_hot=0;
        for(unsigned i=0;i<SAMPLES;++i)
            if(pcs[i]>=(unsigned long)pmu_hot_begin && pcs[i]<(unsigned long)pmu_hot_end) ++in_hot;
        if (in_hot < SAMPLES/2) fail(26);
    }
    // All pending interrupts were acknowledged and no stopped counter retriggers.
    if(CSR_READ(sip)&LCOFI) fail(27);
    puts_mmio(msg);
}
void pmu_main(void) {
    run(1,0,4096,"PMU_SBI cycles PASS\n");
    run(2,0,128,"PMU_SBI instructions PASS\n");
    run(0x20000,BREEZE_OBS_RAW(BREEZE_OBS_IC_DEMAND_MISS),1,"PMU_SBI icache-miss PASS\n");
    puts_mmio("PMU_SBI all PASS\n");
    tohost=1;
    for (;;) __asm__ volatile("wfi");
}
