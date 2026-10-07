// Sv39 in S/U with the real MMU walking through L1D/L2 (breeze-mmu-rtl-spec
// §5.3 permission rules, Svade: A=0, or a store with D=0, is a page fault and
// software sets the bit). Mappings:
//   VA 0x8000_0000 1 GiB  -> PA 0x8000_0000  identity RWX (kernel, tables)
//   VA 0xC000_0000 1 GiB  -> PA 0x8000_0000  non-identity alias, RW
//   VA 0x4000_0000 2 MiB  -> PA 0x8040_0000  non-identity megapage, RW
//   VA 0x4020_0000 4 KiB pages (l0 table):
//     +0 pg_rw RW   +1 pg_ro R   +2 u_page U RWX   +3 x_page X only
//     +4 pg_a0 RW D, A=0   +5 pg_d0 RW A, D=0   +6 remapped by sfence test
// Load page faults are handled in M; store page faults and U ecalls are
// delegated to S through medeleg.
#include "common.h"

#define VA_MEGA   0x40000000
#define VA_4K     0x40200000
#define VA_RW     (VA_4K + 0x0000)
#define VA_RO     (VA_4K + 0x1000)
#define VA_USER   (VA_4K + 0x2000)
#define VA_X      (VA_4K + 0x3000)
#define VA_A0     (VA_4K + 0x4000)
#define VA_D0     (VA_4K + 0x5000)
#define VA_REMAP  (VA_4K + 0x6000)
#define PA_MEGA   0x80400000
#define VA_ALIAS(pa) ((pa) - 0x80000000 + 0xC0000000)
#define LEAF_RW (PTE_V | PTE_R | PTE_W | PTE_A | PTE_D)

// l0a[\idx] <- leaf for \pa_label with \fl
.macro SET_L0 idx, pa_label, fl
  la t1, \pa_label
  MK_PTE t2, t1, \fl
  sd t2, (\idx * 8)(s1)
.endm

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE 1
  li a7, 0

  // ---- build tables (M mode, physical) ----
  la s0, root
  la s1, l0a
  la s2, l1a
  // root[1] -> l1a, root[2] identity gigapage, root[3] alias gigapage
  MK_PTE t2, s2, PTE_V
  sd t2, 8(s0)
  li t1, 0x80000000
  MK_PTE t2, t1, (LEAF_RW | PTE_X)
  sd t2, 16(s0)
  MK_PTE t2, t1, LEAF_RW
  sd t2, 24(s0)
  // l1a[0] megapage, l1a[1] -> l0a
  li t1, PA_MEGA
  MK_PTE t2, t1, LEAF_RW
  sd t2, 0(s2)
  MK_PTE t2, s1, PTE_V
  sd t2, 8(s2)
  SET_L0 0, pg_rw, LEAF_RW
  SET_L0 1, pg_ro, (PTE_V | PTE_R | PTE_A)
  SET_L0 2, u_page, (LEAF_RW | PTE_X | PTE_U)
  SET_L0 3, x_page, (PTE_V | PTE_X | PTE_A)
  SET_L0 4, pg_a0, (PTE_V | PTE_R | PTE_W | PTE_D)
  SET_L0 5, pg_d0, (PTE_V | PTE_R | PTE_W | PTE_A)
  SET_L0 6, pg_rw, LEAF_RW

  // Known contents (physical writes).
  li t0, 0x0000000000001111
  la t1, pg_rw
  sd t0, 0(t1)
  li t0, 0x0000000000002222
  la t1, pg_ro
  sd t0, 0(t1)
  li t0, 0x0000000000004444
  la t1, pg_a0
  sd t0, 0(t1)
  li t0, 0x0000000000005555
  la t1, pg_d0
  sd t0, 0(t1)
  li t0, 0x0000000000003333
  la t1, u_page
  sd t0, 0x7f0(t1)
  li t0, 0x0123456789abcdef
  la t1, magic
  sd t0, 0(t1)

  li t0, (1 << CAUSE_STORE_PAGE) | (1 << CAUSE_ECALL_U)
  csrw medeleg, t0
  srli t0, s0, 12
  li t1, SATP_SV39 | (1 << 44)
  or t0, t0, t1
  csrw satp, t0
  sfence.vma
  ENTER_S s_main

s_main:
  // 1. 4 KiB non-identity RW page: read, write, visible at the physical page.
  li s3, VA_RW
  ld t1, 0(s3)
  CHECK_IMM t1, 0x1111, 1
  li t0, 0xa1a1
  sd t0, 8(s3)
  la t2, pg_rw
  ld t1, 8(t2)
  CHECK_EQ t1, t0, 2

  // 2. Megapage and gigapage alias.
  li s3, VA_MEGA
  li t0, 0xb2b2
  sd t0, 0x238(s3)
  li t2, PA_MEGA + 0x238
  ld t1, 0(t2)
  CHECK_EQ t1, t0, 3
  la t2, magic
  li t3, 0x80000000
  sub t2, t2, t3
  li t3, 0xC0000000
  add t2, t2, t3
  ld t1, 0(t2)
  CHECK_IMM t1, 0x0123456789abcdef, 4

  // 3. Read-only page: load OK, store -> delegated store page fault.
  li s3, VA_RO
  ld t1, 0(s3)
  CHECK_IMM t1, 0x2222, 5
  S_EXPECT CAUSE_STORE_PAGE
