/* Zve32f decoupled-backend smoke (docs/tasks/ZVE32F-single-decoupled.md).
 * Not a conformance test: one directed check per mechanism, so that a
 * wiring error shows up as a specific failure code (tohost = code<<1 | 1).
 * Bare translation, M mode. Build: -march=rv64gc_zve32f. */
#define CHECK(code) li gp, code
.section .text.init
.globl _start
_start:
  la t0, trap
  csrw mtvec, t0
  li s3, 0                      /* expected-trap flag */
  li t0, 0x2200                 /* mstatus.VS = FS = Initial */
  csrs mstatus, t0

  /* 1. fast path: aligned e8 m1 load -> vadd -> store, scalar check. */
  CHECK(1)
  li a0, 64
  vsetvli a1, a0, e8, m1, ta, ma
  bne a0, a1, fail
  la a2, src
  la a3, dst
  vle8.v v1, (a2)
  vadd.vi v2, v1, 1
  vse8.v v2, (a3)
  li t2, 0
1:add t0, a2, t2
  lbu t0, 0(t0)
  addi t0, t0, 1
  andi t0, t0, 0xff
  add t1, a3, t2
  lbu t1, 0(t1)
  bne t0, t1, fail
  addi t2, t2, 1
  blt t2, a0, 1b

  /* 2. fast path, misaligned: load from src+3, store to dst+69. */
  CHECK(2)
  addi a4, a2, 3
  addi a5, a3, 69
  vle8.v v3, (a4)
  vse8.v v3, (a5)
  li t2, 0
1:add t0, a4, t2
  lbu t0, 0(t0)
  add t1, a5, t2
  lbu t1, 0(t1)
  bne t0, t1, fail
  addi t2, t2, 1
  blt t2, a0, 1b
  lbu t0, 68(a3)               /* byte before the misaligned store: untouched */
  li t1, 0x5a
  bne t0, t1, fail

  /* 3. scalar store (dirty in L1D) is visible to a younger vector load. */
  CHECK(3)
  li t0, 0x1122334455667788
  sd t0, 0(a3)
  vle8.v v4, (a3)
  li t1, 1
  vsetvli zero, t1, e32, m1, ta, ma
  vmv.x.s t1, v4
  li t0, 0x55667788
  bne t0, t1, fail

  /* 4. vector store is visible to a younger scalar load. */
  CHECK(4)
  li a0, 64
  vsetvli zero, a0, e8, m1, ta, ma
  vmv.v.i v5, 7
  vse8.v v5, (a3)
  ld t0, 0(a3)
  li t1, 0x0707070707070707
  bne t0, t1, fail
  ld t0, 56(a3)
  bne t0, t1, fail

  /* 5. LMUL=8, 512 bytes across a page boundary. */
  CHECK(5)
  li a0, 512
  vsetvli a1, a0, e8, m8, ta, ma
  bne a0, a1, fail
  la a6, cross
  vle8.v v8, (a2)               /* src is 512 initialized bytes */
  vse8.v v8, (a6)
  vle8.v v16, (a6)
  vse8.v v16, (a3)
  li t2, 0
1:add t0, a2, t2
  ld t0, 0(t0)
  add t1, a3, t2
  ld t1, 0(t1)
  bne t0, t1, fail
  addi t2, t2, 8
  blt t2, a0, 1b

  /* 6. e32 reduction and vmv.x.s / vcpop scalar results. */
  CHECK(6)
  li a0, 16
  vsetvli zero, a0, e32, m1, ta, ma
  la a7, words
  vle32.v v6, (a7)
  vmv.v.i v7, 0
  vredsum.vs v7, v6, v7
  vmv.x.s t0, v7
  li t1, 136                    /* 1+2+...+16 */
  bne t0, t1, fail
  vmsgt.vi v0, v6, 8            /* elements 9..16 */
  vcpop.m t0, v0
  li t1, 8
  bne t0, t1, fail

  /* 7. masked load keeps inactive lanes (mask undisturbed). */
  CHECK(7)
  vmv.v.i v9, -1
  vsetvli zero, a0, e32, m1, ta, mu
  vle32.v v9, (a7), v0.t
  vredsum.vs v10, v9, v7        /* v7[0] = 136 */
  vmv.x.s t0, v10
  /* inactive 8 lanes = -1 each, active = 9+..+16 = 100 */
  li t1, 136 + 100 - 8
  bne t0, t1, fail

  /* 8. strided load: Serial element engine. */
  CHECK(8)
  li t0, 8
  vsetvli zero, a0, e32, m1, ta, ma
  vlse32.v v11, (a7), t0        /* words[0], words[2], ... = 1,3,...,31 */
  vmv.v.i v12, 0
  vredsum.vs v12, v11, v12
  vmv.x.s t1, v12
  li t2, 256                    /* 1+3+...+31 */
  bne t1, t2, fail

  /* 9. FP32: vfmacc.vf + unordered sum + vfmv.f.s. */
  CHECK(9)
  la t0, floats
  vle32.v v13, (t0)             /* 1.0 x16 */
  flw fa0, 0(t0)                /* 1.0 */
  fsub.s fa1, fa0, fa0          /* 0.0 */
  vfmv.v.f v14, fa1
  fadd.s fa2, fa0, fa0          /* 2.0 */
  vfmacc.vf v14, fa2, v13       /* 2.0 each */
  vfmv.v.f v15, fa1
  vfredusum.vs v15, v14, v15
  vfmv.f.s fa3, v15
  fmv.x.w t1, fa3
  li t2, 0x42000000             /* 32.0f */
  bne t1, t2, fail

  /* 10. precise fault through the Serial path: load from a PMA hole. */
  CHECK(10)
  li s3, 1
  li a4, 0x40000000
  vsetvli zero, a0, e32, m1, ta, ma
  vle32.v v1, (a4)
  bnez s3, fail                 /* the trap handler cleared it */
  li t1, 5
  bne s4, t1, fail              /* mcause = load access fault */
  bne s5, a4, fail              /* mtval = first element address */
  bnez s6, fail                 /* vstart = 0 */

  li t0, 1
finish:
  la t1, tohost
  sd t0, 0(t1)
1: j 1b
fail:
  slli t0, gp, 1
  ori t0, t0, 1
  j finish

.balign 4
trap:
  beqz s3, unexpected
  csrr s4, mcause
  csrr s5, mtval
  csrr s6, vstart
  csrw vstart, zero
  csrr t0, mepc
  addi t0, t0, 4
  csrw mepc, t0
  li s3, 0
  mret
unexpected:
  li gp, 99
  j fail

.section .data
.balign 64
src:
  .set n, 0
  .rept 512
  .byte (n * 37 + 11) & 0xff
  .set n, n + 1
  .endr
.balign 64
dst: .fill 640, 1, 0x5a
.balign 64
words:
  .set n, 1
  .rept 32
  .word n
  .set n, n + 1
  .endr
floats:
  .rept 16
  .float 1.0
  .endr
.balign 4096
.zero 4096 - 40
cross: .zero 512
.balign 64
.globl tohost
tohost: .dword 0
