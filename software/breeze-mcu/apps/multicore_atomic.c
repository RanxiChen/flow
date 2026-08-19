#include <stdint.h>

#include "breeze/uart.h"

/* RV64A firmware (spec sections 14-16, tests T6/T7).
 *
 * Case 1 (amo-directed)   : hart 0 walks all 9 AMO operations in .W and .D,
 *                           checking both the returned old value and the
 *                           resulting memory value, including the signed and
 *                           unsigned MIN/MAX boundaries and the .W sign
 *                           extension of the returned old value.
 * Case 2 (amo-contention) : every hart runs AMOADD.D/.W loops against one
 *                           shared counter; the final sums must be exact, so
 *                           no update may be lost.
 * Case 3 (lrsc-success)   : LR/SC round trip plus an LR/SC-built spinlock
 *                           guarding a non-atomic critical section.
 * Case 4 (lrsc-fail)      : hart 0 holds a reservation while hart 1 writes a
 *                           *different word of the same 32 B line*; the SC
 *                           must fail (conservative line granule) and must
 *                           not write memory.
 *
 * The LR ... SC windows are single asm blocks: any store the compiler might
 * schedule in between (a register spill, for instance) would clear the
 * reservation and make the test lie.
 */

#ifndef BREEZE_NUM_HARTS
#define BREEZE_NUM_HARTS 1
#endif

#ifndef BREEZE_ATOMIC_CASE
#error "BREEZE_ATOMIC_CASE must select one directed RV64A test"
#endif

#if BREEZE_NUM_HARTS != 1 && BREEZE_NUM_HARTS != 2 && BREEZE_NUM_HARTS != 4
#error "BREEZE_NUM_HARTS must be one of the frozen 1/2/4 profiles"
#endif

#if (BREEZE_ATOMIC_CASE == 2 || BREEZE_ATOMIC_CASE == 4) && BREEZE_NUM_HARTS < 2
#error "the contention cases require the dual or small profile"
#endif

#define SPIN_LIMIT UINT64_C(2000000)
#define AMO_ITERATIONS 64
#define LOCK_ITERATIONS 32

/* One 32 B line: the reservation granule of case 4 covers both words. */
static volatile uint64_t shared_line[4] __attribute__((aligned(32)));
static volatile uint64_t control[4] __attribute__((aligned(32)));
static volatile uint64_t lock_word __attribute__((aligned(32)));
static volatile uint64_t guarded_counter;

#define CTL_PHASE  0
#define CTL_READY  1
#define CTL_DONE   2
#define CTL_RESULT 3

static inline void publish(volatile uint64_t *slot, uint64_t value)
{
    *slot = value;
    __asm__ volatile ("fence rw, rw" ::: "memory");
}

static int wait_value(volatile uint64_t *slot, uint64_t value)
{
    uint64_t left = SPIN_LIMIT;
    while (*slot != value) {
        if (--left == 0u) {
            return -1;
        }
    }
    return 0;
}

/* ---- AMO wrappers -------------------------------------------------------- */

#define DEFINE_AMO_D(name, mnemonic)                                          \
    static inline uint64_t name(volatile uint64_t *addr, uint64_t operand)    \
    {                                                                         \
        uint64_t old;                                                         \
        __asm__ volatile (mnemonic ".d %0, %2, (%1)"                          \
                          : "=&r"(old) : "r"(addr), "r"(operand) : "memory"); \
        return old;                                                           \
    }

#define DEFINE_AMO_W(name, mnemonic)                                          \
    static inline int64_t name(volatile uint32_t *addr, uint32_t operand)     \
    {                                                                         \
        int64_t old;                                                          \
        __asm__ volatile (mnemonic ".w %0, %2, (%1)"                          \
                          : "=&r"(old) : "r"(addr), "r"(operand) : "memory"); \
        return old;                                                           \
    }

DEFINE_AMO_D(amoswap_d, "amoswap")
DEFINE_AMO_D(amoadd_d,  "amoadd")
DEFINE_AMO_D(amoxor_d,  "amoxor")
DEFINE_AMO_D(amoand_d,  "amoand")
DEFINE_AMO_D(amoor_d,   "amoor")
DEFINE_AMO_D(amomin_d,  "amomin")
DEFINE_AMO_D(amomax_d,  "amomax")
DEFINE_AMO_D(amominu_d, "amominu")
DEFINE_AMO_D(amomaxu_d, "amomaxu")

