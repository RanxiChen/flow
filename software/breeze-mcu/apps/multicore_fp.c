#include <stdint.h>

#ifndef BREEZE_NUM_HARTS
#define BREEZE_NUM_HARTS 4
#endif

#if BREEZE_NUM_HARTS != 4
#error "multicore_fp.c requires the small four-hart profile"
#endif

static volatile uint64_t fp_results[4] __attribute__((aligned(32)));
static volatile uint64_t fp_flags[4] __attribute__((aligned(32)));
static volatile uint64_t fp_done[4] __attribute__((aligned(32)));

static void fp_enable_and_clear(void)
{
    const uint64_t fs_initial = UINT64_C(1) << 13;
    __asm__ volatile ("csrs mstatus, %0\ncsrw fflags, zero"
                      :: "r"(fs_initial) : "memory");
}

static uint64_t fp_read_flags(void)
{
    uint64_t value;
    __asm__ volatile ("csrr %0, fflags" : "=r"(value));
    return value & UINT64_C(0x1f);
}

static uint64_t fp_add(uint64_t a, uint64_t b)
{
    uint64_t result;
    __asm__ volatile (
        "fmv.d.x ft0, %1\nfmv.d.x ft1, %2\n"
        "fadd.d ft2, ft0, ft1\nfmv.x.d %0, ft2"
        : "=r"(result) : "r"(a), "r"(b) : "ft0", "ft1", "ft2");
    return result;
}

static void fp_div_zero(void)
{
    const uint64_t one = UINT64_C(0x3ff0000000000000);
    __asm__ volatile (
        "fmv.d.x ft0, %0\nfmv.d.x ft1, zero\nfdiv.d ft2, ft0, ft1"
        :: "r"(one) : "ft0", "ft1", "ft2");
}

static void fp_invalid_sqrt(void)
{
    const uint64_t minus_one = UINT64_C(0xbff0000000000000);
    __asm__ volatile ("fmv.d.x ft0, %0\nfsqrt.d ft1, ft0"
                      :: "r"(minus_one) : "ft0", "ft1");
}

static void publish_result(uint64_t hart, uint64_t result)
{
    fp_results[hart] = result;
    fp_flags[hart] = fp_read_flags();
    __asm__ volatile ("fence rw, rw" ::: "memory");
    fp_done[hart] = 1;
}

static void run_hart(uint64_t hart)
{
    static const uint64_t a[4] = {
        UINT64_C(0x3ff0000000000000), /*  1.0 */
        UINT64_C(0x4000000000000000), /*  2.0 */
        UINT64_C(0x4000000000000000), /*  2.0 */
        UINT64_C(0xbff0000000000000)  /* -1.0 */
    };
    static const uint64_t b[4] = {
        UINT64_C(0x3ff0000000000000), /* 1.0 */
        UINT64_C(0x3ff0000000000000), /* 1.0 */
        UINT64_C(0x4008000000000000), /* 3.0 */
        UINT64_C(0x3fe0000000000000)  /* 0.5 */
    };
    uint64_t result;

    fp_enable_and_clear();
    result = fp_add(a[hart], b[hart]);
    if (hart == 0) fp_div_zero();       /* DZ only on hart 0 */
    if (hart == 1) fp_invalid_sqrt();   /* NV only on hart 1 */
    publish_result(hart, result);
}

void breeze_secondary_main(uint64_t hart)
{
    if (hart > 0 && hart < 4) run_hart(hart);
}

int main(void)
{
    static const uint64_t expected_results[4] = {
        UINT64_C(0x4000000000000000), /*  2.0 */
        UINT64_C(0x4008000000000000), /*  3.0 */
        UINT64_C(0x4014000000000000), /*  5.0 */
        UINT64_C(0xbfe0000000000000)  /* -0.5 */
    };
    static const uint64_t expected_flags[4] = {8, 16, 0, 0};
    uint64_t timeout = UINT64_C(200000);
    unsigned hart;

    run_hart(0);
    for (hart = 1; hart < 4; hart++) {
        while (fp_done[hart] != 1 && timeout != 0) timeout--;
        if (timeout == 0) return 1;
    }
    __asm__ volatile ("fence rw, rw" ::: "memory");
    for (hart = 0; hart < 4; hart++) {
        if (fp_results[hart] != expected_results[hart]) return 2 + (int)hart;
        if (fp_flags[hart] != expected_flags[hart]) return 6 + (int)hart;
    }
    return 0;
}
