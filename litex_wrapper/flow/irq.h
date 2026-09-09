#ifndef __IRQ_H
#define __IRQ_H

#ifdef __cplusplus
extern "C" {
#endif

#include <system.h>

/*
 * Sources 10..17 carry LiteX's eight-bit interrupt vector. Source 10 is the
 * UART and is also the ID described by the Linux device tree. Context 0 is
 * hart 0 M-mode, where the LiteX BIOS runs.
 */
#define PLIC_BASE          0x0c000000UL
#define PLIC_PENDING       0x0c001000UL
#define PLIC_ENABLED       0x0c002000UL
#define PLIC_THRSHLD       0x0c200000UL
#define PLIC_CLAIM         0x0c200004UL
#define PLIC_EXT_IRQ_BASE  10
#define BREEZE_LITEX_IRQ_MASK 0xffU

static inline unsigned int irq_getie(void)
{
	return (csrr(mstatus) & CSR_MSTATUS_MIE) != 0;
}

static inline void irq_setie(unsigned int ie)
{
	if (ie)
		csrs(mstatus, CSR_MSTATUS_MIE);
	else
		csrc(mstatus, CSR_MSTATUS_MIE);
}

static inline unsigned int irq_getmask(void)
{
	return (*((volatile unsigned int *)PLIC_ENABLED) >> PLIC_EXT_IRQ_BASE) &
		BREEZE_LITEX_IRQ_MASK;
}

static inline void irq_setmask(unsigned int mask)
{
	*((volatile unsigned int *)PLIC_ENABLED) =
		(mask & BREEZE_LITEX_IRQ_MASK) << PLIC_EXT_IRQ_BASE;
}

static inline unsigned int irq_pending(void)
{
	return (*((volatile unsigned int *)PLIC_PENDING) >> PLIC_EXT_IRQ_BASE) &
		BREEZE_LITEX_IRQ_MASK;
}

#ifdef __cplusplus
}
#endif

#endif /* __IRQ_H */
