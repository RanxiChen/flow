    .section .text
    .globl _start

_start:
    # ------------------------------------------------------------
    # 可选：设置栈
    # 如果你的环境没有栈需求，其实可以不要
    # ------------------------------------------------------------
    la      sp, stack_top

    # ------------------------------------------------------------
    # 清空结果区
    # result[0] = predictable branch 累加结果
    # result[1] = alternating branch 累加结果
    # result[2] = done flag
    # ------------------------------------------------------------
    la      t0, result_area
    sd      x0, 0(t0)
    sd      x0, 8(t0)
    sd      x0, 16(t0)

    # ============================================================
    # 测试 1：强可预测循环分支
    #
    # 外层循环很多次，内层循环每次都靠 bne 回跳
    # 这个分支除了最后退出，几乎全是 taken
    # 对 predictor 很友好
    # ============================================================

    li      s0, 40           # outer loop count
    li      s1, 0            # accumulator

test1_outer:
    li      s2, 200          # inner loop count

test1_inner:
    addi    s1, s1, 1        # 做一点无关计算，防止空转太单薄
    addi    s2, s2, -1
    bne     s2, x0, test1_inner

    addi    s0, s0, -1
    bne     s0, x0, test1_outer

    # 保存 test1 结果
    la      t0, result_area
    sd      s1, 0(t0)

    # ============================================================
    # 测试 2：交替分支
    #
    # 每次把一个 bit 在 0/1 之间翻转
    # 然后根据这个 bit 决定走哪条路径
    #
    # 这个分支模式是：
    # taken / not-taken / taken / not-taken ...
    # 可以用来观察 predictor 在这种模式下的表现
    # ============================================================

    li      s3, 5000         # loop count
    li      s4, 0            # toggle bit
    li      s5, 0            # accumulator

test2_loop:
    xori    s4, s4, 1        # 0/1 翻转

    beq     s4, x0, test2_zero_path
    addi    s5, s5, 3
    jal     x0, test2_next

test2_zero_path:
    addi    s5, s5, 7

test2_next:
    addi    s3, s3, -1
    bne     s3, x0, test2_loop

    # 保存 test2 结果
    la      t0, result_area
    sd      s5, 8(t0)

    # done flag = 1
    li      t1, 1
    sd      t1, 16(t0)

finish:
    .word   0x7ff00073       # estop，通知 BreezeCoreSimApp 正常结束


    .section .bss
    .align  3

result_area:
    .space  24               # 3 * 8 bytes

    .space  4096
stack_top:
