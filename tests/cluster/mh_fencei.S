// Cross-hart code modification: hart 0 rewrites a routine, executes FENCE.I
// and raises a flag; every other hart, after seeing the flag, executes its
// own FENCE.I (required: FENCE.I only orders the local hart's fetches) and
// must run the new code. All harts first run the old code, so the line is
// resident in every L1I.
#include "common.h"

#define R 6
#define ADDI_A0(imm) (((imm) << 20) | (10 << 7) | 0x13)

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE NHARTS
  la s0, patch
  jalr ra, 0(s0)
  CHECK_IMM a0, 1, 1
  BARRIER b0, 2
  la s9, flag
  la s10, ack
  li s7, (NHARTS - 1)
  li s5, 0
1:
  addi s4, s5, 1              // round number r+1
  addi s8, s5, 2              // new return value
  bnez s11, 3f
  li t0, ADDI_A0(0)
  slli t1, s8, 20
  or t0, t0, t1
  sw t0, 0(s0)
  fence.i
  fence rw, rw
  sd s4, 0(s9)
  jalr ra, 0(s0)
  CHECK_EQ a0, s8, 3
  mul t1, s7, s4
  WAIT_GE s10, t1, 4
  j 4f
3:
  WAIT_GE s9, s4, 5
  fence.i
  jalr ra, 0(s0)
  CHECK_EQ a0, s8, 6
  li t1, 1
  amoadd.d.aqrl x0, t1, (s10)
4:
  addi s5, s5, 1
  li t0, R
  blt s5, t0, 1b
  PASS

  TEST_RUNTIME

  .data
  .balign 64
patch:
  .word ADDI_A0(1)
  .word 0x00008067
  .balign 64
flag: .dword 0
  .balign 64
ack: .dword 0
  .balign 64
b0: .dword 0
