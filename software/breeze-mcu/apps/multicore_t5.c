#include <stdint.h>

#include "breeze/uart.h"

/* P4 "small" four-hart MSI oracle application.
 *
 * Every scenario uses ordinary RV64IM loads/stores only (no RV64A):
 * cache coherence, not atomics, must make the publications visible.
 * `fence rw,rw` only expresses release ordering; the actual cross-hart
 * visibility is provided by the coherence protocol these tests exist to
 * prove.
 *
 * Shared-memory discipline:
 *   - every control field has exactly one writer: hart 0 owns `phase`;
 *     hart N owns ready_slots[N], done_slots[N] and result_values[N];
 *   - the shared payload line is written by exactly one hart in each
 *     phase (line-granule ownership transfer is part of the test);
 *   - every spin loop has a finite limit and each timeout maps to a
 *     distinct error code;
 *   - hart 0 aggregates the per-hart slots, prints expected/actual masks
 *     and the key expected/actual value, and returns non-zero on any
 *     mismatch, so the runtime magic word -- and therefore the runner
 *     completion marker -- reflects a real firmware verdict.
 */
#ifndef BREEZE_NUM_HARTS
#error "BREEZE_NUM_HARTS must be defined"
#endif
#if BREEZE_NUM_HARTS != 4
#error "P4 T5 firmware is defined only for the small (4-hart) profile"
#endif
#ifndef BREEZE_T5_CASE
#error "BREEZE_T5_CASE must select one directed small-MSI test"
#endif
#ifndef BREEZE_L2_BYTES
#error "BREEZE_L2_BYTES must be provided by the runner (frozen L2 formula)"
#endif

#define VALUE_A UINT64_C(0x1122334455667788)
#define VALUE_B UINT64_C(0x8877665544332211)
#define VALUE_C UINT64_C(0x0f1e2d3c4b5a6978)
#define VALUE_D UINT64_C(0xf0e1d2c3b4a59687)
#define SPIN_LIMIT UINT64_C(400000)

/* Distinct timeout codes, one per spin site. */
#define TIMEOUT_C1_PHASE  UINT64_C(0xf001)  /* any hart waiting phase 1 */
#define TIMEOUT_C2_PHASE1 UINT64_C(0xf002)  /* secondary case 2, phase 1 */
#define TIMEOUT_C2_PHASE2 UINT64_C(0xf003)  /* secondary case 2, phase 2 */
#define TIMEOUT_C3_PHASE1 UINT64_C(0xf004)  /* hart 3, phase 1 */
#define TIMEOUT_C3_PHASE2 UINT64_C(0xf005)  /* hart 1, phase 2 */
#define TIMEOUT_C3_PHASE3 UINT64_C(0xf006)  /* hart 2, phase 3 */
#define TIMEOUT_C4_PHASE  UINT64_C(0xf007)  /* case 4 word writer */
#define TIMEOUT_C5_PHASE1 UINT64_C(0xf008)  /* case 5 racer, phase 1 */

/* Each slot array occupies its own 32-byte-aligned line; `phase` gets one
 * whole line to itself so polling traffic does not fight publication
 * traffic on the same granule. */
static volatile uint64_t phase __attribute__((aligned(32)));
static volatile uint64_t ready_slots[4] __attribute__((aligned(32)));
static volatile uint64_t done_slots[4] __attribute__((aligned(32)));
static volatile uint64_t result_values[4] __attribute__((aligned(32)));
static volatile uint64_t payload[4] __attribute__((aligned(32)));

