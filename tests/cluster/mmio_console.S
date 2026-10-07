// MMIO through L1D's blocking uncached path (l1d-rtl-spec §9) to the
// harness console and CLINT model. The Spec asserts the console text.
#include "common.h"

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE 1

  // Console: one byte store per character.
  li s0, CONSOLE
  la s1, message
1:
  lbu t0, 0(s1)
  beqz t0, 2f
  sb t0, 0(s0)
  addi s1, s1, 1
  j 1b
2:

  // mtime advances.
  li s2, CLINT_MTIME
  ld t0, 0(s2)
  li t2, 2000
3:
  addi t2, t2, -1
  bnez t2, 3b
  ld t1, 0(s2)
  bltu t0, t1, 4f
  FAIL 1
4:

  // mtimecmp write/read, 64-bit then 32-bit halves (write then read the
  // same device address must see the new value).
  li s3, CLINT_MTIMECMP
  li t0, 0x123456789abcdef0
  sd t0, 0(s3)
  ld t1, 0(s3)
  CHECK_EQ t0, t1, 2
  li t0, 0x0badf00d
  sw t0, 0(s3)
  ld t1, 0(s3)
  CHECK_IMM t1, 0x123456780badf00d, 3
  lw t1, 4(s3)
  CHECK_IMM t1, 0x12345678, 4
  li t0, -1
  sd t0, 0(s3)
  ld t1, 0(s3)
  CHECK_EQ t0, t1, 5

  // msip set/clear (mie = 0: pending only, no trap).
  li s4, CLINT_BASE
  li t0, 1
  sw t0, 0(s4)
  lw t1, 0(s4)
  CHECK_IMM t1, 1, 6
  csrr t1, mip
  andi t1, t1, MIP_MSIP
  CHECK_IMM t1, MIP_MSIP, 7
  sw zero, 0(s4)
  lw t1, 0(s4)
  CHECK_IMM t1, 0, 8

  // Interleave cacheable and MMIO accesses: program order of the stores to
  // the console must be preserved around normal memory traffic.
  la s5, scratch
  li t0, 'X'
  sd t0, 0(s5)
  ld t1, 0(s5)
  sb t1, 0(s0)
  li t0, '\n'
  sb t0, 0(s0)

  PASS

  TEST_RUNTIME

  .data
message:
  .asciz "breeze cluster console ok\n"
  .balign 64
scratch:
  .dword 0
