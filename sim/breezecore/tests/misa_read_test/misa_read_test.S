    .section .text
    .globl _start

_start:
    csrr    x1, misa        # 读取 misa CSR 到 x1
    .word   0x7ff00073      # estop，通知仿真器结束
