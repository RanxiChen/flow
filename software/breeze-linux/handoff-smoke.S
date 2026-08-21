    .section .text.init
    .option norvc
    .globl _start
_start:
    li   t0, 0x12001000
1:
    lbu  t2, 4(t0)
    bnez t2, 1b
    li   t1, 'K'
    sb   t1, 0(t0)

2:
    wfi
    j    2b
