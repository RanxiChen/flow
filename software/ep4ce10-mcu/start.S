    .option norvc
    .option norelax
    .section .text.start, "ax", @progbits
    .globl _start
    .type _start, @function

_start:
    la      sp, __stack_top

    la      t0, __data_load_start
    la      t1, __data_start
    la      t2, __data_end
1:  bgeu    t1, t2, 2f
    lbu     t3, 0(t0)
    sb      t3, 0(t1)
    addi    t0, t0, 1
    addi    t1, t1, 1
    jal     zero, 1b

2:  la      t0, __bss_start
    la      t1, __bss_end
3:  bgeu    t0, t1, 4f
    sb      zero, 0(t0)
    addi    t0, t0, 1
    jal     zero, 3b

4:  la      t0, trap_entry
    csrw    mtvec, t0
    call    main

5:  wfi
    jal     zero, 5b

    .size _start, . - _start
