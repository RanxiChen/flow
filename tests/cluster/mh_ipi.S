// Inter-processor interrupts through CLINT msip. Each round, every other
// hart announces it is waiting and sleeps in WFI with mstatus.MIE=0 (no
// check-then-sleep race; WFI wakes on a locally enabled pending interrupt
// regardless of MIE), then opens MIE to take the interrupt. Its handler
// checks the cause, clears its own msip and reports.
#include "common.h"

#define R 8
#define IRQ_MSI ((1 << 63) | 3)

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE NHARTS
  AREA t0
  la t1, ipi_hook
  sd t1, A_HOOK(t0)
  li t0, MIP_MSIP
  csrs mie, t0
  slli s1, s11, 3
  la t0, ready
  add s2, t0, s1              // ready[h]
  la t0, wake
  add s3, t0, s1              // wake[h]
  li s5, 0
  bnez s11, 5f

  // ---- hart 0: per round, wake each other hart and wait for its report ----
1:
  addi s4, s5, 1
  li s6, 1
2:
  li t0, NHARTS
  bge s6, t0, 4f
  slli t1, s6, 3
  la t0, ready
  add t0, t0, t1
  WAIT_GE t0, s4, 1
  li t0, CLINT_BASE
  slli t1, s6, 2
  add t0, t0, t1
  li t1, 1
  sw t1, 0(t0)
  slli t1, s6, 3
  la t0, wake
  add t0, t0, t1
  WAIT_GE t0, s4, 2
  addi s6, s6, 1
  j 2b
4:
  addi s5, s5, 1
  li t0, R
  blt s5, t0, 1b
  PASS

  // ---- other harts ----
5:
  addi s4, s5, 1
  sd s4, 0(s2)
6:
  li t0, MSTATUS_MIE
  csrc mstatus, t0
  ld t1, 0(s3)
  bge t1, s4, 7f
  wfi
  li t0, MSTATUS_MIE
  csrs mstatus, t0            // pending MSIP is taken here
  nop
  j 6b
7:
  addi s5, s5, 1
  li t0, R
  blt s5, t0, 5b
  csrw mie, zero
  PASS

ipi_hook:
  csrr t0, mcause
  li t1, IRQ_MSI
  beq t0, t1, 1f
  li a0, 10
  j finish_m
1:
  csrr t0, mhartid
  slli t1, t0, 2
  li t2, CLINT_BASE
  add t2, t2, t1
  sw zero, 0(t2)
  lw t1, 0(t2)                // MMIO read-back: msip is low before mret
  csrr t0, mhartid
  slli t1, t0, 3
  la t2, wake
  add t2, t2, t1
  ld t1, 0(t2)
  addi t1, t1, 1
  sd t1, 0(t2)
  j m_ret

  TEST_RUNTIME

  .data
  .balign 64
ready: .dword 0, 0, 0, 0
  .balign 64
wake:  .dword 0, 0, 0, 0
