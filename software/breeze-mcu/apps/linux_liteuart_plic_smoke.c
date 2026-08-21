#include <stdint.h>

#include "breeze/csr.h"
#include "breeze/runtime.h"

#define LITEUART_RXTX       UINT64_C(0x12001000)
#define LITEUART_EV_ENABLE  UINT64_C(0x12001014)
#define LITEUART_EVENT_TX   UINT32_C(1)

#define PLIC_PRIORITY_10    UINT64_C(0x0c000028)
#define PLIC_ENABLE_M0      UINT64_C(0x0c002000)
#define PLIC_THRESHOLD_M0   UINT64_C(0x0c200000)
#define PLIC_CLAIM_M0       UINT64_C(0x0c200004)
#define PLIC_UART_SOURCE    UINT32_C(10)

static volatile uint32_t interrupt_seen;
static volatile uint32_t claimed_source;

static uint32_t read32(uint64_t address)
{
    return *(volatile uint32_t *)(uintptr_t)address;
}

static void write32(uint64_t address, uint32_t value)
{
    *(volatile uint32_t *)(uintptr_t)address = value;
}

void breeze_trap_handler(uint64_t mcause, uint64_t mepc, uint64_t mtval)
{
    (void)mepc;
    (void)mtval;
    if (mcause != BREEZE_MCAUSE_EXTERNAL) {
        breeze_fail();
    }

    claimed_source = read32(PLIC_CLAIM_M0);
    write32(LITEUART_EV_ENABLE, 0);
    write32(PLIC_CLAIM_M0, claimed_source);
    breeze_clear_mie(BREEZE_MIE_MEIE);
    interrupt_seen = 1;
}

int main(void)
{
    breeze_clear_mstatus(BREEZE_MSTATUS_MIE);
    write32(LITEUART_EV_ENABLE, 0);
    write32(PLIC_PRIORITY_10, 1);
    write32(PLIC_ENABLE_M0, UINT32_C(1) << PLIC_UART_SOURCE);
    write32(PLIC_THRESHOLD_M0, 0);

    interrupt_seen = 0;
    claimed_source = 0;
    breeze_set_mie(BREEZE_MIE_MEIE);
    write32(LITEUART_EV_ENABLE, LITEUART_EVENT_TX);
    breeze_set_mstatus(BREEZE_MSTATUS_MIE);

    while (interrupt_seen == 0u) {
        __asm__ volatile ("nop");
    }
    if (claimed_source != PLIC_UART_SOURCE ||
            read32(LITEUART_EV_ENABLE) != 0u) {
        return 1;
    }

    *(volatile uint8_t *)(uintptr_t)LITEUART_RXTX = (uint8_t)'P';
    return 0;
}
