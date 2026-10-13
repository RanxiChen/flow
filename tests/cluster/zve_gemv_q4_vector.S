.text
.globl gemv_vector
/* a0=repacked weights, a1=Q8 bytes, a2=output, fa0=Q8 scale. */
gemv_vector:
  addi sp, sp, -64
  sd s0, 0(sp); sd s1, 8(sp); sd s2, 16(sp); sd s3, 24(sp)
  sd s4, 32(sp); sd s5, 40(sp); sd s6, 48(sp); sd ra, 56(sp)
  mv s0, a0; mv s1, a1; mv s2, a2
#ifndef GEMV_N
#define GEMV_N 256
#endif
  li s3, GEMV_N / 64
1:
  li t0, 64
  vsetvli zero, t0, e32, m4, ta, ma
  vmv.v.i v24, 0
  mv s6, s1
  li s4, 28
2:
  li t0, 64
  vsetvli zero, t0, e16, m2, ta, ma
#ifdef FOUR_ACC
  vmv.v.i v4, 0
  vmv.v.i v6, 0
  vmv.v.i v12, 0
  vmv.v.i v14, 0
#else
  vmv.v.i v12, 0
#endif
  li s5, 4
3:
  li t0, 256
  vsetvli zero, t0, e8, m4, ta, ma
  vle8.v v8, (s0)
  addi s0, s0, 256
  li t0, 64
  vsetvli zero, t0, e8, m1, ta, ma
#ifdef FOUR_ACC
  .macro FOUR r, off, acc, lo, hi
    lb t1, \off(s6)
    lb t2, \off+16(s6)
    vsll.vi \lo, \r, 4
    vsra.vi \lo, \lo, 4
    vsra.vi \hi, \r, 4
    vwmacc.vx \acc, t1, \lo
    vwmacc.vx \acc, t2, \hi
  .endm
  FOUR v8, 0, v4,  v1, v2
  FOUR v9, 1, v6,  v3, v0
  FOUR v10,2, v12, v1, v2
  FOUR v11,3, v14, v3, v0
#else
  .macro FOUR r, off
    lb t1, \off(s6)
    lb t2, \off+16(s6)
    vsll.vi v1, \r, 4
    vsra.vi v1, v1, 4
    vsra.vi v2, \r, 4
    vwmacc.vx v12, t1, v1
    vwmacc.vx v12, t2, v2
  .endm
  FOUR v8, 0
  FOUR v9, 1
  FOUR v10, 2
  FOUR v11, 3
#endif
  addi s6, s6, 4
  addi s5, s5, -1
  bnez s5, 3b
  addi s6, s6, 16
  li t0, 64
  vsetvli zero, t0, e16, m2, ta, ma
#ifdef FOUR_ACC
  vadd.vv v4, v4, v6
  vadd.vv v12, v12, v14
  vadd.vv v4, v4, v12
  vfwcvt.f.x.v v16, v4
#else
  vfwcvt.f.x.v v16, v12
#endif
  vsetvli zero, t0, e32, m4, ta, ma
  vle32.v v28, (s0)
  addi s0, s0, 256
  vmv.v.i v20, 0
  vfmacc.vf v20, fa0, v28
  vfmacc.vv v24, v20, v16
  addi s4, s4, -1
  bnez s4, 2b
  vse32.v v24, (s2)
  addi s2, s2, 256
  addi s3, s3, -1
  bnez s3, 1b
  ld s0, 0(sp); ld s1, 8(sp); ld s2, 16(sp); ld s3, 24(sp)
  ld s4, 32(sp); ld s5, 40(sp); ld s6, 48(sp); ld ra, 56(sp)
  addi sp, sp, 64
  ret
