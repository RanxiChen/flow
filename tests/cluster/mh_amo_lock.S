// NHARTS harts share a spin lock (amoswap.w.aq acquire, amoswap.w.rl
// release) protecting a non-atomic ld/add/sd counter. Any loss of mutual
// exclusion or of acquire/release ordering loses increments. Phase A keeps
// lock and counter in one line, phase B in different lines.
#include "common.h"

#define N 100

// Run N locked increments on (\lock, \ctr) registers.
.macro LOCKED_LOOP lock, ctr
  li s5, 0
.Lloop\@:
  li t0, 1
.Lacq\@:
  amoswap.w.aq t1, t0, (\lock)
  beqz t1, .Lin\@
.Lspin\@:
  lw t1, 0(\lock)
  bnez t1, .Lspin\@
  j .Lacq\@
.Lin\@:
  ld t2, 0(\ctr)
  addi t2, t2, 1
  sd t2, 0(\ctr)
  amoswap.w.rl x0, x0, (\lock)
  addi s5, s5, 1
  li t0, N
  blt s5, t0, .Lloop\@
.endm

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE NHARTS

  la s0, lock_a
  addi s1, s0, 8
  LOCKED_LOOP s0, s1
  BARRIER done_a, 1
  la s0, lock_b
  la s1, ctr_b
  LOCKED_LOOP s0, s1
  BARRIER done_b, 2

  bnez s11, 9f
  la t0, lock_a
  ld t1, 8(t0)
  CHECK_IMM t1, NHARTS * N, 3
  la t0, ctr_b
  ld t1, 0(t0)
  CHECK_IMM t1, NHARTS * N, 4
  lw t1, 0(s0)
  CHECK_IMM t1, 0, 5
9:
  PASS

  TEST_RUNTIME

  .data
  .balign 64
lock_a: .dword 0, 0, 0, 0
  .balign 64
lock_b: .dword 0
  .balign 64
ctr_b: .dword 0
  .balign 64
done_a: .dword 0
  .balign 64
done_b: .dword 0