DEFINE_AMO_W(amoswap_w, "amoswap")
DEFINE_AMO_W(amoadd_w,  "amoadd")
DEFINE_AMO_W(amoxor_w,  "amoxor")
DEFINE_AMO_W(amoand_w,  "amoand")
DEFINE_AMO_W(amoor_w,   "amoor")
DEFINE_AMO_W(amomin_w,  "amomin")
DEFINE_AMO_W(amomax_w,  "amomax")
DEFINE_AMO_W(amominu_w, "amominu")
DEFINE_AMO_W(amomaxu_w, "amomaxu")

/* ---- LR/SC --------------------------------------------------------------- */

/** LR.D immediately followed by SC.D. Returns 0 when the SC succeeded. */
static inline int lrsc_d(volatile uint64_t *addr, uint64_t newValue,
                         uint64_t *oldValue)
{
    uint64_t old;
    uint64_t status;
    __asm__ volatile (
        "lr.d  %0, (%2)\n"
        "sc.d  %1, %3, (%2)\n"
        : "=&r"(old), "=&r"(status)
        : "r"(addr), "r"(newValue)
        : "memory");
    *oldValue = old;
    return (int)status;
}

/** LR.D, spin on *flag until non-zero (loads never clear a reservation),
  * then SC.D. Returns 0 on SC success, 1 on SC failure, -1 on spin timeout. */
static inline int lrsc_d_after_flag(volatile uint64_t *addr, uint64_t newValue,
                                    volatile uint64_t *flag, uint64_t limit)
{
    uint64_t status;
    uint64_t left = limit;
    __asm__ volatile (
        "lr.d   t0, (%1)\n"
        "1:\n"
        "ld     t1, 0(%3)\n"
        "bnez   t1, 2f\n"
        "addi   %2, %2, -1\n"
        "bnez   %2, 1b\n"
        "li     %0, -1\n"          /* timeout: never attempt the SC */
        "j      3f\n"
        "2:\n"
        "sc.d   %0, %4, (%1)\n"
        "3:\n"
        : "=&r"(status), "+r"(addr), "+r"(left)
        : "r"(flag), "r"(newValue)
        : "t0", "t1", "memory");
    return (int)(int64_t)status;
}

/** LR/SC spinlock: acquire spins until the SC that writes 1 succeeds. */
static void lock_acquire(volatile uint64_t *lock)
{
    uint64_t old;
    for (;;) {
        if (*lock != 0u) {
            continue;
        }
        if (lrsc_d(lock, 1u, &old) == 0 && old == 0u) {
            return;
        }
    }
}

static void lock_release(volatile uint64_t *lock)
{
    __asm__ volatile ("fence rw, w" ::: "memory");
    *lock = 0u;
}

/* ---- Case 1: directed AMO vectors ---------------------------------------- */

