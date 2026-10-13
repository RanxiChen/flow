.section .rodata
.balign 64
#ifdef VECTOR_MODE
.globl q4_repacked
q4_repacked:
/* N/64 groups x 28 blocks: 16 byte-planes of 64 rows, then 64 f32 scales. */
#ifndef GEMV_N
#define GEMV_N 256
#endif
.rept (GEMV_N / 64) * 28
  .fill 1024, 1, 0x11         /* signed preconverted 4-bit +1 in both nibbles */
  .rept 64
    .word 0x3f800000         /* scale 1.0f */
  .endr
.endr
#else
.globl q4_original
q4_original:
/* 64 rows x 28 original ggml Q4_0 blocks: q nibble 9 means +1. */
.rept 1792
  .fill 16, 1, 0x99
  .word 0x3f800000
.endr
#endif
