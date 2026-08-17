#include <stdint.h>

#ifndef BREEZE_NUM_HARTS
#define BREEZE_NUM_HARTS 1
#endif

#ifndef BREEZE_T4_CASE
#error "BREEZE_T4_CASE must select one directed dual-MSI test"
#endif

#if BREEZE_NUM_HARTS != 2
#error "P3 T4 firmware is defined only for the dual profile"
#endif

/* Each field has one writer.  The tests deliberately use ordinary RV64IM
 * loads/stores: cache coherence, not RV64A, must make the publications
 * visible.  Aligning the payload gives the same-line cases an exact 32-byte
 * coherence granule. */
static volatile struct {
    uint64_t phase;          /* hart 0 writes */
    uint64_t ready;          /* hart 1 writes */
    uint64_t done;           /* hart 1 writes */
    uint64_t hart1_value;    /* hart 1 writes */
} control __attribute__((aligned(32)));

static volatile uint64_t payload[4] __attribute__((aligned(32)));

#define VALUE_A UINT64_C(0x1122334455667788)
#define VALUE_B UINT64_C(0x8877665544332211)
#define VALUE_C UINT64_C(0x0f1e2d3c4b5a6978)
#define SPIN_LIMIT UINT64_C(200000)

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

static void secondary_case(void)
{
#if BREEZE_T4_CASE == 1 /* sharing */
    if (wait_value(&control.phase, 1) != 0) return;
    control.hart1_value = payload[0];
    publish(&control.done, 1);

#elif BREEZE_T4_CASE == 2 /* S->M upgrade and invalidation */
    if (wait_value(&control.phase, 1) != 0) return;
    control.hart1_value = payload[0];
    publish(&control.ready, 1);
    if (wait_value(&control.phase, 2) != 0) return;
    control.hart1_value = payload[0];
    publish(&control.done, 1);

#elif BREEZE_T4_CASE == 3 /* dirty owner read */
    if (wait_value(&control.phase, 1) != 0) return;
    control.hart1_value = payload[0];
    publish(&control.done, 1);

#elif BREEZE_T4_CASE == 4 /* dirty owner transfer */
    if (wait_value(&control.phase, 1) != 0) return;
    payload[0] = VALUE_B;
    publish(&control.done, 1);

#elif BREEZE_T4_CASE == 5 /* same line, different words */
    if (wait_value(&control.phase, 1) != 0) return;
    payload[1] = VALUE_B;
    publish(&control.done, 1);

#elif BREEZE_T4_CASE == 6 /* concurrent same-line read requests */
    publish(&control.ready, 1);
    if (wait_value(&control.phase, 1) != 0) return;
    control.hart1_value = payload[1];
    publish(&control.done, 1);

#else
#error "unknown BREEZE_T4_CASE"
#endif
}

void breeze_secondary_main(uint64_t hart)
{
    if (hart == 1u) {
        secondary_case();
    }
}

int main(void)
{
#if BREEZE_T4_CASE == 1 /* Hart0 M -> Hart1 GetS, ending S/S. */
    payload[0] = VALUE_A;
    if (payload[0] != VALUE_A) return 11;
    publish(&control.phase, 1);
    if (wait_value(&control.done, 1) != 0) return 12;
    if (control.hart1_value != VALUE_A || payload[0] != VALUE_A) return 13;

#elif BREEZE_T4_CASE == 2
    payload[0] = VALUE_A;
    publish(&control.phase, 1);
    if (wait_value(&control.ready, 1) != 0) return 21;
    if (control.hart1_value != VALUE_A) return 22;
    /* Hart1 holds S here.  This store must wait for its InvAck. */
    payload[0] = VALUE_B;
    publish(&control.phase, 2);
    if (wait_value(&control.done, 1) != 0) return 23;
    if (control.hart1_value != VALUE_B) return 24;

#elif BREEZE_T4_CASE == 3
    /* Hart0 retains dirty M data until Hart1's GetS recalls it. */
    payload[0] = VALUE_C;
    publish(&control.phase, 1);
    if (wait_value(&control.done, 1) != 0) return 31;
    if (control.hart1_value != VALUE_C) return 32;

#elif BREEZE_T4_CASE == 4
    payload[0] = VALUE_A;
    publish(&control.phase, 1);
    if (wait_value(&control.done, 1) != 0) return 41;
    /* Hart1's GetM/store must transfer the dirty owner and invalidate us. */
    if (payload[0] != VALUE_B) return 42;

#elif BREEZE_T4_CASE == 5
    payload[0] = VALUE_A;
    payload[1] = 0;
    publish(&control.phase, 1);
    if (wait_value(&control.done, 1) != 0) return 51;
    /* The second-word store transfers the whole line without losing word 0. */
    if (payload[0] != VALUE_A || payload[1] != VALUE_B) return 52;

#elif BREEZE_T4_CASE == 6
    payload[0] = VALUE_A;
    payload[1] = VALUE_B;
    if (wait_value(&control.ready, 1) != 0) return 61;
    publish(&control.phase, 1);
    /* Both harts leave the start barrier and request the same line. */
    if (payload[0] != VALUE_A) return 62;
    if (wait_value(&control.done, 1) != 0) return 63;
    if (control.hart1_value != VALUE_B) return 64;
#endif

    return 0;
}
