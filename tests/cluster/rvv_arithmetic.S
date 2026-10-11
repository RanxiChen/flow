/* Production-width MAC and dependent DOT check for the shared VRF read path. */
.section .text.init
.globl _start
_start:
  la t0, fail
  csrw mtvec, t0
  li t0, 0x200
  csrs mstatus, t0
  li a0, 8
  vsetvli a1, a0, e64, m1, ta, ma
  bne a0, a1, fail
  li a2, 0x0000000300000005
  vmv.v.x v1, a2
  li a2, 0x000000070000000b
  vmv.v.x v2, a2
  li a2, 0x0000000d00000011
  vmv.v.x v4, a2
  vmacc.vv v4, v1, v2
  la a3, vector_mac64_output
  vse64.v v4, (a3)
  li t1, 0x0000005100000048
  li t2, 8
check64:
  ld t0, 0(a3)
  bne t0, t1, fail
  addi a3, a3, 8
  addi t2, t2, -1
  bnez t2, check64
  li a0, 16
  vsetvli a1, a0, e32, m1, ta, ma
  bne a0, a1, fail
  li a2, 3
  vmv.v.x v1, a2
  li a2, 7
  vmv.v.x v2, a2
  li a2, 5
  vmv.v.x v4, a2
  vmacc.vv v4, v1, v2
  la a3, vector_mac32_output
  vse32.v v4, (a3)
  li t1, 26
  li t2, 16
check32:
  lwu t0, 0(a3)
  bne t0, t1, fail
  addi a3, a3, 4
  addi t2, t2, -1
  bnez t2, check32
  li a2, 0x02020202
  vmv.v.x v2, a2
  li a2, 3
  vmv.v.x v4, a2
  li a2, 0x01010101
  /* R04 signed packed-byte dot: vd=v4, vs2=v2, scalar rs1=a2. */
  .rept 16
  .word (44 << 26) | (1 << 25) | (2 << 20) | (12 << 15) | (6 << 12) | (4 << 7) | 0x57
  .endr
  la a3, vector_dot_output
  vse32.v v4, (a3)
  li t1, 131
  li t2, 16
check_dot:
  lwu t0, 0(a3)
  bne t0, t1, fail
  addi a3, a3, 4
  addi t2, t2, -1
  bnez t2, check_dot
  li t0, 1
finish:
  la t1, tohost
  sd t0, 0(t1)
1: j 1b
fail:
  li t0, 3
  j finish
.section .data
.balign 64
.globl vector_mac64_output, vector_mac32_output, vector_dot_output
vector_mac64_output: .zero 64
vector_mac32_output: .zero 64
vector_dot_output: .zero 64
.globl tohost
tohost: .dword 0
