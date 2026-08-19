#include <stdint.h>

#include "breeze/csr.h"
#include "breeze/platform.h"
#include "breeze/runtime.h"
#include "breeze/uart.h"

/* CLINT / IPI / remote FENCE.I firmware (spec sections 13, 19; tests T8/T9).
 *
 * Case 1 (ipi)            : hart 0 raises msip[target] for each hart in turn.
 *                           The target takes a machine software interrupt
 *                           (mcause = 0x8000_0000_0000_0003), clears its own
 *                           msip and acknowledges. Only the addressed hart may
 *                           react, which is what proves msip[0] and msip[1]
 *                           do not alias inside their shared 64-bit bus word.
 * Case 2 (remote-fencei)  : hart 0 rewrites a function that lives in writable,
 *                           executable SRAM and notifies the target by IPI.
 *                           The target executes FENCE.I inside the handler and
 *                           must then observe the *new* instruction. A control
 *                           call before the patch pulls the old encoding into
 *                           the target's I$, so a missing FENCE.I (or a
 *                           missing L2 recall of hart 0's dirty line) fails.
 * Case 3 (per-hart-timer) : phase A arms only the highest hart's mtimecmp and
 *                           requires that no other hart sees MTIP (a broadcast
 *                           mtip fails here); phase B arms every hart and each
 *                           must see exactly its own timer interrupt.
 */

#ifndef BREEZE_NUM_HARTS
#define BREEZE_NUM_HARTS 1
#endif

#ifndef BREEZE_IPI_CASE
#error "BREEZE_IPI_CASE must select one directed CLINT test"
#endif

#if BREEZE_NUM_HARTS != 1 && BREEZE_NUM_HARTS != 2 && BREEZE_NUM_HARTS != 4
#error "BREEZE_NUM_HARTS must be one of the frozen 1/2/4 profiles"
#endif

#define SPIN_LIMIT   UINT64_C(2000000)
#define TIMER_DELTA  UINT64_C(64)
#define QUIET_WINDOW UINT64_C(40000)

static volatile uint64_t ipi_seen[BREEZE_NUM_HARTS];
static volatile uint64_t timer_seen[BREEZE_NUM_HARTS];
static volatile uint64_t trap_error[BREEZE_NUM_HARTS];
static volatile uint64_t ack[BREEZE_NUM_HARTS];
static volatile uint64_t phase;
static volatile uint64_t stub_result[BREEZE_NUM_HARTS];
static volatile uint64_t fencei_done[BREEZE_NUM_HARTS];

/* Patchable stub in writable, executable SRAM (.data):
 *     li a0, 1      (addi a0, zero, 1)
 *     ret           (jalr zero, 0(ra))
 * Hart 0 rewrites the first instruction into "li a0, 2". */
#define STUB_LI_A0_1 UINT32_C(0x00100513)
#define STUB_LI_A0_2 UINT32_C(0x00200513)
#define STUB_RET     UINT32_C(0x00008067)

static volatile uint32_t code_stub[2] __attribute__((aligned(32))) = {
    STUB_LI_A0_1, STUB_RET,
};

typedef uint64_t (*stub_fn)(void);

static inline uint64_t read_mhartid(void)
{
    uint64_t value;
    __asm__ volatile ("csrr %0, mhartid" : "=r"(value));
    return value;
}

static inline volatile uint32_t *msip_of(uint64_t hart)
{
    return (volatile uint32_t *)(uintptr_t)(BREEZE_MSIP_ADDR + 4u * hart);
}

static inline volatile uint64_t *mtimecmp_of(uint64_t hart)
{
    return (volatile uint64_t *)(uintptr_t)(BREEZE_MTIMECMP_ADDR + 8u * hart);
}

static inline uint64_t read_mtime(void)
{
    return *(volatile uint64_t *)(uintptr_t)BREEZE_MTIME_ADDR;
}

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

static void spin_cycles(uint64_t cycles)
{
    uint64_t start = breeze_read_mcycle();
    while ((breeze_read_mcycle() - start) < cycles) {
        __asm__ volatile ("nop");
    }
}

/* ---- Trap handling ------------------------------------------------------- */

