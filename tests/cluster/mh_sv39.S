// Every hart runs S mode under Sv39 with its own page tables and ASID
// (h + 1):
//   VA 0x0000_0000 1 GiB    -> PA 0 (devices, for the CLINT)  RW
//   VA 0x8000_0000 1 GiB    -> identity RAM                    RWX
//   VA 0x4000_0000          -> this hart's private page
//   VA 0x4000_1000 + h*4K   -> the shared page SH (a different VA per hart)
//   VA 0x4020_0000          -> through the shared l0 table: R0, later R1
// Phase 1: private data, AMOADD on SH through each hart's own VA, read the
// shared-table mapping (now cached in every dTLB). Phase 2: hart 0 rewrites
// the shared PTE with an ordinary store and IPIs the others; their M-mode
// handler runs SFENCE.VMA (a simplified TLB shootdown) and they must then
// see R1. The other harts' walks read the PTE line hart 0 holds dirty.
#include "common.h"

#define N 50
#define VA_PRIV 0x40000000
#define VA_SH0  0x40001000
#define VA_SHT  0x40200000
#define IRQ_MSI ((1 << 63) | 3)
#define LEAF_RW (PTE_V | PTE_R | PTE_W | PTE_A | PTE_D)

// \dst <- address of page \h (register) of the per-hart array \base.
.macro PER_HART dst, base, h
  la \dst, \base
  slli t5, \h, 12
  add \dst, \dst, t5
.endm

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE NHARTS
  li a7, 0

  // ---- each hart builds its own root/l1/l0 (M mode, physical) ----
  PER_HART s0, roots, s11
  PER_HART s1, l1s, s11
  PER_HART s2, l0p, s11
  PER_HART s3, privs, s11
  li t1, 0
  MK_PTE t2, t1, LEAF_RW
  sd t2, 0(s0)                // root[0] devices
  MK_PTE t2, s1, PTE_V
  sd t2, 8(s0)                // root[1] -> l1
  li t1, 0x80000000
  MK_PTE t2, t1, (LEAF_RW | PTE_X)
  sd t2, 16(s0)               // root[2] identity RAM
  MK_PTE t2, s2, PTE_V
  sd t2, 0(s1)                // l1[0] -> private l0
  la t1, l0s
  MK_PTE t2, t1, PTE_V
  sd t2, 8(s1)                // l1[1] -> shared l0
  MK_PTE t2, s3, LEAF_RW
  sd t2, 0(s2)                // l0[0] -> private page
  la t1, shpage
  MK_PTE t2, t1, LEAF_RW
  addi t3, s11, 1
  slli t3, t3, 3
  add t3, t3, s2
  sd t2, 0(t3)                // l0[1 + h] -> SH
  bnez s11, 1f
  la t1, r0page
  MK_PTE t2, t1, (PTE_V | PTE_R | PTE_A)
  la t3, l0s
  sd t2, 0(t3)                // shared l0[0] -> R0
  li t0, 0xaaaa
  la t1, r0page
  sd t0, 0(t1)
  li t0, 0xbbbb
  la t1, r1page
  sd t0, 0(t1)
1:
  BARRIER b0, 1

  AREA t0
  la t1, ipi_hook
  sd t1, A_HOOK(t0)
  li t0, MIP_MSIP
  csrs mie, t0
  srli t0, s0, 12
  addi t1, s11, 1
  slli t1, t1, 44
  or t0, t0, t1
  li t1, SATP_SV39
  or t0, t0, t1
  csrw satp, t0
  sfence.vma
  ENTER_S s_main

s_main:
  // Private page through the translation, checked physically.
  li s4, VA_PRIV
  li t0, 0x111
  mul t0, t0, s11
  addi t0, t0, 0x500
  sd t0, 0(s4)
  ld t1, 0(s4)
  CHECK_EQ t1, t0, 2
  ld t1, 0(s3)
  CHECK_EQ t1, t0, 3

  // Shared counter through this hart's own VA.
  li s5, VA_SH0
  slli t0, s11, 12
  add s5, s5, t0
  li s6, 0
2:
  li t0, 1
  amoadd.d t1, t0, (s5)
  addi s6, s6, 1
  li t0, N
  blt s6, t0, 2b

  // Cache the shared-table mapping in this dTLB.
  li s7, VA_SHT
  ld t1, 0(s7)
  CHECK_IMM t1, 0xaaaa, 4
  BARRIER b1, 5

  bnez s11, 6f
  // ---- hart 0 ----
  ld t1, 0(s5)
  CHECK_IMM t1, NHARTS * N, 6
  la t1, r1page
  MK_PTE t2, t1, (PTE_V | PTE_R | PTE_A)
  la t3, l0s
  sd t2, 0(t3)
  fence rw, rw
  li s6, 1
3:
  li t0, NHARTS
  bge s6, t0, 4f
  li t0, CLINT_BASE
  slli t1, s6, 2
  add t0, t0, t1
  li t1, 1
  sw t1, 0(t0)
  addi s6, s6, 1
  j 3b
4:
  la t0, shot_ack
  li t1, NHARTS - 1
  WAIT_GE t0, t1, 7
  sfence.vma s7, zero
  ld t1, 0(s7)
  CHECK_IMM t1, 0xbbbb, 8
  PASS

  // ---- other harts: wait for the shootdown, then see R1 ----
6:
  la t0, shot
  slli t1, s11, 3
  add t0, t0, t1
  li t1, 1
  WAIT_GE t0, t1, 9
  ld t1, 0(s7)
  CHECK_IMM t1, 0xbbbb, 10
  PASS

ipi_hook:
  csrr t0, mcause
  li t1, IRQ_MSI
  beq t0, t1, 1f
  li a0, 11
  j finish_m
1:
  csrr t0, mhartid
  slli t1, t0, 2
  li t2, CLINT_BASE
  add t2, t2, t1
  sw zero, 0(t2)
  lw t1, 0(t2)
  sfence.vma
  csrr t0, mhartid
  slli t1, t0, 3
  la t2, shot
  add t2, t2, t1
  li t1, 1
  sd t1, 0(t2)
  la t2, shot_ack
  amoadd.d x0, t1, (t2)
  j m_ret

  TEST_RUNTIME

  .data
  .balign 64
b0: .dword 0
  .balign 64
b1: .dword 0
  .balign 64
shot: .dword 0, 0, 0, 0
  .balign 64
shot_ack: .dword 0
  .balign 4096
roots:  .zero 4096 * NHARTS
l1s:    .zero 4096 * NHARTS
l0p:    .zero 4096 * NHARTS
privs:  .zero 4096 * NHARTS
l0s:    .zero 4096
shpage: .zero 4096
r0page: .zero 4096
r1page: .zero 4096
