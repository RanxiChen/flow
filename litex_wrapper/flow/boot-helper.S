	.section .text, "ax", @progbits
	.global boot_helper
	.global smp_ap_args
	.global smp_ap_target
	.global smp_ap_ready

boot_helper:
	/* Publish the next-stage DTB/argument registers and entry point. */
	la t0, smp_ap_args
	sd a1, 0(t0)
	sd a2, 8(t0)
	la t0, smp_ap_target
	sd a3, 0(t0)
	fence w, w

	/* Release the secondary harts only after all handoff data is visible. */
	li t1, 1
	la t0, smp_ap_ready
	sd t1, 0(t0)
	fence w, w

	/* RISC-V firmware receives the real boot hart ID in a0. */
	csrr a0, mhartid
	jr a3
