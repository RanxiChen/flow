#ifndef BREEZE_MTVEC_MODE
#define BREEZE_MTVEC_MODE 0
#endif

#if BREEZE_MTVEC_MODE != 0 && BREEZE_MTVEC_MODE != 1
#error "BREEZE_MTVEC_MODE must be 0 (Direct) or 1 (Vectored)"
#endif

/* Multi-hart startup layout (spec section 20):
 *   - every hart enters _start from the same reset vector;
 *   - sp = _stack_top - mhartid * (1 << BREEZE_STACK_SHIFT), so each hart
 *     owns a non-overlapping 32 KiB stack slice below the SRAM top;
 *   - hart 0 alone copies .data and clears .bss, then publishes the boot
 *     barrier magic;
 *   - secondary harts spin on the linker-reserved barrier slot until hart 0
 *     publishes the magic.  The current LiteX simulation SRAM is initialized
 *     to zero; the linker keeps application data and stacks away from it.
 */
#define BREEZE_STACK_SHIFT  15
#define BREEZE_BOOT_MAGIC   0xb00210c0ffee1234

    .section .text.start, "ax", @progbits
    .globl _start
    .type _start, @function
_start:
    csrr    t0, mhartid
    slli    t1, t0, BREEZE_STACK_SHIFT
    la      sp, _stack_top
    sub     sp, sp, t1

    la      t0, breeze_trap_vector
#if BREEZE_MTVEC_MODE == 1
    ori     t0, t0, 1
#endif
    csrw    mtvec, t0

    csrr    t0, mhartid
    bnez    t0, 4f

    /* Hart 0 only: copy initialized writable data from ROM to SRAM. */
    la      t0, _sidata
    la      t1, _sdata
    la      t2, _edata
1:
    bgeu    t1, t2, 2f
    ld      t3, 0(t0)
    sd      t3, 0(t1)
    addi    t0, t0, 8
    addi    t1, t1, 8
    j       1b

    /* Hart 0 only: clear BSS in SRAM. */
2:
    la      t0, _sbss
    la      t1, _ebss
3:
    bgeu    t0, t1, 5f
    sd      zero, 0(t0)
    addi    t0, t0, 8
    j       3b

    /* Secondary harts: wait for hart 0's .data/.bss publication. */
4:
    la      t0, _breeze_boot_barrier
    li      t1, BREEZE_BOOT_MAGIC
1:
    ld      t2, 0(t0)
    bne     t2, t1, 1b
    j       6f

    /* Hart 0: make the initialized sections globally visible before the
     * barrier release (strictly ordered core; the fence documents the
     * publication intent). */
5:
    fence   iorw, w
    la      t0, _breeze_boot_barrier
    li      t1, BREEZE_BOOT_MAGIC
    sd      t1, 0(t0)

6:
    call    breeze_runtime_entry
7:
    j       7b
    .size _start, . - _start
