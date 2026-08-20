#include <stdint.h>

static void fp_enable(void)
{
    const uint64_t fs_initial = UINT64_C(1) << 13;
    __asm__ volatile ("csrs mstatus, %0" :: "r"(fs_initial) : "memory");
}

static void fp_clear_flags(void)
{
    __asm__ volatile ("csrw fflags, zero" ::: "memory");
}

static uint64_t fp_read_flags(void)
{
    uint64_t value;
    __asm__ volatile ("csrr %0, fflags" : "=r"(value));
    return value;
}

static uint64_t add_d(uint64_t a, uint64_t b)
{
    uint64_t result;
    __asm__ volatile (
        "fmv.d.x ft0, %1\n"
        "fmv.d.x ft1, %2\n"
        "fadd.d  ft2, ft0, ft1\n"
        "fmv.x.d %0, ft2\n"
        : "=r"(result) : "r"(a), "r"(b) : "ft0", "ft1", "ft2");
    return result;
}

static uint64_t fmadd_d(uint64_t a, uint64_t b, uint64_t c)
{
    uint64_t result;
    __asm__ volatile (
        "fmv.d.x ft0, %1\n"
        "fmv.d.x ft1, %2\n"
        "fmv.d.x ft2, %3\n"
        "fmadd.d ft3, ft0, ft1, ft2\n"
        "fmv.x.d %0, ft3\n"
        : "=r"(result) : "r"(a), "r"(b), "r"(c)
        : "ft0", "ft1", "ft2", "ft3");
    return result;
}

static uint64_t add_s(uint32_t a, uint32_t b)
{
    uint64_t result;
    __asm__ volatile (
        "fmv.w.x ft0, %1\n"
        "fmv.w.x ft1, %2\n"
        "fadd.s  ft2, ft0, ft1\n"
        "fmv.x.d %0, ft2\n"
        : "=r"(result) : "r"((uint64_t)a), "r"((uint64_t)b)
        : "ft0", "ft1", "ft2");
    return result;
}

static uint64_t div_zero_flags(void)
{
    const uint64_t one = UINT64_C(0x3ff0000000000000);
    uint64_t result;
    __asm__ volatile (
        "fmv.d.x ft0, %1\n"
        "fmv.d.x ft1, zero\n"
        "fdiv.d  ft2, ft0, ft1\n"
        "fmv.x.d %0, ft2\n"
        : "=r"(result) : "r"(one) : "ft0", "ft1", "ft2");
    return result;
}

static uint64_t load_store_d(uint64_t value)
{
    volatile uint64_t slot = value;
    uint64_t result;
    __asm__ volatile (
        "fld ft0, 0(%1)\n"
        "fsd ft0, 0(%2)\n"
        "ld  %0, 0(%2)\n"
        : "=r"(result) : "r"(&slot), "r"(&slot)
        : "ft0", "memory");
    return result;
}

int main(void)
{
    fp_enable();
    fp_clear_flags();

    if (add_d(UINT64_C(0x3ff0000000000000),
              UINT64_C(0x4000000000000000)) !=
              UINT64_C(0x4008000000000000)) return 1; /* 1+2=3 */
    if (fmadd_d(UINT64_C(0x3ff0000000000000),
                UINT64_C(0x4000000000000000),
                UINT64_C(0x3ff0000000000000)) !=
                UINT64_C(0x4008000000000000)) return 2; /* 1*2+1=3 */
    if (add_s(UINT32_C(0x3fc00000), UINT32_C(0x40100000)) !=
              UINT64_C(0xffffffff40700000)) return 3; /* boxed 3.75f */
    if (load_store_d(UINT64_C(0xc008000000000000)) !=
                     UINT64_C(0xc008000000000000)) return 4;
    if (div_zero_flags() != UINT64_C(0x7ff0000000000000)) return 5;
    if ((fp_read_flags() & UINT64_C(0x1f)) != UINT64_C(0x08)) return 6;
    return 0;
}