static inline void publish(volatile uint64_t *slot, uint64_t value)
{
    *slot = value;
    /* Release ordering only: coherence provides the cross-hart visibility. */
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

static int wait_slots_mask(volatile uint64_t *slots, uint64_t mask, uint64_t value)
{
    uint64_t left = SPIN_LIMIT;
    for (;;) {
        uint64_t seen = 0;
        unsigned hart;
        for (hart = 1; hart < BREEZE_NUM_HARTS; hart++) {
            if (slots[hart] == value) {
                seen |= UINT64_C(1) << hart;
            }
        }
        if ((seen & mask) == mask) {
            return 0;
        }
        if (--left == 0u) {
            return -1;
        }
    }
}

static uint64_t collect_done_mask(void)
{
    uint64_t seen = 0;
    unsigned hart;
    for (hart = 1; hart < BREEZE_NUM_HARTS; hart++) {
        if (done_slots[hart] == 1u) {
            seen |= UINT64_C(1) << hart;
        }
    }
    return seen;
}

static uint64_t collect_all_done_mask(void)
{
    uint64_t seen = 0;
    unsigned hart;
    for (hart = 0; hart < BREEZE_NUM_HARTS; hart++) {
        if (done_slots[hart] == 1u) {
            seen |= UINT64_C(1) << hart;
        }
    }
    return seen;
}

/* Secondary-hart failure: publish the timeout code, then the done flag. */
static void secondary_fail(uint64_t hart, uint64_t code)
{
    result_values[hart] = code;
    publish(&done_slots[hart], 1);
}

static void t5_secondary_1(uint64_t hart)
{
    if (wait_value(&phase, 1) != 0) {
        secondary_fail(hart, TIMEOUT_C1_PHASE);
        return;
    }
    result_values[hart] = payload[0];
    publish(&done_slots[hart], 1);
}

static void t5_secondary_2(uint64_t hart)
{
    if (wait_value(&phase, 1) != 0) {
        secondary_fail(hart, TIMEOUT_C2_PHASE1);
        return;
    }
    result_values[hart] = payload[0];
    publish(&ready_slots[hart], 1);
    if (wait_value(&phase, 2) != 0) {
        result_values[hart] = TIMEOUT_C2_PHASE2;
        publish(&done_slots[hart], 1);
        return;
    }
    result_values[hart] = payload[0];
    publish(&done_slots[hart], 1);
}

static void t5_secondary_3(uint64_t hart)
{
    if (hart == 3u) {
        if (wait_value(&phase, 1) != 0) {
            secondary_fail(3, TIMEOUT_C3_PHASE1);
            return;
        }
        payload[0] = VALUE_C;  /* hart 3 becomes the dirty M owner */
        publish(&done_slots[3], 1);
    } else if (hart == 1u) {
        if (wait_value(&phase, 2) != 0) {
            secondary_fail(1, TIMEOUT_C3_PHASE2);
            return;
        }
        payload[0] = VALUE_B;  /* GetM: full-line recall from hart 3 */
        publish(&done_slots[1], 1);
    } else { /* hart 2 */
        if (wait_value(&phase, 3) != 0) {
            secondary_fail(2, TIMEOUT_C3_PHASE3);
            return;
        }
        result_values[2] = payload[0];  /* GetS of the latest dirty line */
        publish(&done_slots[2], 1);
    }
}

static void t5_secondary_4(uint64_t hart)
{
    uint64_t ph = (hart == 1u) ? 1u : (hart == 2u) ? 2u : 3u;
    if (wait_value(&phase, ph) != 0) {
        secondary_fail(hart, TIMEOUT_C4_PHASE);
        return;
    }
    payload[hart] = VALUE_A + hart;  /* word writer for this line */
    publish(&done_slots[hart], 1);
}

static void t5_secondary_5(uint64_t hart)
{
    publish(&ready_slots[hart], 1);
    if (wait_value(&phase, 1) != 0) {
        secondary_fail(hart, TIMEOUT_C5_PHASE1);
        return;
    }
    result_values[hart] = payload[hart];
    publish(&done_slots[hart], 1);
}

static void t5_secondary_6(uint64_t hart)
{
    /* The eviction pattern runs on hart 0; secondaries only prove that all
     * four harts booted and parked through the shared startup path. */
    publish(&done_slots[hart], 1);
}

void breeze_secondary_main(uint64_t hart)
{
    if (hart == 0u || hart >= BREEZE_NUM_HARTS) {
        return;
    }
#if BREEZE_T5_CASE == 1
    t5_secondary_1(hart);
#elif BREEZE_T5_CASE == 2
    t5_secondary_2(hart);
#elif BREEZE_T5_CASE == 3
    t5_secondary_3(hart);
#elif BREEZE_T5_CASE == 4
    t5_secondary_4(hart);
#elif BREEZE_T5_CASE == 5
    t5_secondary_5(hart);
#elif BREEZE_T5_CASE == 6
    t5_secondary_6(hart);
#else
#error "unknown BREEZE_T5_CASE"
#endif
}

/* UART is expensive in simulation (~87 cycles/char): keep the report short
 * so the eviction-heavy case stays inside the completion watchdog. */
static void put_hex_n(uint64_t value, unsigned digits)
{
    unsigned i;
    for (i = 0; i < digits; ++i) {
        unsigned shift = (digits - 1u - i) * 4u;
        unsigned nibble = (unsigned)((value >> shift) & 0xfu);
        char c = (char)(nibble < 10u ? '0' + nibble : 'a' + nibble - 10u);
        breeze_uart_putc(c);
    }
}

static void report(uint64_t exp_mask, uint64_t act_mask, uint64_t exp_val, uint64_t act_val)
{
    breeze_uart_puts("T5-");
    put_hex_n(BREEZE_T5_CASE, 1);
    breeze_uart_puts(" m=");
    put_hex_n(exp_mask, 2);
    breeze_uart_puts("/");
    put_hex_n(act_mask, 2);
    breeze_uart_puts(" v=");
    breeze_uart_put_hex64(exp_val);
    breeze_uart_puts("/");
    breeze_uart_put_hex64(act_val);
    breeze_uart_puts("\r\n");
}

int main(void)
{
#if BREEZE_T5_CASE == 1 /* four-way sharing of one published value */
    payload[0] = VALUE_A;
    if (payload[0] != VALUE_A) return 11;
    publish(&phase, 1);
    if (wait_slots_mask(done_slots, 0xe, 1) != 0) return 12;
    if (result_values[1] != VALUE_A || result_values[2] != VALUE_A ||
        result_values[3] != VALUE_A) return 13;
    if (payload[0] != VALUE_A) return 14;
    report(0xe, collect_done_mask(), VALUE_A, result_values[1]);

#elif BREEZE_T5_CASE == 2 /* four-way share, then S->M upgrade */
    payload[0] = VALUE_A;
    publish(&phase, 1);
    if (wait_slots_mask(ready_slots, 0xe, 1) != 0) return 21;
    if (result_values[1] != VALUE_A || result_values[2] != VALUE_A ||
        result_values[3] != VALUE_A) return 22;
    /* Harts 1..3 hold S here.  This store must wait for all three InvAcks
     * before the line becomes visible with the new value. */
    payload[0] = VALUE_B;
    publish(&phase, 2);
    if (wait_slots_mask(done_slots, 0xe, 1) != 0) return 23;
    if (result_values[1] != VALUE_B || result_values[2] != VALUE_B ||
        result_values[3] != VALUE_B) return 24;
    if (payload[0] != VALUE_B) return 25;
    report(0xe, collect_done_mask(), VALUE_B, result_values[1]);

#elif BREEZE_T5_CASE == 3 /* dirty owner hart3 -> hart1 takeover -> hart2 read */
    payload[0] = VALUE_A;
    publish(&phase, 1);
    if (wait_slots_mask(done_slots, 1u << 3, 1) != 0) return 31;
    publish(&phase, 2);
    if (wait_slots_mask(done_slots, 1u << 1, 1) != 0) return 32;
    publish(&phase, 3);
    if (wait_slots_mask(done_slots, 1u << 2, 1) != 0) return 33;
    if (result_values[2] != VALUE_B) return 34;
    /* Hart 0's stale S copy was invalidated by hart 3's store; the re-read
     * must obtain the latest value through the coherence path. */
    if (payload[0] != VALUE_B) return 35;
    report(0xe, collect_done_mask(), VALUE_B, result_values[2]);

#elif BREEZE_T5_CASE == 4 /* one 32 B line, one word writer per hart */
    payload[0] = VALUE_A;
    payload[1] = 0;
    payload[2] = 0;
    payload[3] = 0;
    publish(&phase, 1);
    if (wait_slots_mask(done_slots, 1u << 1, 1) != 0) return 41;
    publish(&phase, 2);
    if (wait_slots_mask(done_slots, 1u << 2, 1) != 0) return 42;
    publish(&phase, 3);
    if (wait_slots_mask(done_slots, 1u << 3, 1) != 0) return 43;
    /* Every word store transferred the whole line: all four words survive. */
    if (payload[0] != VALUE_A || payload[1] != VALUE_A + 1u ||
        payload[2] != VALUE_A + 2u || payload[3] != VALUE_A + 3u) return 44;
    report(0xe, collect_done_mask(), VALUE_A + 1u, payload[1]);

#elif BREEZE_T5_CASE == 5 /* four harts race on the same line from a barrier */
    payload[0] = VALUE_A;
    payload[1] = VALUE_B;
    payload[2] = VALUE_C;
    payload[3] = VALUE_D;
    if (wait_slots_mask(ready_slots, 0xe, 1) != 0) return 51;
    publish(&phase, 1);
    /* All four harts now request the same line concurrently; each reports
     * its own word from that line. */
    result_values[0] = payload[0];
    publish(&done_slots[0], 1);
    if (wait_slots_mask(done_slots, 0xe, 1) != 0) return 52;
    if (result_values[0] != VALUE_A || result_values[1] != VALUE_B ||
        result_values[2] != VALUE_C || result_values[3] != VALUE_D) return 53;
    report(0xf, collect_all_done_mask(), VALUE_B, result_values[1]);

#elif BREEZE_T5_CASE == 6 /* dirty data integrity under the 64 KiB L2 geometry */
    {
        /* Set stride = L2 capacity / ways; small = 65536 / 8 = 8192, which
         * keeps the L2 set index (and therefore the L1D set) constant. */
        const uint64_t stride = (uint64_t)BREEZE_L2_BYTES / 8u;
        const unsigned num_sets = 4u;
        const unsigned lines_per_set = 12u; /* > 8 ways: forces L2 eviction */
        unsigned set, index;
        for (set = 0; set < num_sets; ++set) {
            for (index = 0; index < lines_per_set; ++index) {
                volatile uint64_t *line = (volatile uint64_t *)(
                    UINT64_C(0x80000000) + (uint64_t)set * 32u +
                    (uint64_t)index * stride);
                *line = 0xe71ca11000000000ull |
                    ((uint64_t)set << 8) | (uint64_t)index;
            }
        }
        for (set = 0; set < num_sets; ++set) {
            for (index = 0; index < lines_per_set; ++index) {
                volatile uint64_t *line = (volatile uint64_t *)(
                    UINT64_C(0x80000000) + (uint64_t)set * 32u +
                    (uint64_t)index * stride);
                uint64_t expect = 0xe71ca11000000000ull |
                    ((uint64_t)set << 8) | (uint64_t)index;
                if (*line != expect) return 61;
            }
        }
        if (wait_slots_mask(done_slots, 0xe, 1) != 0) return 62;
        report(0xe, collect_done_mask(), 0, 0);
    }
#else
#error "unknown BREEZE_T5_CASE"
#endif

    return 0;
}
