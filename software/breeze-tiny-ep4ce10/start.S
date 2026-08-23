    .section .text.start, "ax", @progbits
    .globl _start
    .type _start, @function
_start:
    la      sp, _stack_top

    la      t0, tiny_trap_vector
    csrw    mtvec, t0

    /* Copy initialized data from its ROM load image to SRAM. */
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

    /* Clear BSS. */
2:
    la      t0, _sbss
    la      t1, _ebss
3:
    bgeu    t0, t1, 4f
    sd      zero, 0(t0)
    addi    t0, t0, 8
    j       3b

4:
    call    main
5:
    wfi
    j       5b
    .size _start, . - _start
