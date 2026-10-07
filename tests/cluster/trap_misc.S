// Synchronous traps across M/S/U: ecall, illegal instructions, misaligned
// data accesses (trapped by L1D before translation, l1d-rtl-spec §5.1:
// loads/LR cause 4, stores/SC/AMO cause 6), PMA faults (fetch from a
// non-executable or unmapped region: 1; load: 5; store/SC/AMO, and atomics
// to AMONone/RsrvNone device regions: 7, dcache-pipeline-design atomic PMA)
// and medeleg delegation of illegal instructions to S.
#include "common.h"


.macro M_TRAP cause, code, insn:vararg
  EXPECT \cause
.Lpc\@:
  \insn
  CHECK_TRAP_M \cause, \code
  SEEN t5, A_EPC
  la t4, .Lpc\@
  CHECK_EQ t5, t4, \code + 1
.endm

.macro S_TRAP cause, code, insn:vararg
  S_EXPECT \cause
.Lpc\@:
  \insn
  CHECK_TRAP_S \cause, \code
  SEEN t5, S_AREA + A_EPC
  la t4, .Lpc\@
  CHECK_EQ t5, t4, \code + 1
.endm

.macro CHECK_TVAL_M reg, code
  SEEN t5, A_TVAL
  CHECK_EQ t5, \reg, \code
.endm

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE 1
  li a7, 0
  la s0, buf
  li t0, 0x1122334455667788
  sd t0, 0(s0)
  sd t0, 8(s0)

  // --- M mode ---
  M_TRAP CAUSE_ECALL_M, 10, ecall
  SEEN t5, A_PRIV
  CHECK_IMM t5, 3, 12
  M_TRAP CAUSE_ILLEGAL, 13, .word 0x00000000
  M_TRAP CAUSE_ILLEGAL, 15, csrw mhartid, t0

#ifndef QEMU   // QEMU performs misaligned loads/stores; PMA map differs from virt
  // Misaligned: tval is the faulting address; memory is untouched.
  addi s1, s0, 1
  M_TRAP CAUSE_LOAD_MISALIGNED, 20, ld t1, 0(s1)
  CHECK_TVAL_M s1, 22
  addi s1, s0, 2
  M_TRAP CAUSE_LOAD_MISALIGNED, 23, lw t1, 0(s1)
  CHECK_TVAL_M s1, 25
  addi s1, s0, 1
  M_TRAP CAUSE_LOAD_MISALIGNED, 26, lh t1, 0(s1)
  addi s1, s0, 4
  M_TRAP CAUSE_STORE_MISALIGNED, 28, sd t0, 0(s1)
  CHECK_TVAL_M s1, 30
  addi s1, s0, 2
  M_TRAP CAUSE_STORE_MISALIGNED, 31, sw t0, 0(s1)
  addi s1, s0, 1
  M_TRAP CAUSE_STORE_MISALIGNED, 33, sh t0, 0(s1)
  addi s1, s0, 2
  M_TRAP CAUSE_STORE_MISALIGNED, 35, amoadd.w t1, t0, (s1)
  addi s1, s0, 4
  M_TRAP CAUSE_STORE_MISALIGNED, 37, amoadd.d t1, t0, (s1)
  M_TRAP CAUSE_LOAD_MISALIGNED, 39, lr.d t1, (s1)
  M_TRAP CAUSE_STORE_MISALIGNED, 41, sc.d t1, t0, (s1)
  ld t1, 0(s0)
  CHECK_EQ t1, t0, 43
  ld t1, 8(s0)
  CHECK_EQ t1, t0, 44

  // PMA: fetch from a device region and from a hole.
  li s1, CONSOLE
  EXPECT_RESUME CAUSE_FETCH_ACCESS, back1
  jalr ra, 0(s1)
back1:
  CHECK_TRAP_M CAUSE_FETCH_ACCESS, 45
  SEEN t5, A_EPC
  CHECK_EQ t5, s1, 46
  CHECK_TVAL_M s1, 47
  li s1, HOLE
  EXPECT_RESUME CAUSE_FETCH_ACCESS, back2
  jalr ra, 0(s1)
back2:
  CHECK_TRAP_M CAUSE_FETCH_ACCESS, 48
  SEEN t5, A_EPC
  CHECK_EQ t5, s1, 49

  // PMA: data access to a hole; atomics to a device page.
  li s1, HOLE
  M_TRAP CAUSE_LOAD_ACCESS, 50, ld t1, 0(s1)
  CHECK_TVAL_M s1, 52
  M_TRAP CAUSE_STORE_ACCESS, 53, sd t0, 0(s1)
  CHECK_TVAL_M s1, 55
  li s1, GPIO0
  M_TRAP CAUSE_STORE_ACCESS, 56, amoadd.d t1, t0, (s1)
  M_TRAP CAUSE_STORE_ACCESS, 58, amoswap.w t1, t0, (s1)
  M_TRAP CAUSE_LOAD_ACCESS, 60, lr.d t1, (s1)
  M_TRAP CAUSE_STORE_ACCESS, 62, sc.d t1, t0, (s1)
#endif

  // --- S mode (bare): ecall to M, illegal delegated to S ---
  li t0, (1 << CAUSE_ILLEGAL)
  csrw medeleg, t0
  ENTER_S s_part
s_part:
  M_TRAP CAUSE_ECALL_S, 70, ecall
  SEEN t5, A_PRIV
  CHECK_IMM t5, 1, 72
  S_TRAP CAUSE_ILLEGAL, 73, csrr t1, mstatus
  SEEN t5, S_AREA + A_PRIV
  CHECK_IMM t5, 1, 75
  S_TRAP CAUSE_ILLEGAL, 76, mret
  S_TRAP CAUSE_ILLEGAL, 78, .word 0xffffffff

  // --- U mode ---
  la t0, u_part
  csrw sepc, t0
  li t0, MSTATUS_SPP
  csrc sstatus, t0
  sret
u_part:
  M_TRAP CAUSE_ECALL_U, 80, ecall
  SEEN t5, A_PRIV
  CHECK_IMM t5, 0, 82
  S_TRAP CAUSE_ILLEGAL, 83, sret
  SEEN t5, S_AREA + A_PRIV
  CHECK_IMM t5, 0, 85
  S_TRAP CAUSE_ILLEGAL, 86, csrr t1, sstatus
  S_TRAP CAUSE_ILLEGAL, 88, csrw satp, zero
  PASS

  TEST_RUNTIME

  .data
  .balign 64
buf:
  .dword 0, 0, 0, 0