void breeze_trap_handler(uint64_t mcause, uint64_t mepc, uint64_t mtval)
{
    uint64_t hart = read_mhartid();

    (void)mepc;
    (void)mtval;

    if (mcause == BREEZE_MCAUSE_SOFTWARE) {
        /* MSIP is level-sensitive and read-only in mip: the only way out of
         * the handler is to clear this hart's own msip in the CLINT. */
        *msip_of(hart) = 0u;
        __asm__ volatile ("fence rw, rw" ::: "memory");
#if BREEZE_IPI_CASE == 2
        /* Remote code modification: only the local FENCE.I makes hart 0's
         * newly written instructions visible to this hart's fetch. */
        __asm__ volatile ("fence.i" ::: "memory");
        fencei_done[hart] = 1u;
#endif
        ipi_seen[hart] = ipi_seen[hart] + 1u;
        return;
    }

    if (mcause == BREEZE_MCAUSE_TIMER) {
        /* MTIP is level-sensitive too: move mtimecmp out of reach first. */
        *mtimecmp_of(hart) = UINT64_MAX;
        breeze_clear_mie(BREEZE_MIE_MTIE);
        timer_seen[hart] = timer_seen[hart] + 1u;
        return;
    }

    trap_error[hart] = mcause;
    breeze_fail();
}

/* ---- Secondary harts ----------------------------------------------------- */

void breeze_secondary_main(uint64_t hart)
{
#if BREEZE_IPI_CASE == 1
    *msip_of(hart) = 0u;
    breeze_set_mie(BREEZE_MIE_MSIE);
    breeze_set_mstatus(BREEZE_MSTATUS_MIE);
    publish(&ack[hart], 1);

    /* One IPI, then acknowledge it to hart 0. */
    {
        uint64_t left = SPIN_LIMIT;
        while (ipi_seen[hart] == 0u) {
            if (--left == 0u) {
                return;
            }
        }
    }
    publish(&ack[hart], 2);

#elif BREEZE_IPI_CASE == 2
    stub_fn stub = (stub_fn)(uintptr_t)code_stub;

    *msip_of(hart) = 0u;
    breeze_set_mie(BREEZE_MIE_MSIE);
    breeze_set_mstatus(BREEZE_MSTATUS_MIE);

    /* Control call: pulls the pre-patch encoding into this hart's I$. */
    if (stub() != 1u) {
        publish(&stub_result[hart], UINT64_C(0xbad0));
        return;
    }
    publish(&ack[hart], 1);

    /* Wait for the IPI whose handler runs the local FENCE.I. */
    {
        uint64_t left = SPIN_LIMIT;
        while (fencei_done[hart] == 0u) {
            if (--left == 0u) {
                publish(&stub_result[hart], UINT64_C(0xbad1));
                return;
            }
        }
    }
    /* After FENCE.I the patched instruction must be the one that executes. */
    publish(&stub_result[hart], stub());

#elif BREEZE_IPI_CASE == 3
    /* Phase A: armed for timer interrupts but with mtimecmp out of reach.
     * Only the highest hart arms itself, so any interrupt seen here proves a
     * broadcast mtip instead of a per-hart one. */
    *mtimecmp_of(hart) = UINT64_MAX;
    breeze_set_mie(BREEZE_MIE_MTIE);
    breeze_set_mstatus(BREEZE_MSTATUS_MIE);
    publish(&ack[hart], 1);

    if (wait_value(&phase, 1) != 0) {
        return;
    }
    if (hart == (uint64_t)(BREEZE_NUM_HARTS - 1)) {
        *mtimecmp_of(hart) = read_mtime() + TIMER_DELTA;
        {
            uint64_t left = SPIN_LIMIT;
            while (timer_seen[hart] == 0u) {
                if (--left == 0u) {
                    return;
                }
            }
        }
    }
    publish(&ack[hart], 2);

    /* Phase B: every hart arms its own compare and waits for its own MTI. */
    if (wait_value(&phase, 2) != 0) {
        return;
    }
    breeze_set_mie(BREEZE_MIE_MTIE);
    *mtimecmp_of(hart) = read_mtime() + TIMER_DELTA;
    {
        uint64_t expected = (hart == (uint64_t)(BREEZE_NUM_HARTS - 1)) ? 2u : 1u;
        uint64_t left = SPIN_LIMIT;
        while (timer_seen[hart] != expected) {
            if (--left == 0u) {
                return;
            }
        }
    }
    publish(&ack[hart], 3);

#else
    (void)hart;
#endif
}

/* ---- Hart 0 -------------------------------------------------------------- */