ro_store:
  sd t0, 0(s3)
  CHECK_TRAP_S CAUSE_STORE_PAGE, 6
  SEEN t5, S_AREA + A_TVAL
  CHECK_EQ t5, s3, 7
  SEEN t5, S_AREA + A_EPC
  la t4, ro_store
  CHECK_EQ t5, t4, 8
  ld t1, 0(s3)
  CHECK_IMM t1, 0x2222, 9

  // 4. SUM: S load of a U page faults with SUM=0, works with SUM=1.
  li s3, VA_USER + 0x7f0
  EXPECT CAUSE_LOAD_PAGE
  ld t1, 0(s3)
  CHECK_TRAP_M CAUSE_LOAD_PAGE, 10
  SEEN t5, A_TVAL
  CHECK_EQ t5, s3, 11
  li t0, MSTATUS_SUM
  csrs sstatus, t0
  ld t1, 0(s3)
  CHECK_IMM t1, 0x3333, 12
#ifndef QEMU   // QEMU 6.2 lets S fetch a U page here (stale SUM in its TLB)
  // S may never execute a U page, SUM or not.
  li s4, VA_USER
  EXPECT_RESUME CAUSE_FETCH_PAGE, back_u
  jalr ra, 0(s4)
back_u:
  CHECK_TRAP_M CAUSE_FETCH_PAGE, 13
  SEEN t5, A_EPC
  CHECK_EQ t5, s4, 14
#endif
  li t0, MSTATUS_SUM
  csrc sstatus, t0

  // 5. MXR: execute-only page readable only with MXR; executable from S.
  li s3, VA_X
  EXPECT CAUSE_LOAD_PAGE
  lw t1, 0(s3)
  CHECK_TRAP_M CAUSE_LOAD_PAGE, 15
  li t0, MSTATUS_MXR
  csrs sstatus, t0
  lw t1, 0(s3)
  la t2, x_page
  lw t3, 0(t2)
  CHECK_EQ t1, t3, 16
  li t0, MSTATUS_MXR
  csrc sstatus, t0
  jalr ra, 0(s3)
  CHECK_IMM a0, 0x55, 17

#ifndef QEMU   // QEMU 6.2 updates A/D in hardware (no Svade)
  // 6. Svade: A=0 -> load page fault; set A, sfence, retry.
  li s3, VA_A0
  EXPECT CAUSE_LOAD_PAGE
  ld t1, 0(s3)
  CHECK_TRAP_M CAUSE_LOAD_PAGE, 18
  SEEN t5, A_TVAL
  CHECK_EQ t5, s3, 19
  la t2, l0a
  ld t3, 32(t2)
  ori t3, t3, PTE_A
  sd t3, 32(t2)
  sfence.vma s3, zero
  ld t1, 0(s3)
  CHECK_IMM t1, 0x4444, 20
  //    D=0 -> store page fault (delegated); set D, sfence, retry.
  li s3, VA_D0
  ld t1, 0(s3)
  CHECK_IMM t1, 0x5555, 21
  S_EXPECT CAUSE_STORE_PAGE
  li t0, 0x5656
  sd t0, 0(s3)
  CHECK_TRAP_S CAUSE_STORE_PAGE, 22
  ld t1, 0(s3)
  CHECK_IMM t1, 0x5555, 23
  la t2, l0a
  ld t3, 40(t2)
  ori t3, t3, PTE_D
  sd t3, 40(t2)
  sfence.vma s3, zero
  sd t0, 0(s3)
  ld t1, 0(s3)
  CHECK_EQ t1, t0, 24
#endif

  // 7. Remap: VA_REMAP shows pg_rw, then pg_ro after PTE change + sfence.
  li s3, VA_REMAP
  ld t1, 0(s3)
  CHECK_IMM t1, 0x1111, 25
  la t2, pg_ro
  MK_PTE t3, t2, (PTE_V | PTE_R | PTE_A)
  la t2, l0a
  sd t3, 48(t2)
  sfence.vma s3, zero
  ld t1, 0(s3)
  CHECK_IMM t1, 0x2222, 26

  // 8. U mode on the U page: read, write, ecall delegated to S.
  li t0, VA_USER
  csrw sepc, t0
  li t0, MSTATUS_SPP
  csrc sstatus, t0
  S_EXPECT_RESUME CAUSE_ECALL_U, after_u
  sret
after_u:
  CHECK_TRAP_S CAUSE_ECALL_U, 27
  SEEN t5, S_AREA + A_PRIV
  CHECK_IMM t5, 0, 28
  la t2, u_page
  ld t1, 0x7f8(t2)
  CHECK_IMM t1, 0x3334, 29

  PASS

  TEST_RUNTIME

  // Code pages, reached through their own mappings.
  .text
  .balign 4096
u_page:
  lui t0, (VA_USER >> 12)
  ld t1, 0x7f0(t0)
  addi t1, t1, 1
  sd t1, 0x7f8(t0)
  ecall
1:
  j 1b
  .balign 4096
x_page:
  li a0, 0x55
  ret
  .balign 4096

  .data
  .balign 4096
root: .zero 4096
l1a:  .zero 4096
l0a:  .zero 4096
pg_rw: .zero 4096
pg_ro: .zero 4096
pg_a0: .zero 4096
pg_d0: .zero 4096
magic: .dword 0
