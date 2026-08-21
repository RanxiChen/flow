    .section .text.init
    .option norvc
    .globl _start
_start:
    csrr a0, mhartid
    li   a1, 0x80100000
    li   t0, 0x80000000
    jr   t0

