.section .text.init
.globl _start
_start:
  la sp, _end
  li t0, 16384
  add sp, sp, t0
  andi sp, sp, -16
  li t0, 0x2200                 /* mstatus.VS and FS = Initial */
  csrs mstatus, t0
  call main
  beqz a0, 1f
  slli a0, a0, 1
  ori a0, a0, 1
  j 2f
1:li a0, 1
2:la t0, tohost
  sd a0, 0(t0)
3:j 3b
