// Machine timer interrupt through the CLINT model: WFI wake with and
// without mstatus.MIE, vectored mtvec, and an M interrupt taken from S
// (always enabled below M). Interrupt mepc for a WFI is the next
// instruction (privileged spec 3.3.3).
#include "common.h"

#define IRQ_TIMER ((1 << 63) | 7)

.macro ARM_TIMER delta
  li t0, CLINT_MTIME
  ld t1, 0(t0)
  addi t1, t1, \delta
  li t0, CLINT_MTIMECMP
  sd t1, 0(t0)
  ld t1, 0(t0)              // MMIO read-back: write has completed
.endm

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE 1
  li t0, CLINT_MTIMECMP
  li t1, -1
  sd t1, 0(t0)
  ld t1, 0(t0)
  AREA t0
  la t1, timer_hook
  sd t1, A_HOOK(t0)
  la s0, irq_count
  sd zero, 0(s0)

  // 1. WFI with MIE=1: interrupt taken, mepc = instruction after WFI.
  ARM_TIMER 40
  li t0, MIP_MTIP
  csrs mie, t0
  li t0, MSTATUS_MIE
  csrs mstatus, t0
wfi1:
  wfi
after1:
  li t0, MSTATUS_MIE
  csrc mstatus, t0
  ld t1, 0(s0)
  CHECK_IMM t1, 1, 1
  la t0, irq_cause
  ld t1, 0(t0)
  CHECK_IMM t1, IRQ_TIMER, 2
  // The interrupt is normally taken while WFI sleeps (mepc = after1). If it
  // arrived before WFI retired, mepc = wfi1 and the hook skips the WFI.
  la t0, irq_epc
  ld t1, 0(t0)
  la t2, after1
  beq t1, t2, 1f
  la t2, wfi1
  CHECK_EQ t1, t2, 3
1:
  la t0, irq_mpp
  ld t1, 0(t0)
  CHECK_IMM t1, 3, 4

  // 2. WFI with MIE=0 and MTIE=1: wakes, no trap; MTIP visible.
  ARM_TIMER 40
wfi2:
  wfi
  csrr t1, mip
  andi t1, t1, MIP_MTIP
  CHECK_IMM t1, MIP_MTIP, 5
  ld t1, 0(s0)
  CHECK_IMM t1, 1, 6
  li t0, CLINT_MTIMECMP
  li t1, -1
  sd t1, 0(t0)
  ld t1, 0(t0)

  // 3. Vectored mtvec: the timer goes to BASE + 4*7.
  la t0, vec_table
  ori t0, t0, 1
  csrw mtvec, t0
  la s1, vec_hit
  sd zero, 0(s1)
  ARM_TIMER 30
  li t0, MSTATUS_MIE
  csrs mstatus, t0
  li t2, 100000
7:
  ld t1, 0(s0)
  li t3, 2
  beq t1, t3, 8f
  addi t2, t2, -1
  bnez t2, 7b
  FAIL 7
8:
  li t0, MSTATUS_MIE
  csrc mstatus, t0
  ld t1, 0(s1)
  CHECK_IMM t1, 1, 8
  la t0, m_trap
  csrw mtvec, t0

  // 4. Interrupt from S: M-level interrupts are enabled whatever MIE says.
  ARM_TIMER 60
  ENTER_S s_code

s_code:
  li t2, 200000
s_spin:
  ld t1, 0(s0)
  li t3, 3
  beq t1, t3, s_done
  addi t2, t2, -1
  bnez t2, s_spin
s_spin_end:
  FAIL 9
s_done:
  la t0, irq_mpp
  ld t1, 0(t0)
  CHECK_IMM t1, 1, 10
  la t0, irq_epc
  ld t1, 0(t0)
  la t2, s_code
  bltu t1, t2, 9f
  la t2, s_spin_end
  bgeu t1, t2, 9f
  PASS
9:
  FAIL 11

  // Hook: record, disarm the timer, return (t0-t5 are saved by m_trap).
timer_hook:
  csrr t0, mcause
  la t1, irq_cause
  sd t0, 0(t1)
  csrr t0, mepc
  la t1, irq_epc
  sd t0, 0(t1)
  la t1, wfi1
  bne t0, t1, 1f
  addi t0, t0, 4
  csrw mepc, t0
1:
  csrr t0, mstatus
  srli t0, t0, 11
  andi t0, t0, 3
  la t1, irq_mpp
  sd t0, 0(t1)
  la t1, irq_count
  ld t2, 0(t1)
  addi t2, t2, 1
  sd t2, 0(t1)
  li t0, CLINT_MTIMECMP
  li t1, -1
  sd t1, 0(t0)
  ld t1, 0(t0)
  j m_ret

  .balign 256
vec_table:
  j m_trap                  // 0: synchronous exceptions
  j vec_bad                 // 1
  j vec_bad                 // 2
  j vec_bad                 // 3
  j vec_bad                 // 4
  j vec_bad                 // 5
  j vec_bad                 // 6
  j vec_timer               // 7: machine timer
  j vec_bad                 // 8
  j vec_bad                 // 9
  j vec_bad                 // 10
  j vec_bad                 // 11
vec_timer:
  csrrw t6, mscratch, t6
  sd t0, A_SAVE + 48(t6)
  sd t1, A_SAVE + 56(t6)
  la t0, vec_hit
  li t1, 1
  sd t1, 0(t0)
  ld t0, A_SAVE + 48(t6)
  ld t1, A_SAVE + 56(t6)
  csrrw t6, mscratch, t6
  j m_trap
vec_bad:
  li a0, 12
  j finish_m

  TEST_RUNTIME

  .data
  .balign 64
irq_count: .dword 0
irq_cause: .dword 0
irq_epc:   .dword 0
irq_mpp:   .dword 0
vec_hit:   .dword 0