#if BREEZE_ATOMIC_CASE == 1
static int check_amo_d(void)
{
    volatile uint64_t *target = &shared_line[0];

    *target = UINT64_C(0x0000000000000010);
    if (amoswap_d(target, UINT64_C(0xdeadbeefcafebabe)) != UINT64_C(0x10)) return 1;
    if (*target != UINT64_C(0xdeadbeefcafebabe)) return 2;

    *target = UINT64_C(100);
    if (amoadd_d(target, (uint64_t)(int64_t)-30) != UINT64_C(100)) return 3;
    if (*target != UINT64_C(70)) return 4;

    /* Wrap-around must be modular, not saturating. */
    *target = UINT64_MAX;
    if (amoadd_d(target, UINT64_C(2)) != UINT64_MAX) return 5;
    if (*target != UINT64_C(1)) return 6;

    *target = UINT64_C(0xff00ff00ff00ff00);
    if (amoxor_d(target, UINT64_C(0x0f0f0f0f0f0f0f0f)) != UINT64_C(0xff00ff00ff00ff00)) return 7;
    if (*target != UINT64_C(0xf00ff00ff00ff00f)) return 8;

    *target = UINT64_C(0xff00ff00ff00ff00);
    if (amoand_d(target, UINT64_C(0x0f0f0f0f0f0f0f0f)) != UINT64_C(0xff00ff00ff00ff00)) return 9;
    if (*target != UINT64_C(0x0f000f000f000f00)) return 10;

    *target = UINT64_C(0xff00ff00ff00ff00);
    if (amoor_d(target, UINT64_C(0x0f0f0f0f0f0f0f0f)) != UINT64_C(0xff00ff00ff00ff00)) return 11;
    if (*target != UINT64_C(0xff0fff0fff0fff0f)) return 12;

    /* Signed MIN/MAX: the most negative and most positive 64-bit values. */
    *target = (uint64_t)INT64_MIN;
    if (amomin_d(target, (uint64_t)INT64_MAX) != (uint64_t)INT64_MIN) return 13;
    if (*target != (uint64_t)INT64_MIN) return 14;

    *target = (uint64_t)INT64_MIN;
    if (amomax_d(target, (uint64_t)INT64_MAX) != (uint64_t)INT64_MIN) return 15;
    if (*target != (uint64_t)INT64_MAX) return 16;

    /* Unsigned MIN/MAX must treat the same bit patterns as large positives. */
    *target = (uint64_t)INT64_MIN; /* 0x8000...0, unsigned-large */
    if (amominu_d(target, (uint64_t)INT64_MAX) != (uint64_t)INT64_MIN) return 17;
    if (*target != (uint64_t)INT64_MAX) return 18;

    *target = (uint64_t)INT64_MIN;
    if (amomaxu_d(target, (uint64_t)INT64_MAX) != (uint64_t)INT64_MIN) return 19;
    if (*target != (uint64_t)INT64_MIN) return 20;

    return 0;
}

static int check_amo_w(void)
{
    /* shared_line[1] holds two 32-bit halves: [2] is addr+8 (low half at
     * offset 8, high half at offset 12), which exercises both the addr[2]
     * half selection and the .W sign extension of the returned old value. */
    volatile uint32_t *low  = (volatile uint32_t *)&shared_line[1];
    volatile uint32_t *high = low + 1;

    shared_line[1] = UINT64_C(0);
    *low = 0x11112222u;
    *high = 0x33334444u;

    if (amoswap_w(low, 0x55556666u) != (int64_t)0x11112222) return 21;
    if (*low != 0x55556666u || *high != 0x33334444u) return 22; /* other half intact */

    if (amoswap_w(high, 0x77778888u) != (int64_t)0x33334444) return 23;
    if (*high != 0x77778888u || *low != 0x55556666u) return 24;

    /* The returned old value is sign-extended: 0x80000000 -> negative. */
    *low = 0x80000000u;
    if (amoadd_w(low, 0u) != (int64_t)(int32_t)0x80000000) return 25;

    /* .W arithmetic is 32-bit and wraps within the half. */
    *low = 0xffffffffu;
    if (amoadd_w(low, 2u) != (int64_t)(int32_t)0xffffffff) return 26;
    if (*low != 1u) return 27;

    *low = 0xff00ff00u;
    if (amoxor_w(low, 0x0f0f0f0fu) != (int64_t)(int32_t)0xff00ff00) return 28;
    if (*low != 0xf00ff00fu) return 29;

    *low = 0xff00ff00u;
    if (amoand_w(low, 0x0f0f0f0fu) != (int64_t)(int32_t)0xff00ff00) return 30;
    if (*low != 0x0f000f00u) return 31;

    *low = 0xff00ff00u;
    if (amoor_w(low, 0x0f0f0f0fu) != (int64_t)(int32_t)0xff00ff00) return 32;
    if (*low != 0xff0fff0fu) return 33;

    /* Signed vs unsigned MIN/MAX at the 32-bit boundary. */
    *low = 0x80000000u; /* INT32_MIN */
    if (amomin_w(low, 0x7fffffffu) != (int64_t)(int32_t)0x80000000) return 34;
    if (*low != 0x80000000u) return 35;

    *low = 0x80000000u;
    if (amomax_w(low, 0x7fffffffu) != (int64_t)(int32_t)0x80000000) return 36;
    if (*low != 0x7fffffffu) return 37;

    *low = 0x80000000u;
    if (amominu_w(low, 0x7fffffffu) != (int64_t)(int32_t)0x80000000) return 38;
    if (*low != 0x7fffffffu) return 39;

    *low = 0x80000000u;
    if (amomaxu_w(low, 0x7fffffffu) != (int64_t)(int32_t)0x80000000) return 40;
    if (*low != 0x80000000u) return 41;

    /* The upper half must have survived every .W access above. */
    if (*high != 0x77778888u) return 42;

    return 0;
}
#endif

