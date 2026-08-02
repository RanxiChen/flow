    /*
     * The table is 64-byte aligned and contains one 4-byte jump per cause.
     * Direct mode enters slot zero; Vectored mode enters BASE + 4*cause.
     */
    .section .text.trap_vector, "ax", @progbits
    .balign 64
    .globl breeze_trap_vector
    .type breeze_trap_vector, @function
breeze_trap_vector:
    .rept 16
    j       breeze_trap_entry
    .endr
    .size breeze_trap_vector, . - breeze_trap_vector

    .section .text.trap_entry, "ax", @progbits
    .balign 4
    .globl breeze_trap_entry
    .type breeze_trap_entry, @function
breeze_trap_entry:
    /* Preserve every integer register that a C handler may clobber. */
    addi    sp, sp, -256
    sd      ra,   0(sp)
    sd      gp,   8(sp)
    sd      tp,  16(sp)
    sd      t0,  24(sp)
    sd      t1,  32(sp)
    sd      t2,  40(sp)
    sd      s0,  48(sp)
    sd      s1,  56(sp)
    sd      a0,  64(sp)
    sd      a1,  72(sp)
    sd      a2,  80(sp)
    sd      a3,  88(sp)
    sd      a4,  96(sp)
    sd      a5, 104(sp)
    sd      a6, 112(sp)
    sd      a7, 120(sp)
    sd      s2, 128(sp)
    sd      s3, 136(sp)
    sd      s4, 144(sp)
    sd      s5, 152(sp)
    sd      s6, 160(sp)
    sd      s7, 168(sp)
    sd      s8, 176(sp)
    sd      s9, 184(sp)
    sd      s10, 192(sp)
    sd      s11, 200(sp)
    sd      t3, 208(sp)
    sd      t4, 216(sp)
    sd      t5, 224(sp)
    sd      t6, 232(sp)

    csrr    a0, mcause
    csrr    a1, mepc
    csrr    a2, mtval
    call    breeze_trap_handler

    ld      ra,   0(sp)
    ld      gp,   8(sp)
    ld      tp,  16(sp)
    ld      t0,  24(sp)
    ld      t1,  32(sp)
    ld      t2,  40(sp)
    ld      s0,  48(sp)
    ld      s1,  56(sp)
    ld      a0,  64(sp)
    ld      a1,  72(sp)
    ld      a2,  80(sp)
    ld      a3,  88(sp)
    ld      a4,  96(sp)
    ld      a5, 104(sp)
    ld      a6, 112(sp)
    ld      a7, 120(sp)
    ld      s2, 128(sp)
    ld      s3, 136(sp)
    ld      s4, 144(sp)
    ld      s5, 152(sp)
    ld      s6, 160(sp)
    ld      s7, 168(sp)
    ld      s8, 176(sp)
    ld      s9, 184(sp)
    ld      s10, 192(sp)
    ld      s11, 200(sp)
    ld      t3, 208(sp)
    ld      t4, 216(sp)
    ld      t5, 224(sp)
    ld      t6, 232(sp)
    addi    sp, sp, 256
    mret
    .size breeze_trap_entry, . - breeze_trap_entry
