/* 32 KiB straight-line scalar text: deliberately exceeds the 16 KiB L1I.
 * No fence/syscall is required; each pass has a deterministic result. */
  .text
  .option push
  .option norvc
  .balign 64
  .globl flow_perf_icache_hot
  .type flow_perf_icache_hot,@function
flow_perf_icache_hot:
  .rept 8192
  addi a0,a0,1
  .endr
  ret
  .size flow_perf_icache_hot,.-flow_perf_icache_hot
  .option pop
  .section .note.GNU-stack,"",@progbits