/* ---- Secondary harts ----------------------------------------------------- */

void breeze_secondary_main(uint64_t hart)
{
#if BREEZE_ATOMIC_CASE == 2
    if (wait_value(&control[CTL_PHASE], 1) != 0) {
        return;
    }
    for (int i = 0; i < AMO_ITERATIONS; i++) {
        (void)amoadd_d(&shared_line[0], 1u);
        (void)amoadd_w((volatile uint32_t *)&shared_line[1], 1u);
    }
    (void)amoadd_d(&control[CTL_DONE], 1u);

#elif BREEZE_ATOMIC_CASE == 3
    if (wait_value(&control[CTL_PHASE], 1) != 0) {
        return;
    }
    for (int i = 0; i < LOCK_ITERATIONS; i++) {
        lock_acquire(&lock_word);
        /* Non-atomic read-modify-write, correct only under mutual exclusion. */
        guarded_counter = guarded_counter + 1u;
        lock_release(&lock_word);
    }
    (void)amoadd_d(&control[CTL_DONE], 1u);

#elif BREEZE_ATOMIC_CASE == 4
    if (hart != 1u) {
        return;
    }
    /* Wait until hart 0 holds its reservation, then write a *different word*
     * of the same 32 B line: the conservative line granule must still break
     * hart 0's reservation. */
    if (wait_value(&control[CTL_PHASE], 1) != 0) {
        return;
    }
    shared_line[2] = UINT64_C(0xa5a5a5a5a5a5a5a5);
    publish(&control[CTL_READY], 1);

#else
    (void)hart;
#endif
}

/* ---- Hart 0 -------------------------------------------------------------- */

