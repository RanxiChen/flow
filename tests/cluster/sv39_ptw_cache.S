// The PTW reads PTEs through the L1D PTW entry (breeze-mmu-vipt-design: PTW
// via a dedicated D-cache physical channel). Page-table lines are written by
// ordinary S-mode stores, so a walk after SFENCE.VMA must see:
//   - a dirty PTE still resident in this L1D (M state, same cache),
//   - a dirty PTE whose line was evicted (written back through L2),
//   - a changed non-leaf PTE (walk cache invalidated by SFENCE.VMA).
#include "common.h"

#define VA_T 0x40000000
#define NPAGES 8

// \dst <- leaf PTE for page k (register \k) of `pages`.
.macro PAGE_PTE dst, k
  la t1, pages
  slli t2, \k, 12
  add t1, t1, t2
  MK_PTE \dst, t1, (PTE_V | PTE_R | PTE_W | PTE_A | PTE_D)
.endm

.macro TAG dst, k
  addi \dst, \k, 0x100
.endm

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE 1

  la s0, root
  la s1, l1b
  la s2, l0b
  la s3, l0c
  MK_PTE t2, s1, PTE_V
  sd t2, 8(s0)                      // root[1] -> l1b
  li t1, 0x80000000
  MK_PTE t2, t1, (PTE_V | PTE_R | PTE_W | PTE_X | PTE_A | PTE_D)
  sd t2, 16(s0)                     // root[2] identity RAM
  MK_PTE t2, s2, PTE_V
  sd t2, 0(s1)                      // l1b[0] -> l0b
  la t1, qpage
  MK_PTE t2, t1, (PTE_V | PTE_R | PTE_W | PTE_A | PTE_D)
  sd t2, 0(s3)                      // l0c[0] -> qpage
  li t0, 0x999
  sd t0, 0(t1)
  // Tag every page.
  li t3, 0
1:
  la t1, pages
  slli t2, t3, 12
  add t1, t1, t2
  TAG t0, t3
  sd t0, 0(t1)
  addi t3, t3, 1
  li t2, NPAGES
  blt t3, t2, 1b
  li t3, 0
  PAGE_PTE t2, t3
  sd t2, 0(s2)

  srli t0, s0, 12
  li t1, SATP_SV39 | (2 << 44)
  or t0, t0, t1
  csrw satp, t0
  sfence.vma
  ENTER_S s_main

s_main:
  li s4, VA_T
  ld t1, 0(s4)
  CHECK_IMM t1, 0x100, 1

  // 1. PTE dirty in this L1D.
  li s5, 1
2:
  PAGE_PTE t2, s5
  sd t2, 0(s2)
  slli t0, s5, 1                    // even value: stays an invalid PTE
  sd t0, 24(s2)                     // data store into the same line
  sfence.vma s4, zero
  ld t1, 0(s4)
  TAG t0, s5
  CHECK_EQ t1, t0, 2
  addi s5, s5, 1
  li t0, NPAGES
  blt s5, t0, 2b

  // 2. PTE line evicted from L1D by same-set stores before the walk.
  li s5, 0
3:
  PAGE_PTE t2, s5
  sd t2, 0(s2)
  la t3, evict
  li t4, 0
4:
  slli t5, t4, 12
  add t5, t5, t3
  sd t4, 0(t5)
  addi t4, t4, 1
  li t5, 8
  blt t4, t5, 4b
  sfence.vma s4, zero
  ld t1, 0(s4)
  TAG t0, s5
  CHECK_EQ t1, t0, 3
  addi s5, s5, 1
  li t0, NPAGES
  blt s5, t0, 3b

  // 3. Non-leaf change: l1b[0] -> l0c, then back.
  MK_PTE t2, s3, PTE_V
  sd t2, 0(s1)
  sfence.vma
  ld t1, 0(s4)
  CHECK_IMM t1, 0x999, 4
  MK_PTE t2, s2, PTE_V
  sd t2, 0(s1)
  sfence.vma
  ld t1, 0(s4)
  CHECK_IMM t1, (0x100 + NPAGES - 1), 5

  // 4. Write through the translation, read physically.
  li t0, 0x7777
  sd t0, 8(s4)
  la t1, pages
  li t2, (NPAGES - 1) << 12
  add t1, t1, t2
  ld t3, 8(t1)
  CHECK_EQ t3, t0, 6
  PASS

  TEST_RUNTIME

  .data
  .balign 4096
root: .zero 4096
l1b:  .zero 4096
l0b:  .zero 4096
l0c:  .zero 4096
qpage: .zero 4096
pages: .zero 4096 * NPAGES
evict: .zero 4096 * 8
