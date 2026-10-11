/* R04 supported subset, with independent scalar checks of all stored lanes. */
.section .text.init
.globl _start
_start:
  la t0, trap
  csrw mtvec, t0
  li s3, 0
  /* VS=Off must trap precisely before reserving vector work. */
fault_pc:
  .word 0x0c007057 /* vsetvli zero,zero,e8,m1,ta,ma */
  li t0, 1
  bne s3, t0, fail
  li t0, 0x200
  csrs mstatus, t0
#ifdef BE2_RVV_SV39
  li t0, -1
  csrw pmpaddr0, t0
  li t0, 0xf
  csrw pmpcfg0, t0
  la t0, root_table
  srli t0, t0, 12
  li t1, 0x8000000000000000
  or t0, t0, t1
  csrw satp, t0
  sfence.vma
  li t0, 0x1800
  csrc mstatus, t0
  li t0, 0x20800 /* MPRV=1, MPP=S; instruction fetch stays in M. */
  csrs mstatus, t0
#endif
  li a0, 8
  vsetvli a1, a0, e64, m1, ta, ma
  bne a0, a1, fail
  csrr t0, vl
  bne t0, a0, fail
  li a2, 5
  vmv.v.x v1, a2
  li a2, 7
  vadd.vx v2, v1, a2
  la a3, vector_output
#ifdef BE2_RVV_SV39
  li t0, 0x40000000
  add a3, a3, t0 /* Non-identity VA alias to the same physical output. */
#endif
  /* Populate the scalar L1D before the vector store invalidates it. */
  ld t0, 0(a3)
  vse64.v v2, (a3)
  ld t0, 0(a3)
  li t1, 12
  bne t0, t1, fail
  vle64.v v3, (a3)
  vmv.x.s t0, v3
  bne t0, t1, fail
  /* Linux 6.18 context discard uses immediate broadcast, including LMUL=8. */
  vsetvli t0, zero, e8, m8, ta, ma
  li t1, 512
  bne t0, t1, fail
  vmv.v.i v8, -1
  vmv.x.s t0, v8
  li t1, -1
  bne t0, t1, fail
  la a4, vector_context
#ifdef BE2_RVV_SV39
  li t0, 0x40000000
  add a4, a4, t0
#endif
  vse8.v v8, (a4)
  vmv.v.i v8, 0
  vle8.v v8, (a4)
  vmv.x.s t0, v15
  bne t0, t1, fail
  csrr t0, vlenb
  li t1, 64
  bne t0, t1, fail
  li a0, 8
  vsetvli a1, a0, e64, m1, ta, ma
  li t1, 12
  li t2, 8
check:
  ld t0, 0(a3)
  bne t0, t1, fail
  addi a3, a3, 8
  addi t2, t2, -1
  bnez t2, check
  li t0, 1
finish:
  la t1, tohost
  sd t0, 0(t1)
1: j 1b
fail:
  li t0, 3
  j finish
.balign 4
trap:
  csrr t0, mcause
  li t1, 2
  bne t0, t1, fail
  csrr t0, mepc
  la t1, fault_pc
  bne t0, t1, fail
  csrr t1, mtval
  li t2, 0x0c007057
  bne t1, t2, fail
  addi t0, t0, 4
  csrw mepc, t0
  addi s3, s3, 1
  mret
.section .data
.balign 64
.globl vector_output
vector_output: .zero 64
/* Exercise both pretranslated segments, a partial first bus beat and the
   store/load row gather across a 4KiB boundary. */
.balign 4096
.zero 4096-24
.globl vector_context
vector_context: .zero 512
.balign 64
.globl tohost
tohost: .dword 0
#ifdef BE2_RVV_SV39
.balign 4096
root_table:
  .quad 0, 0, 0x200000cf, 0x200000cf
  .zero 4096-32
#endif
