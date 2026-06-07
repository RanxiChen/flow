    .section .text
    .globl _start

# ======================================================================
# BUG 1 复现测试: trap.valid 未被 memWbReg.valid 门控
#
# 原理:
#   BreezeBackend.scala:571  csrFile.io.trap.valid := wbTrap
#   BreezeBackend.scala:293  exceptionRedirect := memWbReg.valid && wbTrap
#
#   trap.valid 没有门控 memWbReg.valid，如果 flush 后异常标志残留，
#   会导致 mepc/mcause/mtval 被意外覆盖。
#
# 测试策略:
#   1. 先用 ecall 触发一次正常 trap
#   2. trap handler 里记录 mepc/mcause 到内存
#   3. 在 trap handler 中连续执行多条指令，观察是否有异常覆盖
#   4. 最终用 estop 结束，通过 tandem trace 检查 PC 序列和寄存器值
#
# 如何判断 bug:
#   - 正常情况: ecall 后 PC 跳转到 mtvec (0x1000)，mcause=11, mepc=ecall的PC
#   - bug 存在: mepc/mcause 在 trap handler 执行过程中被意外修改
#   - 通过 tandem trace 检查 trap handler 中的 CSR 读取结果
# ======================================================================

_start:
    # ---------------------------------------------------------------
    # Step 1: 设置 trap handler 地址
    # mtvec = 0x1000 (trap_handler)
    # ---------------------------------------------------------------
    la      t0, trap_handler
    csrw    mtvec, t0           # mtvec = trap_handler 地址

    # ---------------------------------------------------------------
    # Step 2: 保存一些已知值到寄存器，方便 trap handler 中验证
    # ---------------------------------------------------------------
    li      s0, 0xAAAA          # 标记值 A
    li      s1, 0xBBBB          # 标记值 B
    li      s2, 0xCCCC          # 标记值 C

    # ---------------------------------------------------------------
    # Step 3: 触发 ecall (cause=11)
    # 在 tandem trace 中应该看到 PC 跳转到 trap_handler
    # ---------------------------------------------------------------
    ecall

    # ---------------------------------------------------------------
    # Step 4: 如果 mret 正确，会回到这里
    # 读取 mcause 验证是否正确 (应该=11)
    # ---------------------------------------------------------------
    csrr    s3, mcause          # s3 = mcause (应该=11)
    csrr    s4, mepc            # s4 = mepc (应该=ecall的地址)

    # 保存结果到内存
    la      t0, result_area
    sd      s3, 0(t0)           # result[0] = mcause
    sd      s4, 8(t0)           # result[1] = mepc

    # ---------------------------------------------------------------
    # Step 5: 第二次 ecall，测试重复 trap 是否正常
    # ---------------------------------------------------------------
    ecall

    # 第二次 trap 返回后
    csrr    s5, mcause          # s5 = mcause (应该还是=11)
    csrr    s6, mepc            # s6 = mepc (应该=第二次ecall的地址)
    la      t0, result_area
    sd      s5, 16(t0)          # result[2] = mcause (第二次)
    sd      s6, 24(t0)          # result[3] = mepc (第二次)

    # estop 结束
    .word   0x7ff00073


# ======================================================================
# Trap Handler @ 0x1000
# ======================================================================
    .section .text.trap_handler
    .align 4
trap_handler:
    # ---------------------------------------------------------------
    # Trap handler 入口
    # 在 tandem trace 中，这里应该紧跟在 ecall 之后
    # ---------------------------------------------------------------

    # 读取当前 mcause (应该=11 for ecall)
    csrr    t1, mcause

    # 读取当前 mepc (应该=ecall 的 PC)
    csrr    t2, mepc

    # ---------------------------------------------------------------
    # 关键测试: 在 trap handler 中执行多条指令
    # 如果 trap.valid 没有门控 memWbReg.valid，那么在 flush 后
    # 的边界周期，可能会意外再次触发 trap，覆盖 mepc/mcause
    # ---------------------------------------------------------------
    li      t3, 0x1111
    li      t4, 0x2222
    li      t5, 0x3333
    li      t6, 0x4444

    # 再次读取 mcause/mepc，检查是否被意外修改
    csrr    a0, mcause          # 应该仍然=11
    csrr    a1, mepc            # 应该仍然=ecall 的 PC

    # mret 返回
    mret


    .section .bss
    .align  3
result_area:
    .space  32                  # 4 * 8 bytes

    .space  4096
stack_top:
