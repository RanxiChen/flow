#include <stdint.h>

#include "breeze/uart.h"

/* T4 "boot": every hart enters the same _start, takes its own 32 KiB stack
 * slice below the SRAM top (start.S), passes the shared boot barrier, then
 * reports through its own completion slot. Hart 0 aggregates the slots and
 * only then lets the runtime report PASS, so the completion mask proves
 * that every hart really booted with a unique mhartid and a working,
 * non-overlapping stack.
 *
 * Slot words: zero means "not reported yet" (.bss is cleared by hart 0
 * before the barrier release), HART_DONE_WORD(h) is the success value and
 * HART_FAIL_WORD(h) | (status << 8) carries the self-check error code.
 */
#ifndef BREEZE_NUM_HARTS
#define BREEZE_NUM_HARTS 1
#endif

#define BREEZE_STACK_SHIFT 15                    /* keep in sync with start.S */
#define BREEZE_STACK_SIZE  (UINT64_C(1) << BREEZE_STACK_SHIFT)

#if BREEZE_NUM_HARTS < 1 || BREEZE_NUM_HARTS > 4
#error "BREEZE_NUM_HARTS must be one of the frozen 1/2/4 profiles"
#endif

extern char _stack_top[];

#define HART_DONE_WORD(hart) (UINT64_C(0xb007000000000000) | (hart))
#define HART_FAIL_WORD(hart) (UINT64_C(0xbad7000000000000) | (hart))

static volatile uint64_t boot_done[BREEZE_NUM_HARTS];

static uint64_t read_mhartid(void)
{
    uint64_t value;
    __asm__ volatile ("csrr %0, mhartid" : "=r"(value));
    return value;
}

static uint64_t read_sp(void)
{
    uint64_t value;
    __asm__ volatile ("mv %0, sp" : "=r"(value));
    return value;
}

/* Runs on the hart's own stack; returns 0 or a small error code. */
static uint64_t hart_self_check(uint64_t hart)
{
    volatile uint64_t canary[2];
    uint64_t sp = read_sp();
    uint64_t slice_top = (uint64_t)(uintptr_t)_stack_top - hart * BREEZE_STACK_SIZE;
    uint64_t pattern = UINT64_C(0x5aa55aa55aa55aa5) ^ hart;

    if (hart >= BREEZE_NUM_HARTS) {
        return 1;
    }
    if (sp > slice_top || sp <= slice_top - BREEZE_STACK_SIZE) {
        return 2;
    }
    canary[0] = pattern;
    canary[1] = ~pattern;
    if (canary[0] != pattern || canary[1] != ~pattern) {
        return 3;
    }
    return 0;
}

static void hart_report(uint64_t hart)
{
    uint64_t status = hart_self_check(hart);

    if (hart >= BREEZE_NUM_HARTS) {
        return;
    }
    if (status == 0u) {
        boot_done[hart] = HART_DONE_WORD(hart);
    } else {
        boot_done[hart] = HART_FAIL_WORD(hart) | (status << 8);
    }
}

void breeze_secondary_main(uint64_t hart)
{
    hart_report(hart);
}

int main(void)
{
    uint64_t expected_mask = (UINT64_C(1) << BREEZE_NUM_HARTS) - 1u;
    uint64_t done_mask;
    uint64_t fail_mask;
    uint64_t pending;
    unsigned hart;

    hart_report(read_mhartid());

    for (;;) {
        done_mask = 0;
        fail_mask = 0;
        pending = 0;
        for (hart = 0; hart < BREEZE_NUM_HARTS; hart++) {
            uint64_t word = boot_done[hart];

            if (word == 0u) {
                pending = 1;
            } else if (word == HART_DONE_WORD(hart)) {
                done_mask |= UINT64_C(1) << hart;
            } else {
                fail_mask |= UINT64_C(1) << hart;
            }
        }
        if (pending == 0u) {
            break;
        }
    }

    breeze_uart_puts("MC-BOOT exp=");
    breeze_uart_put_hex64(expected_mask);
    breeze_uart_puts(" act=");
    breeze_uart_put_hex64(done_mask);
    breeze_uart_puts(" fail=");
    breeze_uart_put_hex64(fail_mask);
    breeze_uart_puts("\r\n");

    if (done_mask != expected_mask || fail_mask != 0u) {
        return 1;
    }
    return 0;
}
