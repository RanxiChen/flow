    .section .text.init
    .option norvc
    .globl _start
_start:
    csrr t0, mhartid
    bnez t0, .Lsecondary
    li   t0, 0x80000000
    jr   t0

.Lsecondary:
    j    .Lsecondary
