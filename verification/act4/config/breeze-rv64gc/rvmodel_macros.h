// SPDX-License-Identifier: BSD-3-Clause
#ifndef _RVMODEL_MACROS_H
#define _RVMODEL_MACROS_H

#define RVMODEL_DATA_SECTION                                      \
        .pushsection .tohost,"aw",@progbits;                      \
        .balign 8; .global tohost; tohost: .dword 0;              \
        .balign 8; .global fromhost; fromhost: .dword 0;          \
        .popsection

#define RVMODEL_BOOT
#define RVMODEL_BOOT_TO_MMODE

#define RVMODEL_HALT_PASS                                         \
        li x1, 1;                                                 \
        la t0, tohost;                                            \
        sw x1, 0(t0);                                             \
1:      j 1b

#define RVMODEL_HALT_FAIL                                         \
        li x1, 3;                                                 \
        la t0, tohost;                                            \
        sw x1, 0(t0);                                             \
1:      j 1b

#define RVMODEL_IO_INIT(_R1, _R2, _R3)
#define RVMODEL_IO_WRITE_STR(_R1, _R2, _R3, _STR_PTR)

#define RVMODEL_ACCESS_FAULT_ADDRESS 0x00000000

/* ACT4 requires these declarations even when privileged tests are disabled.
 * Keep unsupported interrupt injection explicit: an accidental use must fail
 * at assembly time instead of compiling into a false certification result. */
#define RVMODEL_INTERRUPT_LATENCY 0
#define RVMODEL_TIMER_INT_SOON_DELAY 0

#define RVMODEL_SET_MEXT_INT(_R1, _R2) .error "Breeze ACT4 MEXT injection is not implemented"
#define RVMODEL_CLR_MEXT_INT(_R1, _R2) .error "Breeze ACT4 MEXT clearing is not implemented"
#define RVMODEL_SET_MSW_INT(_R1, _R2)  .error "Breeze ACT4 MSW injection is not implemented"
#define RVMODEL_CLR_MSW_INT(_R1, _R2)  .error "Breeze ACT4 MSW clearing is not implemented"
#define RVMODEL_SET_SEXT_INT(_R1, _R2) .error "Breeze ACT4 SEXT injection is not implemented"
#define RVMODEL_CLR_SEXT_INT(_R1, _R2) .error "Breeze ACT4 SEXT clearing is not implemented"
#define RVMODEL_SET_SSW_INT(_R1, _R2)  .error "Breeze ACT4 SSW injection is not implemented"
#define RVMODEL_CLR_SSW_INT(_R1, _R2)  .error "Breeze ACT4 SSW clearing is not implemented"

#endif
