// Single-hart LR/SC and AMO (l1d-rtl-spec §8; RISC-V A extension).
// Expected AMO values are precomputed with an independent Python model
// (sign extension of .W results, .W updates only its 4 bytes).
#include "common.h"

  .section .text.init
  .globl _start
_start:
  TEST_INIT
  PARK_IF_HART_GE 1
  la s0, word0
  la s1, word1            // a different line

  // LR/SC success: SC returns 0 and writes.
  li t0, 10
  sd t0, 0(s0)
  lr.d t1, (s0)
  CHECK_IMM t1, 10, 1
  addi t1, t1, 1
  sc.d t2, t1, (s0)
  CHECK_IMM t2, 0, 2
  ld t3, 0(s0)
  CHECK_IMM t3, 11, 3

  // SC right after a successful SC has no reservation: fails, no write.
  li t1, 99
  sc.d t2, t1, (s0)
  CHECK_IMM t2, 1, 4
  ld t3, 0(s0)
  CHECK_IMM t3, 11, 5

  // A newer LR to another line moves the reservation: SC to the old fails.
  lr.d t1, (s0)
  lr.d t1, (s1)
  li t1, 55
  sc.d t2, t1, (s0)
  CHECK_IMM t2, 1, 6
  ld t3, 0(s0)
  CHECK_IMM t3, 11, 7

  // Reservation granularity: SC to another word of the reserved line may
  // succeed or fail; whatever it reports must match memory.
  lr.d t1, (s0)
  li t1, 77
  addi t0, s0, 8
  sc.d t2, t1, (t0)
  ld t3, 8(s0)
  beqz t2, 1f
  CHECK_IMM t2, 1, 8
  j 2f
1:
  CHECK_IMM t3, 77, 9
2:

  // LR.W sign-extends; SC.W writes only 4 bytes.
  li t0, 0x11111111fffffff0
  sd t0, 0(s0)
  lr.w.aq t1, (s0)
  CHECK_IMM t1, 0xfffffffffffffff0, 10
  li t1, 0x7
  sc.w.rl t2, t1, (s0)
  CHECK_IMM t2, 0, 11
  ld t3, 0(s0)
  CHECK_IMM t3, 0x1111111100000007, 12

  // LR/SC .aqrl increment loop (always succeeds single-hart within bound).
  sd zero, 0(s1)
  li s2, 0
  li s3, 50
3:
  li s4, 8                // retry bound per iteration
4:
  lr.d.aqrl t1, (s1)
  addi t1, t1, 1
  sc.d.aqrl t2, t1, (s1)
  beqz t2, 5f
  addi s4, s4, -1
  bnez s4, 4b
  FAIL 13
5:
  addi s2, s2, 1
  blt s2, s3, 3b
  ld t3, 0(s1)
  CHECK_IMM t3, 50, 14

  // A plain store to the reserved line by the same hart: SC may fail or
  // succeed, but a success must leave the SC value.
  lr.d t1, (s1)
  li t0, 1000
  sd t0, 0(s1)
  li t1, 2000
  sc.d t2, t1, (s1)
  ld t3, 0(s1)
  beqz t2, 6f
  CHECK_IMM t3, 1000, 15
  j 7f
6:
  CHECK_IMM t3, 2000, 16
