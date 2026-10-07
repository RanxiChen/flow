// Message passing: hart 0 writes data, FENCE w,w, writes a flag; readers
// wait for the flag, FENCE r,r, and must read the new data (RVWMO MP with
// fences is forbidden to observe flag=new, data=old). Phase 1 puts data and
// flag in different lines, phase 2 in the same line; each round uses a fresh
// data line and waits for every reader's acknowledgement.
#include "common.h"

#define R 32
#define STRIDE 64

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE NHARTS
  li s6, 0x0101010101010101
  li s7, (NHARTS - 1)

  // ---- phase 1: separate data and flag lines ----
  li s5, 0
1:
  mul s8, s5, s6
  addi s8, s8, 0x5a           // magic(r)
  la t0, data1
  li t1, STRIDE
  mul t1, t1, s5
  add s9, t0, t1              // data line of round r
  la s10, flag1
  addi s4, s5, 1
  bnez s11, 3f
  sd s8, 0(s9)
  fence w, w
  sd s4, 0(s10)
  la t0, ack1
  mul t1, s7, s4
  WAIT_GE t0, t1, 1
  j 4f
3:
  WAIT_GE s10, s4, 2
  fence r, r
  ld t1, 0(s9)
  CHECK_EQ t1, s8, 3
  la t0, ack1
  li t1, 1
  amoadd.d x0, t1, (t0)
4:
  addi s5, s5, 1
  li t0, R
  blt s5, t0, 1b

  // ---- phase 2: data at +0, flag at +16 of the same line ----
  li s5, 0
5:
  mul s8, s5, s6
  addi s8, s8, 0x77
  la t0, data2
  li t1, STRIDE
  mul t1, t1, s5
  add s9, t0, t1
  addi s10, s9, 16
  addi s4, s5, 1
  bnez s11, 6f
  sd s8, 0(s9)
  fence w, w
  sd s4, 0(s10)
  la t0, ack2
  mul t1, s7, s4
  WAIT_GE t0, t1, 4
  j 7f
6:
  WAIT_GE s10, s4, 5
  fence r, r
  ld t1, 0(s9)
  CHECK_EQ t1, s8, 6
  la t0, ack2
  li t1, 1
  amoadd.d x0, t1, (t0)
7:
  addi s5, s5, 1
  li t0, R
  blt s5, t0, 5b
  PASS

  TEST_RUNTIME

  .data
  .balign 64
flag1: .dword 0
  .balign 64
ack1: .dword 0
  .balign 64
ack2: .dword 0
  .balign 64
data1: .zero STRIDE * R
data2: .zero STRIDE * R
