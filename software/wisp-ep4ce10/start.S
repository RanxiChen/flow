    .option norvc
    .option norelax
    .section .text.start, "ax", @progbits
    .globl _start
    .type _start, @function

    .equ UART_RXTX,   0x12001000
    .equ UART_TXFULL, 0x12001004

_start:
    li      t0, UART_TXFULL
    li      t1, UART_RXTX

1:  li      t2, 1000
2:  addi    t2, t2, -1
    bnez    t2, 2b

    /* A complete software-controlled UART message, repeated forever. */
    li      t3, 'W'
    jal     ra, putc
    li      t3, 'I'
    jal     ra, putc
    li      t3, 'S'
    jal     ra, putc
    li      t3, 'P'
    jal     ra, putc
    li      t3, '\r'
    jal     ra, putc
    li      t3, '\n'
    jal     ra, putc
    jal     zero, 1b

putc:
3:  lw      t4, 0(t0)
    bnez    t4, 3b
    sw      t3, 0(t1)
    jalr    zero, 0(ra)

    .size _start, . - _start