int main(void)
{
#if BREEZE_ATOMIC_CASE == 1
    int status;

    breeze_uart_puts("AMO_DIRECTED: START\r\n");
    status = check_amo_d();
    if (status != 0) {
        breeze_uart_puts("AMO_DIRECTED: FAIL(D)\r\n");
        return status;
    }
    status = check_amo_w();
    if (status != 0) {
        breeze_uart_puts("AMO_DIRECTED: FAIL(W)\r\n");
        return status;
    }
    breeze_uart_puts("AMO_DIRECTED: PASS\r\n");
    return 0;

#elif BREEZE_ATOMIC_CASE == 2
    const uint64_t expected = (uint64_t)BREEZE_NUM_HARTS * AMO_ITERATIONS;

    breeze_uart_puts("AMO_CONTENTION: START\r\n");
    shared_line[0] = 0u;
    shared_line[1] = 0u;
    control[CTL_DONE] = 0u;
    publish(&control[CTL_PHASE], 1);

    for (int i = 0; i < AMO_ITERATIONS; i++) {
        (void)amoadd_d(&shared_line[0], 1u);
        (void)amoadd_w((volatile uint32_t *)&shared_line[1], 1u);
    }
    (void)amoadd_d(&control[CTL_DONE], 1u);

    if (wait_value(&control[CTL_DONE], BREEZE_NUM_HARTS) != 0) {
        breeze_uart_puts("AMO_CONTENTION: FAIL(sync)\r\n");
        return 1;
    }
    /* No lost updates in either width, and the .W half must not have spilled
     * into the neighbouring 32-bit half. */
    if (shared_line[0] != expected) {
        breeze_uart_puts("AMO_CONTENTION: FAIL(d-sum)\r\n");
        return 2;
    }
    if ((uint32_t)shared_line[1] != (uint32_t)expected) {
        breeze_uart_puts("AMO_CONTENTION: FAIL(w-sum)\r\n");
        return 3;
    }
    if ((shared_line[1] >> 32) != 0u) {
        breeze_uart_puts("AMO_CONTENTION: FAIL(w-spill)\r\n");
        return 4;
    }
    breeze_uart_puts("AMO_CONTENTION: PASS\r\n");
    return 0;

#elif BREEZE_ATOMIC_CASE == 3
    uint64_t old;
    const uint64_t expected = (uint64_t)BREEZE_NUM_HARTS * LOCK_ITERATIONS;

    breeze_uart_puts("LRSC_SUCCESS: START\r\n");

    /* Plain LR/SC round trip with no interference: must succeed and write. */
    shared_line[0] = UINT64_C(0x1234);
    if (lrsc_d(&shared_line[0], UINT64_C(0x5678), &old) != 0) {
        breeze_uart_puts("LRSC_SUCCESS: FAIL(sc)\r\n");
        return 1;
    }
    if (old != UINT64_C(0x1234) || shared_line[0] != UINT64_C(0x5678)) {
        breeze_uart_puts("LRSC_SUCCESS: FAIL(value)\r\n");
        return 2;
    }

    /* An SC without a preceding LR must fail and must not write. */
    shared_line[0] = UINT64_C(0x5678);
    {
        uint64_t status;
        volatile uint64_t *addr = &shared_line[0];
        __asm__ volatile ("sc.d %0, %2, (%1)"
                          : "=&r"(status)
                          : "r"(addr), "r"(UINT64_C(0xdead))
                          : "memory");
        if (status == 0u) {
            breeze_uart_puts("LRSC_SUCCESS: FAIL(bare-sc)\r\n");
            return 3;
        }
        if (shared_line[0] != UINT64_C(0x5678)) {
            breeze_uart_puts("LRSC_SUCCESS: FAIL(bare-sc-wrote)\r\n");
            return 4;
        }
    }

    /* LR/SC spinlock across every hart. */
    lock_word = 0u;
    guarded_counter = 0u;
    control[CTL_DONE] = 0u;
    publish(&control[CTL_PHASE], 1);

    for (int i = 0; i < LOCK_ITERATIONS; i++) {
        lock_acquire(&lock_word);
        guarded_counter = guarded_counter + 1u;
        lock_release(&lock_word);
    }
    (void)amoadd_d(&control[CTL_DONE], 1u);

    if (wait_value(&control[CTL_DONE], BREEZE_NUM_HARTS) != 0) {
        breeze_uart_puts("LRSC_SUCCESS: FAIL(sync)\r\n");
        return 5;
    }
    if (guarded_counter != expected) {
        breeze_uart_puts("LRSC_SUCCESS: FAIL(mutex)\r\n");
        return 6;
    }
    breeze_uart_puts("LRSC_SUCCESS: PASS\r\n");
    return 0;

#elif BREEZE_ATOMIC_CASE == 4
    int status;

    breeze_uart_puts("LRSC_FAIL: START\r\n");
    shared_line[0] = UINT64_C(0x1111);
    shared_line[2] = UINT64_C(0x2222);
    control[CTL_READY] = 0u;
    publish(&control[CTL_PHASE], 1);

    /* Reserve word 0, wait for hart 1's write to word 2 of the same line,
     * then attempt the SC. Every instruction between the LR and the SC is a
     * load or a branch, so only the remote write can break the reservation. */
    status = lrsc_d_after_flag(&shared_line[0], UINT64_C(0xdead),
                               &control[CTL_READY], SPIN_LIMIT);
    if (status < 0) {
        breeze_uart_puts("LRSC_FAIL: FAIL(sync)\r\n");
        return 1;
    }
    if (status == 0) {
        breeze_uart_puts("LRSC_FAIL: FAIL(sc-succeeded)\r\n");
        return 2;
    }
    /* A failed SC must not have written memory. */
    if (shared_line[0] != UINT64_C(0x1111)) {
        breeze_uart_puts("LRSC_FAIL: FAIL(sc-wrote)\r\n");
        return 3;
    }
    /* The interfering write itself must be coherently visible. */
    if (shared_line[2] != UINT64_C(0xa5a5a5a5a5a5a5a5)) {
        breeze_uart_puts("LRSC_FAIL: FAIL(remote-write)\r\n");
        return 4;
    }

    /* After the failure a fresh LR/SC on the same address must succeed. */
    {
        uint64_t old;
        if (lrsc_d(&shared_line[0], UINT64_C(0x3333), &old) != 0) {
            breeze_uart_puts("LRSC_FAIL: FAIL(retry)\r\n");
            return 5;
        }
        if (old != UINT64_C(0x1111) || shared_line[0] != UINT64_C(0x3333)) {
            breeze_uart_puts("LRSC_FAIL: FAIL(retry-value)\r\n");
            return 6;
        }
    }
    breeze_uart_puts("LRSC_FAIL: PASS\r\n");
    return 0;

#else
#error "unknown BREEZE_ATOMIC_CASE"
#endif
}
