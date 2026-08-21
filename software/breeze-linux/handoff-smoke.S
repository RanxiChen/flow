    .section .text.init
    .option norvc
    .globl _start
_start:
    li   t0, 0x13000000
    li   t1, 'K'
    sb   t1, 0(t0)

1:
    wfi
    j    1b
