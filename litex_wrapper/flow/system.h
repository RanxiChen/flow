#ifndef __SYSTEM_H
#define __SYSTEM_H

#ifdef __cplusplus
extern "C" {
#endif

#include <csr-defs.h>

#define csrr(reg) ({ unsigned long __tmp; \
	asm volatile ("csrr %0, " #reg : "=r"(__tmp)); \
	__tmp; })

#define csrw(reg, val) ({ \
	if (__builtin_constant_p(val) && (unsigned long)(val) < 32) \
		asm volatile ("csrw " #reg ", %0" :: "i"(val)); \
	else \
		asm volatile ("csrw " #reg ", %0" :: "r"(val)); })

#define csrs(reg, bit) ({ \
	if (__builtin_constant_p(bit) && (unsigned long)(bit) < 32) \
		asm volatile ("csrrs x0, " #reg ", %0" :: "i"(bit)); \
	else \
		asm volatile ("csrrs x0, " #reg ", %0" :: "r"(bit)); })

#define csrc(reg, bit) ({ \
	if (__builtin_constant_p(bit) && (unsigned long)(bit) < 32) \
		asm volatile ("csrrc x0, " #reg ", %0" :: "i"(bit)); \
	else \
		asm volatile ("csrrc x0, " #reg ", %0" :: "r"(bit)); })

/*
 * Breeze implements FENCE.I as a serializing D-cache clean followed by an
 * I-cache invalidation. It is therefore also the conservative whole-cache
 * operation required before LiteX jumps to a freshly loaded payload.
 */
__attribute__((unused)) static inline void flush_cpu_icache(void)
{
	asm volatile ("fence.i" ::: "memory");
}

__attribute__((unused)) static inline void flush_cpu_dcache(void)
{
	asm volatile ("fence.i" ::: "memory");
}

void flush_l2_cache(void);
void busy_wait(unsigned int ms);
void busy_wait_us(unsigned int us);

#ifdef __cplusplus
}
#endif

#endif /* __SYSTEM_H */
