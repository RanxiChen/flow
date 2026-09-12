/* LiteX serialboot supplies no FDT argument. Establish the OpenSBI input ABI.
 * Load this trampoline at 0x80080000, OpenSBI at 0x80000000 and the DTB at
 * 0x80100000. Linux (with initramfs) is loaded separately at 0x80200000.
 */
.section .text.entry, "ax", @progbits
.globl _start
.option norvc
_start:
    csrr a0, mhartid
    li a1, 0x80100000
    li a2, 0
    li a3, 0
    li a4, 0
    fence rw, rw
    fence.i
    li t0, 0x80000000
    jr t0
