/* Branch-free 64-instruction windows. Three passes over the same instructions
 * and data; pass 0 warms caches, passes 1/2 are measured in the simulation.
 * CASE 0: independent ALU, CASE 1: RAW chain, CASE 2: warm L1D loads,
 * CASE 3: independent explicit 16-bit C.ADDI instructions.
 * No change to existing tests, reference values or watchdogs.
 */
#include "common.h"
  .option norvc
  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE 1
  li s0, 0
  li s1, 3
  la s2, probe_data
probe_repeat:
  li a1, 0
  li a2, 0
  li a3, 0
  li a4, 0
  .balign 32
  .globl probe_begin
probe_begin:
  .rept 16
#if CASE == 0
  addi a1, a1, 1
  addi a2, a2, 1
  addi a3, a3, 1
  addi a4, a4, 1
#elif CASE == 1
  addi a1, a1, 1
  addi a1, a1, 1
  addi a1, a1, 1
  addi a1, a1, 1
#elif CASE == 2
  ld a1, 0(s2)
  ld a2, 8(s2)
  ld a3, 16(s2)
  ld a4, 24(s2)
#else
  .2byte 0x0585 /* c.addi a1,1 */
  .2byte 0x0605 /* c.addi a2,1 */
  .2byte 0x0685 /* c.addi a3,1 */
  .2byte 0x0705 /* c.addi a4,1 */
#endif
  .endr
  .globl probe_end
probe_end:
#if CASE == 0 || CASE == 3
  CHECK_IMM a1, 16, 1
  CHECK_IMM a2, 16, 2
  CHECK_IMM a3, 16, 3
  CHECK_IMM a4, 16, 4
#elif CASE == 1
  CHECK_IMM a1, 64, 5
#else
  CHECK_IMM a1, 101, 6
  CHECK_IMM a2, 102, 7
  CHECK_IMM a3, 103, 8
  CHECK_IMM a4, 104, 9
#endif
  addi s0, s0, 1
  blt s0, s1, probe_repeat
  PASS
  TEST_RUNTIME
  .section .data
  .balign 32
probe_data:
  .dword 101, 102, 103, 104
