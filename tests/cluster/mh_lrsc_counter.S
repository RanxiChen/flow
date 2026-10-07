// NHARTS harts increment shared counters with LR/SC retry loops: a .D
// counter and a .W counter in the same line as the .D one. Totals must be
// exact (no lost SC success) and every hart must finish (forward progress
// under contention; the harness watchdog/maxCycles bound it).
#include "common.h"

#define N 100

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE NHARTS

  la s0, counter
  li s5, 0
1:
  lr.d.aq t1, (s0)
  addi t1, t1, 1
  sc.d.rl t2, t1, (s0)
  bnez t2, 1b
  addi t2, s0, 8
2:
  lr.w t1, (t2)
  addi t1, t1, 3
  sc.w t3, t1, (t2)
  bnez t3, 2b
  addi s5, s5, 1
  li t0, N
  blt s5, t0, 1b
  BARRIER done, 1

  bnez s11, 9f
  ld t1, 0(s0)
  CHECK_IMM t1, NHARTS * N, 2
  lw t1, 8(s0)
  CHECK_IMM t1, 3 * NHARTS * N, 3
9:
  PASS

  TEST_RUNTIME

  .data
  .balign 64
counter: .dword 0, 0, 0, 0
  .balign 64
done: .dword 0
