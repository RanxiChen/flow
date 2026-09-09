#ifndef EP4CE10_MCU_SOC_H
#define EP4CE10_MCU_SOC_H

#include <stdint.h>

#define MCU_SYS_CLK_HZ             50000000u

#define CSR_CTRL_BASE              0x12000000u
#define CSR_UART_BASE              0x12001000u
#define CSR_TIMER0_BASE            0x12002000u
#define CSR_MATRIX_BASE            0x12003000u
#define CSR_GPIO_BASE              0x12004000u
#define CSR_SEG7_BASE              0x12005000u
#define CSR_WATCHDOG0_BASE         0x12006000u
#define CSR_SDRAM_BASE             0x12007000u

#define MAIN_RAM_BASE              0x80000000u
#define MAIN_RAM_SIZE              0x02000000u

#define UART_RXTX                  (CSR_UART_BASE + 0x00u)
#define UART_TXFULL                (CSR_UART_BASE + 0x04u)

#define TIMER0_LOAD                (CSR_TIMER0_BASE + 0x00u)
#define TIMER0_RELOAD              (CSR_TIMER0_BASE + 0x04u)
#define TIMER0_ENABLE              (CSR_TIMER0_BASE + 0x08u)
#define TIMER0_EV_PENDING          (CSR_TIMER0_BASE + 0x18u)
#define TIMER0_EV_ENABLE           (CSR_TIMER0_BASE + 0x1cu)

#define GPIO_OUT                   (CSR_GPIO_BASE + 0x00u)

#define SEG7_DIGITS                (CSR_SEG7_BASE + 0x00u)
#define SEG7_ENABLE                (CSR_SEG7_BASE + 0x04u)
#define SEG7_DOTS                  (CSR_SEG7_BASE + 0x08u)

#define MCAUSE_INTERRUPT           (UINT64_C(1) << 63)
#define MCAUSE_MACHINE_TIMER       7u
#define MCAUSE_MACHINE_EXTERNAL    11u

static inline void mmio_write32(uintptr_t address, uint32_t value)
{
    *(volatile uint32_t *)address = value;
}

static inline uint32_t mmio_read32(uintptr_t address)
{
    return *(volatile uint32_t *)address;
}

#endif
