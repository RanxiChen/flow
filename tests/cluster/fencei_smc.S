// FENCE.I self-modifying code, single hart (l1d-rtl-spec §11; backend FENCE.I
// redirect + L1I flush). The patched routine is executed first so its line is
// resident in L1I; the store makes the line M in L1D; after FENCE.I the L1I
// refill must read the dirty line through L2 (Down probe of the L1D owner).
#include "common.h"

#define ADDI_A0(imm) (((imm) << 20) | (10 << 7) | 0x13)   // addi a0, x0, imm
#define RET 0x00008067                                     // jalr x0, 0(ra)

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE 1

  // 1. Warm L1I with the original routine.
  la s0, patch
  jalr ra, 0(s0)
  CHECK_IMM a0, 1, 1

  // 2. Rewrite the first instruction with a 32-bit store, FENCE.I, execute.
  li s1, 2
  li s2, 18
1:
  li t0, ADDI_A0(0)
  slli t1, s1, 20
  or t0, t0, t1
  sw t0, 0(s0)
  fence.i
  jalr ra, 0(s0)
  CHECK_EQ a0, s1, 2
  addi s1, s1, 1
  blt s1, s2, 1b

  // 3. Rewrite both instructions with one 64-bit store (data line is M,
  //    both instruction words change at once).
  li t0, (RET << 32) | ADDI_A0(77)
  sd t0, 0(s0)
  fence.i
  jalr ra, 0(s0)
  CHECK_IMM a0, 77, 3

  // 4. A data word in the same 32 B line as the code: writing it must not
  //    disturb the instructions, and reads back exactly.
  li t0, 0x0123456789abcdef
  sd t0, 16(s0)
  fence.i
  jalr ra, 0(s0)
  CHECK_IMM a0, 77, 4
  ld t1, 16(s0)
  CHECK_EQ t0, t1, 5

  // 5. Store without FENCE.I: either version may run (not checked); after
  //    FENCE.I the new one must.
  li t0, ADDI_A0(99)
  sw t0, 0(s0)
  jalr ra, 0(s0)
  fence.i
  jalr ra, 0(s0)
  CHECK_IMM a0, 99, 6

  // 6. Patch a second routine in a different line after evicting the
  //    first from L1D with same-set data stores (4 KiB set stride).
  la s3, patch2
  jalr ra, 0(s3)
  CHECK_IMM a0, 5, 7
  li t0, ADDI_A0(42)
  sw t0, 0(s3)
  la t1, evict_buf
  slli t5, s3, 52        // same L1D set as patch2: add its page offset
  srli t5, t5, 52
  add t1, t1, t5
  li t2, 0
  li t3, 8
2:
  slli t4, t2, 12
  add t4, t4, t1
  sd t2, 0(t4)
  addi t2, t2, 1
  blt t2, t3, 2b
  fence.i
  jalr ra, 0(s3)
  CHECK_IMM a0, 42, 8

  PASS

  TEST_RUNTIME

  .data
  .balign 64
patch:
  .word ADDI_A0(1)
  .word RET
  .word 0, 0
  .dword 0
  .dword 0
  .balign 64
patch2:
  .word ADDI_A0(5)
  .word RET
  .balign 4096
evict_buf:
  .zero 4096 * 9