7:

  // All nine AMOs, .D and .W, with aq/rl encodings rotated.
  la s0, amo_word
  // amoswap.d: mem 0xffffffff80000001 op 0x7fffffffffff0003 -> rd 0xffffffff80000001, mem 0x7fffffffffff0003
  li t0, 0xffffffff80000001
  sd t0, 0(s0)
  li t1, 0x7fffffffffff0003
  addi t2, s0, 0
  amoswap.d t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 100
  ld t3, 0(s0)
  CHECK_IMM t3, 0x7fffffffffff0003, 101
  // amoswap.d: mem 0x5 op 0xfffffffffffffffe -> rd 0x5, mem 0xfffffffffffffffe
  li t0, 0x5
  sd t0, 0(s0)
  li t1, 0xfffffffffffffffe
  addi t2, s0, 0
  amoswap.d.aq t3, t1, (t2)
  CHECK_IMM t3, 0x5, 102
  ld t3, 0(s0)
  CHECK_IMM t3, 0xfffffffffffffffe, 103
  // amoadd.d: mem 0xffffffff80000001 op 0x7fffffffffff0003 -> rd 0xffffffff80000001, mem 0x7fffffff7fff0004
  li t0, 0xffffffff80000001
  sd t0, 0(s0)
  li t1, 0x7fffffffffff0003
  addi t2, s0, 0
  amoadd.d.rl t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 104
  ld t3, 0(s0)
  CHECK_IMM t3, 0x7fffffff7fff0004, 105
  // amoadd.d: mem 0x5 op 0xfffffffffffffffe -> rd 0x5, mem 0x3
  li t0, 0x5
  sd t0, 0(s0)
  li t1, 0xfffffffffffffffe
  addi t2, s0, 0
  amoadd.d.aqrl t3, t1, (t2)
  CHECK_IMM t3, 0x5, 106
  ld t3, 0(s0)
  CHECK_IMM t3, 0x3, 107
  // amoxor.d: mem 0xffffffff80000001 op 0x7fffffffffff0003 -> rd 0xffffffff80000001, mem 0x800000007fff0002
  li t0, 0xffffffff80000001
  sd t0, 0(s0)
  li t1, 0x7fffffffffff0003
  addi t2, s0, 0
  amoxor.d t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 108
  ld t3, 0(s0)
  CHECK_IMM t3, 0x800000007fff0002, 109
  // amoxor.d: mem 0x5 op 0xfffffffffffffffe -> rd 0x5, mem 0xfffffffffffffffb
  li t0, 0x5
  sd t0, 0(s0)
  li t1, 0xfffffffffffffffe
  addi t2, s0, 0
  amoxor.d.aq t3, t1, (t2)
  CHECK_IMM t3, 0x5, 110
  ld t3, 0(s0)
  CHECK_IMM t3, 0xfffffffffffffffb, 111
  // amoand.d: mem 0xffffffff80000001 op 0x7fffffffffff0003 -> rd 0xffffffff80000001, mem 0x7fffffff80000001
  li t0, 0xffffffff80000001
  sd t0, 0(s0)
  li t1, 0x7fffffffffff0003
  addi t2, s0, 0
  amoand.d.rl t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 112
  ld t3, 0(s0)
  CHECK_IMM t3, 0x7fffffff80000001, 113
  // amoand.d: mem 0x5 op 0xfffffffffffffffe -> rd 0x5, mem 0x4
  li t0, 0x5
  sd t0, 0(s0)
  li t1, 0xfffffffffffffffe
  addi t2, s0, 0
  amoand.d.aqrl t3, t1, (t2)
  CHECK_IMM t3, 0x5, 114
  ld t3, 0(s0)
  CHECK_IMM t3, 0x4, 115
  // amoor.d: mem 0xffffffff80000001 op 0x7fffffffffff0003 -> rd 0xffffffff80000001, mem 0xffffffffffff0003
  li t0, 0xffffffff80000001
  sd t0, 0(s0)
  li t1, 0x7fffffffffff0003
  addi t2, s0, 0
  amoor.d t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 116
  ld t3, 0(s0)
  CHECK_IMM t3, 0xffffffffffff0003, 117
  // amoor.d: mem 0x5 op 0xfffffffffffffffe -> rd 0x5, mem 0xffffffffffffffff
  li t0, 0x5
  sd t0, 0(s0)
  li t1, 0xfffffffffffffffe
  addi t2, s0, 0
  amoor.d.aq t3, t1, (t2)
  CHECK_IMM t3, 0x5, 118
  ld t3, 0(s0)
  CHECK_IMM t3, 0xffffffffffffffff, 119
  // amomin.d: mem 0xffffffff80000001 op 0x7fffffffffff0003 -> rd 0xffffffff80000001, mem 0xffffffff80000001
  li t0, 0xffffffff80000001
  sd t0, 0(s0)
  li t1, 0x7fffffffffff0003
  addi t2, s0, 0
  amomin.d.rl t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 120
  ld t3, 0(s0)
  CHECK_IMM t3, 0xffffffff80000001, 121
  // amomin.d: mem 0x5 op 0xfffffffffffffffe -> rd 0x5, mem 0xfffffffffffffffe
  li t0, 0x5
  sd t0, 0(s0)
  li t1, 0xfffffffffffffffe
  addi t2, s0, 0
  amomin.d.aqrl t3, t1, (t2)
  CHECK_IMM t3, 0x5, 122
  ld t3, 0(s0)
  CHECK_IMM t3, 0xfffffffffffffffe, 123
  // amomax.d: mem 0xffffffff80000001 op 0x7fffffffffff0003 -> rd 0xffffffff80000001, mem 0x7fffffffffff0003
  li t0, 0xffffffff80000001
  sd t0, 0(s0)
  li t1, 0x7fffffffffff0003
  addi t2, s0, 0
  amomax.d t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 124
  ld t3, 0(s0)
  CHECK_IMM t3, 0x7fffffffffff0003, 125
  // amomax.d: mem 0x5 op 0xfffffffffffffffe -> rd 0x5, mem 0x5
  li t0, 0x5
  sd t0, 0(s0)
  li t1, 0xfffffffffffffffe
  addi t2, s0, 0
  amomax.d.aq t3, t1, (t2)
  CHECK_IMM t3, 0x5, 126
  ld t3, 0(s0)
  CHECK_IMM t3, 0x5, 127
  // amominu.d: mem 0xffffffff80000001 op 0x7fffffffffff0003 -> rd 0xffffffff80000001, mem 0x7fffffffffff0003
  li t0, 0xffffffff80000001
  sd t0, 0(s0)
  li t1, 0x7fffffffffff0003
  addi t2, s0, 0
  amominu.d.rl t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 128
  ld t3, 0(s0)
  CHECK_IMM t3, 0x7fffffffffff0003, 129
  // amominu.d: mem 0x5 op 0xfffffffffffffffe -> rd 0x5, mem 0x5
  li t0, 0x5
  sd t0, 0(s0)
  li t1, 0xfffffffffffffffe
  addi t2, s0, 0
  amominu.d.aqrl t3, t1, (t2)
  CHECK_IMM t3, 0x5, 130
  ld t3, 0(s0)
  CHECK_IMM t3, 0x5, 131
  // amomaxu.d: mem 0xffffffff80000001 op 0x7fffffffffff0003 -> rd 0xffffffff80000001, mem 0xffffffff80000001
  li t0, 0xffffffff80000001
  sd t0, 0(s0)
  li t1, 0x7fffffffffff0003
  addi t2, s0, 0
  amomaxu.d t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 132
  ld t3, 0(s0)
  CHECK_IMM t3, 0xffffffff80000001, 133
  // amomaxu.d: mem 0x5 op 0xfffffffffffffffe -> rd 0x5, mem 0xfffffffffffffffe
  li t0, 0x5
  sd t0, 0(s0)
  li t1, 0xfffffffffffffffe
  addi t2, s0, 0
  amomaxu.d.aq t3, t1, (t2)
  CHECK_IMM t3, 0x5, 134
  ld t3, 0(s0)
  CHECK_IMM t3, 0xfffffffffffffffe, 135
  // amoswap.w: mem 0x80000001 op 0x7fff0003 -> rd 0xffffffff80000001, mem 0x7fff0003
  li t0, 0x8000000113579bdf
  sd t0, 0(s0)
  li t1, 0x7fff0003
  addi t2, s0, 4
  amoswap.w.rl t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 136
  ld t3, 0(s0)
  CHECK_IMM t3, 0x7fff000313579bdf, 137
  // amoswap.w: mem 0x5 op 0xfffffffe -> rd 0x5, mem 0xfffffffe
  li t0, 0x513579bdf
  sd t0, 0(s0)
  li t1, 0xfffffffe
  addi t2, s0, 4
  amoswap.w.aqrl t3, t1, (t2)
  CHECK_IMM t3, 0x5, 138
  ld t3, 0(s0)
  CHECK_IMM t3, 0xfffffffe13579bdf, 139
  // amoadd.w: mem 0x80000001 op 0x7fff0003 -> rd 0xffffffff80000001, mem 0xffff0004
  li t0, 0x8000000113579bdf
  sd t0, 0(s0)
  li t1, 0x7fff0003
  addi t2, s0, 4
  amoadd.w t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 140
  ld t3, 0(s0)
  CHECK_IMM t3, 0xffff000413579bdf, 141
  // amoadd.w: mem 0x5 op 0xfffffffe -> rd 0x5, mem 0x3
  li t0, 0x513579bdf
  sd t0, 0(s0)
  li t1, 0xfffffffe
  addi t2, s0, 4
  amoadd.w.aq t3, t1, (t2)
  CHECK_IMM t3, 0x5, 142
  ld t3, 0(s0)
  CHECK_IMM t3, 0x313579bdf, 143
  // amoxor.w: mem 0x80000001 op 0x7fff0003 -> rd 0xffffffff80000001, mem 0xffff0002
  li t0, 0x8000000113579bdf
  sd t0, 0(s0)
  li t1, 0x7fff0003
  addi t2, s0, 4
  amoxor.w.rl t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 144
  ld t3, 0(s0)
  CHECK_IMM t3, 0xffff000213579bdf, 145
  // amoxor.w: mem 0x5 op 0xfffffffe -> rd 0x5, mem 0xfffffffb
  li t0, 0x513579bdf
  sd t0, 0(s0)
  li t1, 0xfffffffe
  addi t2, s0, 4
  amoxor.w.aqrl t3, t1, (t2)
  CHECK_IMM t3, 0x5, 146
  ld t3, 0(s0)
  CHECK_IMM t3, 0xfffffffb13579bdf, 147
  // amoand.w: mem 0x80000001 op 0x7fff0003 -> rd 0xffffffff80000001, mem 0x1
  li t0, 0x8000000113579bdf
  sd t0, 0(s0)
  li t1, 0x7fff0003
  addi t2, s0, 4
  amoand.w t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 148
  ld t3, 0(s0)
  CHECK_IMM t3, 0x113579bdf, 149
  // amoand.w: mem 0x5 op 0xfffffffe -> rd 0x5, mem 0x4
  li t0, 0x513579bdf
  sd t0, 0(s0)
  li t1, 0xfffffffe
  addi t2, s0, 4
  amoand.w.aq t3, t1, (t2)
  CHECK_IMM t3, 0x5, 150
  ld t3, 0(s0)
  CHECK_IMM t3, 0x413579bdf, 151
  // amoor.w: mem 0x80000001 op 0x7fff0003 -> rd 0xffffffff80000001, mem 0xffff0003
  li t0, 0x8000000113579bdf
  sd t0, 0(s0)
  li t1, 0x7fff0003
  addi t2, s0, 4
  amoor.w.rl t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 152
  ld t3, 0(s0)
  CHECK_IMM t3, 0xffff000313579bdf, 153
  // amoor.w: mem 0x5 op 0xfffffffe -> rd 0x5, mem 0xffffffff
  li t0, 0x513579bdf
  sd t0, 0(s0)
  li t1, 0xfffffffe
  addi t2, s0, 4
  amoor.w.aqrl t3, t1, (t2)
  CHECK_IMM t3, 0x5, 154
  ld t3, 0(s0)
  CHECK_IMM t3, 0xffffffff13579bdf, 155
  // amomin.w: mem 0x80000001 op 0x7fff0003 -> rd 0xffffffff80000001, mem 0x80000001
  li t0, 0x8000000113579bdf
  sd t0, 0(s0)
  li t1, 0x7fff0003
  addi t2, s0, 4
  amomin.w t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 156
  ld t3, 0(s0)
  CHECK_IMM t3, 0x8000000113579bdf, 157
  // amomin.w: mem 0x5 op 0xfffffffe -> rd 0x5, mem 0xfffffffe
  li t0, 0x513579bdf
  sd t0, 0(s0)
  li t1, 0xfffffffe
  addi t2, s0, 4
  amomin.w.aq t3, t1, (t2)
  CHECK_IMM t3, 0x5, 158
  ld t3, 0(s0)
  CHECK_IMM t3, 0xfffffffe13579bdf, 159
  // amomax.w: mem 0x80000001 op 0x7fff0003 -> rd 0xffffffff80000001, mem 0x7fff0003
  li t0, 0x8000000113579bdf
  sd t0, 0(s0)
  li t1, 0x7fff0003
  addi t2, s0, 4
  amomax.w.rl t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 160
  ld t3, 0(s0)
  CHECK_IMM t3, 0x7fff000313579bdf, 161
  // amomax.w: mem 0x5 op 0xfffffffe -> rd 0x5, mem 0x5
  li t0, 0x513579bdf
  sd t0, 0(s0)
  li t1, 0xfffffffe
  addi t2, s0, 4
  amomax.w.aqrl t3, t1, (t2)
  CHECK_IMM t3, 0x5, 162
  ld t3, 0(s0)
  CHECK_IMM t3, 0x513579bdf, 163
  // amominu.w: mem 0x80000001 op 0x7fff0003 -> rd 0xffffffff80000001, mem 0x7fff0003
  li t0, 0x8000000113579bdf
  sd t0, 0(s0)
  li t1, 0x7fff0003
  addi t2, s0, 4
  amominu.w t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 164
  ld t3, 0(s0)
  CHECK_IMM t3, 0x7fff000313579bdf, 165
  // amominu.w: mem 0x5 op 0xfffffffe -> rd 0x5, mem 0x5
  li t0, 0x513579bdf
  sd t0, 0(s0)
  li t1, 0xfffffffe
  addi t2, s0, 4
  amominu.w.aq t3, t1, (t2)
  CHECK_IMM t3, 0x5, 166
  ld t3, 0(s0)
  CHECK_IMM t3, 0x513579bdf, 167
  // amomaxu.w: mem 0x80000001 op 0x7fff0003 -> rd 0xffffffff80000001, mem 0x80000001
  li t0, 0x8000000113579bdf
  sd t0, 0(s0)
  li t1, 0x7fff0003
  addi t2, s0, 4
  amomaxu.w.rl t3, t1, (t2)
  CHECK_IMM t3, 0xffffffff80000001, 168
  ld t3, 0(s0)
  CHECK_IMM t3, 0x8000000113579bdf, 169
  // amomaxu.w: mem 0x5 op 0xfffffffe -> rd 0x5, mem 0xfffffffe
  li t0, 0x513579bdf
  sd t0, 0(s0)
  li t1, 0xfffffffe
  addi t2, s0, 4
  amomaxu.w.aqrl t3, t1, (t2)
  CHECK_IMM t3, 0x5, 170
  ld t3, 0(s0)
  CHECK_IMM t3, 0xfffffffe13579bdf, 171

  PASS

  TEST_RUNTIME

  .data
  .balign 64
word0:
  .dword 0, 0, 0, 0
  .balign 64
word1:
  .dword 0, 0, 0, 0
  .balign 64
amo_word:
  .dword 0