static int wait_all_acks(uint64_t value)
{
    for (int hart = 1; hart < BREEZE_NUM_HARTS; hart++) {
        if (wait_value(&ack[hart], value) != 0) {
            return -1;
        }
    }
    return 0;
}

int main(void)
{
#if BREEZE_IPI_CASE == 1
    breeze_uart_puts("IPI: START\r\n");

    for (int hart = 0; hart < BREEZE_NUM_HARTS; hart++) {
        *msip_of((uint64_t)hart) = 0u;
    }
    breeze_set_mie(BREEZE_MIE_MSIE);
    breeze_set_mstatus(BREEZE_MSTATUS_MIE);

    if (wait_all_acks(1) != 0) {
        breeze_uart_puts("IPI: FAIL(arm)\r\n");
        return 1;
    }

    /* Address each hart in turn, including hart 0 itself (self-IPI, the only
     * shape the single profile can exercise). After each one, every other
     * hart's counter must still be untouched: msip[0] and msip[1] share a
     * 64-bit bus word and must not alias. */
    for (int target = 0; target < BREEZE_NUM_HARTS; target++) {
        *msip_of((uint64_t)target) = 1u;
        __asm__ volatile ("fence rw, rw" ::: "memory");

        if (target == 0) {
            uint64_t left = SPIN_LIMIT;
            while (ipi_seen[0] != 1u) {
                if (--left == 0u) {
                    breeze_uart_puts("IPI: FAIL(self)\r\n");
                    return 2;
                }
            }
        } else if (wait_value(&ack[target], 2) != 0) {
            breeze_uart_puts("IPI: FAIL(ack)\r\n");
            return 3;
        }

        /* The addressed hart cleared its own msip inside the handler. */
        if (*msip_of((uint64_t)target) != 0u) {
            breeze_uart_puts("IPI: FAIL(msip-stuck)\r\n");
            return 4;
        }
        for (int other = 0; other < BREEZE_NUM_HARTS; other++) {
            uint64_t expected = (other <= target) ? 1u : 0u;
            if (ipi_seen[other] != expected) {
                breeze_uart_puts("IPI: FAIL(alias)\r\n");
                return 5;
            }
        }
    }

    for (int hart = 0; hart < BREEZE_NUM_HARTS; hart++) {
        if (trap_error[hart] != 0u) {
            breeze_uart_puts("IPI: FAIL(cause)\r\n");
            return 6;
        }
    }
    breeze_uart_puts("IPI: PASS\r\n");
    return 0;

#elif BREEZE_IPI_CASE == 2
    breeze_uart_puts("REMOTE_FENCEI: START\r\n");

    for (int hart = 0; hart < BREEZE_NUM_HARTS; hart++) {
        *msip_of((uint64_t)hart) = 0u;
    }
    breeze_set_mie(BREEZE_MIE_MSIE);
    breeze_set_mstatus(BREEZE_MSTATUS_MIE);

    if (wait_all_acks(1) != 0) {
        breeze_uart_puts("REMOTE_FENCEI: FAIL(arm)\r\n");
        return 1;
    }
    if (code_stub[0] != STUB_LI_A0_1 || code_stub[1] != STUB_RET) {
        breeze_uart_puts("REMOTE_FENCEI: FAIL(stub-init)\r\n");
        return 2;
    }

    /* Rewrite the instruction and publish it before signalling. */
    code_stub[0] = STUB_LI_A0_2;
    __asm__ volatile ("fence rw, rw" ::: "memory");

#if BREEZE_NUM_HARTS == 1
    /* Single profile: hart 0 is its own target (self-test). */
    *msip_of(0) = 1u;
    __asm__ volatile ("fence rw, rw" ::: "memory");
    {
        uint64_t left = SPIN_LIMIT;
        while (fencei_done[0] == 0u) {
            if (--left == 0u) {
                breeze_uart_puts("REMOTE_FENCEI: FAIL(self-ipi)\r\n");
                return 3;
            }
        }
    }
    {
        stub_fn stub = (stub_fn)(uintptr_t)code_stub;
        if (stub() != 2u) {
            breeze_uart_puts("REMOTE_FENCEI: FAIL(self-stale)\r\n");
            return 4;
        }
    }
#else
    for (int target = 1; target < BREEZE_NUM_HARTS; target++) {
        *msip_of((uint64_t)target) = 1u;
    }
    __asm__ volatile ("fence rw, rw" ::: "memory");

    for (int target = 1; target < BREEZE_NUM_HARTS; target++) {
        uint64_t left = SPIN_LIMIT;
        while (stub_result[target] == 0u) {
            if (--left == 0u) {
                breeze_uart_puts("REMOTE_FENCEI: FAIL(timeout)\r\n");
                return 3;
            }
        }
        if (fencei_done[target] != 1u) {
            breeze_uart_puts("REMOTE_FENCEI: FAIL(no-fencei)\r\n");
            return 4;
        }
        /* 1 means the target still fetched the stale instruction. */
        if (stub_result[target] != 2u) {
            breeze_uart_puts("REMOTE_FENCEI: FAIL(stale)\r\n");
            return 5;
        }
    }
#endif

    if (code_stub[0] != STUB_LI_A0_2) {
        breeze_uart_puts("REMOTE_FENCEI: FAIL(patch-lost)\r\n");
        return 6;
    }
    breeze_uart_puts("REMOTE_FENCEI: PASS\r\n");
    return 0;

#elif BREEZE_IPI_CASE == 3
    const uint64_t highest = (uint64_t)(BREEZE_NUM_HARTS - 1);

    breeze_uart_puts("PER_HART_TIMER: START\r\n");

    for (int hart = 0; hart < BREEZE_NUM_HARTS; hart++) {
        *mtimecmp_of((uint64_t)hart) = UINT64_MAX;
    }
    breeze_set_mie(BREEZE_MIE_MTIE);
    breeze_set_mstatus(BREEZE_MSTATUS_MIE);

    if (wait_all_acks(1) != 0) {
        breeze_uart_puts("PER_HART_TIMER: FAIL(arm)\r\n");
        return 1;
    }

    /* Phase A: only the highest hart arms itself. */
    publish(&phase, 1);
    if (highest == 0u) {
        *mtimecmp_of(0) = read_mtime() + TIMER_DELTA;
        {
            uint64_t left = SPIN_LIMIT;
            while (timer_seen[0] == 0u) {
                if (--left == 0u) {
                    breeze_uart_puts("PER_HART_TIMER: FAIL(self)\r\n");
                    return 2;
                }
            }
        }
    } else if (wait_all_acks(2) != 0) {
        breeze_uart_puts("PER_HART_TIMER: FAIL(phase-a)\r\n");
        return 3;
    }

    /* Give a broadcast mtip time to show up before declaring isolation. */
    spin_cycles(QUIET_WINDOW);
    if (timer_seen[highest] != 1u) {
        breeze_uart_puts("PER_HART_TIMER: FAIL(target-missed)\r\n");
        return 4;
    }
    for (int hart = 0; hart < BREEZE_NUM_HARTS; hart++) {
        if ((uint64_t)hart != highest && timer_seen[hart] != 0u) {
            breeze_uart_puts("PER_HART_TIMER: FAIL(broadcast)\r\n");
            return 5;
        }
    }

    /* Phase B: every hart arms its own compare. */
    publish(&phase, 2);
    breeze_set_mie(BREEZE_MIE_MTIE);
    *mtimecmp_of(0) = read_mtime() + TIMER_DELTA;
    {
        uint64_t expected = (highest == 0u) ? 2u : 1u;
        uint64_t left = SPIN_LIMIT;
        while (timer_seen[0] != expected) {
            if (--left == 0u) {
                breeze_uart_puts("PER_HART_TIMER: FAIL(phase-b-self)\r\n");
                return 6;
            }
        }
    }
    if (wait_all_acks(3) != 0) {
        breeze_uart_puts("PER_HART_TIMER: FAIL(phase-b)\r\n");
        return 7;
    }

    for (int hart = 0; hart < BREEZE_NUM_HARTS; hart++) {
        uint64_t expected = ((uint64_t)hart == highest) ? 2u : 1u;
        if (timer_seen[hart] != expected) {
            breeze_uart_puts("PER_HART_TIMER: FAIL(count)\r\n");
            return 8;
        }
        if (trap_error[hart] != 0u) {
            breeze_uart_puts("PER_HART_TIMER: FAIL(cause)\r\n");
            return 9;
        }
    }
    breeze_uart_puts("PER_HART_TIMER: PASS\r\n");
    return 0;

#else
#error "unknown BREEZE_IPI_CASE"
#endif
}
