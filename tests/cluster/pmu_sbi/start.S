/* S-mode payload entered by real OpenSBI, not a replacement SBI firmware. */
  .section .text.init
  .globl _start
_start:
  .option push
  .option norelax
  la gp, __global_pointer$
  .option pop
  la sp, stack_top
  la t0, __bss_start
  la t1, __bss_end
1:
  bgeu t0, t1, 2f
  sd zero, 0(t0)
  addi t0, t0, 8
  j 1b
2:
  la t0, pmu_trap
  csrw stvec, t0
  call pmu_main
3:
  wfi
  j 3b

  .balign 4
pmu_trap:
  addi sp, sp, -128
  sd ra, 0(sp)
  sd t0, 8(sp)
  sd t1, 16(sp)
  sd t2, 24(sp)
  sd t3, 32(sp)
  sd t4, 40(sp)
  sd t5, 48(sp)
  sd t6, 56(sp)
  sd a0, 64(sp)
  sd a1, 72(sp)
  sd a2, 80(sp)
  sd a3, 88(sp)
  sd a4, 96(sp)
  sd a5, 104(sp)
  sd a6, 112(sp)
  sd a7, 120(sp)
  call pmu_irq
  ld ra, 0(sp)
  ld t0, 8(sp)
  ld t1, 16(sp)
  ld t2, 24(sp)
  ld t3, 32(sp)
  ld t4, 40(sp)
  ld t5, 48(sp)
  ld t6, 56(sp)
  ld a0, 64(sp)
  ld a1, 72(sp)
  ld a2, 80(sp)
  ld a3, 88(sp)
  ld a4, 96(sp)
  ld a5, 104(sp)
  ld a6, 112(sp)
  ld a7, 120(sp)
  addi sp, sp, 128
  sret

/* Explicit function bounds make the sampled-PC check independent of C
 * compiler block duplication and scheduling. Caller-saved registers only. */
  .balign 64
  .globl hot, pmu_hot_begin, pmu_hot_end
hot:
pmu_hot_begin:
  li a0, 1000000
  li t1, 123456789
  la t3, done
4:
  ld t2, 0(t3)
  bnez t2, 5f
  addi a0, a0, -1
  beqz a0, 5f
  slli t0, t1, 13
  xor t1, t1, t0
  srli t0, t1, 7
  xor t1, t1, t0
  slli t0, t1, 17
  xor t1, t1, t0
  j 4b
5:
  ret
pmu_hot_end:
