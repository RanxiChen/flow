    .section .text
    .globl _start

# ======================================================================
# 测试: addi → jalr 数据冒险 (RAW hazard)
#
# 重现 inst_access_fault_test handler 里的 bug:
#   auipc t0, 0       → t0 = PC
#   addi  t0, t0, 16  → t0 = PC + 16 (= target)
#   jalr  zero, t0, 0 → 应该跳到 target
#
# 如果 forwarding 正常: 跳到 target → PASS
# 如果 hazard: 读到 stale auipc 值, 跳回 auipc 自身 → 死循环
# ======================================================================

_start:
    la      t0, trap_handler
    csrw    mtvec, t0

    # ---------------------------------------------------------------
    # 测试序列
    # ---------------------------------------------------------------
    auipc   t0, 0                   # t0 = PC of this auipc
    addi    t0, t0, 16              # t0 should = target_addr
    jalr    zero, t0, 0             # jump to target_addr
    nop                             # should be skipped

    # 走错路: hazarding 失败
wrong_path:
    li      s0, 0xBEEF              # FAIL
    la      t1, result_area
    sd      s0, 0(t1)
    .word   0x7ff00073


target_addr:
    # 正确到达 → forwarding 正常
    li      s0, 0xCAFE              # PASS
    la      t1, result_area
    sd      s0, 0(t1)
    .word   0x7ff00073


trap_handler:
    li      s0, 0xDEAD
    la      t1, result_area
    sd      s0, 0(t1)
    .word   0x7ff00073


    .section .bss
    .align  3
result_area:
    .space  8
    .space  4096
stack_top:
