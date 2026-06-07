    .section .text
    .globl _start

# ======================================================================
# BUG 2 复现测试: instruction_access_fault 信号断开
#
# 原理:
#   前端硬接 instruction_access_fault := false.B
#   取指访问错误（跳转到未映射地址）不会触发异常 cause=1
#
# 测试策略:
#   1. 设置 mtvec 指向 trap handler
#   2. 跳转到一个未在内存中映射的高地址 (0x1000_0000)
#   3. 观察 CPU 行为:
#      - 正确行为: 触发 instruction_access_fault (cause=1), 跳转 trap handler
#      - bug 行为: 静默继续执行，PC 推进到错误地址，取到 X/0 数据
#
# 如何判断 bug:
#   - 如果 tandem trace 显示 PC 跳转到 trap_handler (0x1000 区域)
#     → 说明 instruction_access_fault 正常工作
#   - 如果 simulation timeout 或者 PC 跳到奇怪的地址
#     → 说明 bug 存在，取指错误被静默忽略
#
# 注意: 此测试需要配合仿真器的内存模型
#   仿真器只映射了 ELF 加载的地址范围，跳到未映射地址时
#   仿真器的 imem 响应行为决定了测试结果
# ======================================================================

_start:
    # ---------------------------------------------------------------
    # Step 1: 设置 trap handler
    # ---------------------------------------------------------------
    la      t0, trap_handler
    csrw    mtvec, t0

    # ---------------------------------------------------------------
    # Step 2: 设置标记值
    # ---------------------------------------------------------------
    li      s0, 0x1111          # 到达标记
    li      s1, 0               # trap 未触发标记

    # ---------------------------------------------------------------
    # Step 3: 跳转到未映射地址
    # 0x1000_0000 不在 ELF 加载范围内
    # 如果 instruction_access_fault 工作，应该触发 trap
    # ---------------------------------------------------------------
    li      t1, 0x10000000
    jalr    x0, t1, 0           # 跳转到 0x1000_0000

    # ---------------------------------------------------------------
    # 如果到达这里，说明没有触发异常 (BUG!)
    # 这意味着 CPU 从 0x1000_0000 取到了某些数据并执行了
    # ---------------------------------------------------------------
bug_path:
    li      s1, 0xBEEF          # trap 未触发标记 = 0xBEEF
    la      t0, result_area
    sd      s1, 0(t0)           # result[0] = 0xBEEF (表示 bug 存在)

    # estop
    .word   0x7ff00073


# ======================================================================
# Trap Handler
# 如果 instruction_access_fault 正常工作，PC 会跳到这里
# ======================================================================
    .section .text.trap_handler
    .align 4
trap_handler:
    # 读取 mcause，应该是 1 (instruction_access_fault)
    csrr    t1, mcause
    csrr    t2, mepc            # 应该是 0x1000_0000

    # 保存结果
    la      t0, result_area
    li      s1, 0xCAFE          # trap 触发标记 = 0xCAFE
    sd      s1, 0(t0)           # result[0] = 0xCAFE (表示正常)
    sd      t1, 8(t0)           # result[1] = mcause
    sd      t2, 16(t0)          # result[2] = mepc

    # 跳转到正常结束 (用 j 避免 addi→jalr hazard)
    nop                     # 隔开 CSR 读和跳转
    j       finish

finish:
    .word   0x7ff00073          # estop


    .section .bss
    .align  3
result_area:
    .space  24                  # 3 * 8 bytes

    .space  4096
stack_top:
