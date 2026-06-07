    .section .text
    .globl _start

# ======================================================================
# 测试: 解码器是否错误地将 0x00000000 当作非法指令
#
# RISC-V 中 0x00000000 是 add x0, x0, x0，属于合法的 R-type 指令
# 如果解码器把它判为 illegal，mcause 会是 2
# ======================================================================

_start:
    la      t0, trap_handler
    csrw    mtvec, t0

    # 执行 add x0, x0, x0 (0x00000000)
    # 这是一条合法 NOP，不应触发任何异常
    .word   0x00000000

    # 如果到达这里 → 解码正确
    li      s0, 0xCAFE          # PASS
    la      t0, result_area
    sd      s0, 0(t0)

    .word   0x7ff00073           # estop


trap_handler:
    # 解码把 0x00000000 判成了 illegal_instruction → BUG
    csrr    t1, mcause           # 应该是 2
    li      s0, 0xBEEF           # FAIL
    la      t0, result_area
    sd      s0, 0(t0)
    sd      t1, 8(t0)

    # 跳转到 estop (用 j 避免 addi→jr hazard)
    j       handler_finish

handler_finish:
    .word   0x7ff00073           # estop


    .section .bss
    .align  3
result_area:
    .space  16
    .space  4096
stack_top:
