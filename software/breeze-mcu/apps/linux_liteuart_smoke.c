#include <stdint.h>

#define LITEUART_RXTX       UINT64_C(0x12001000)
#define LITEUART_TXFULL     UINT64_C(0x12001004)
#define LITEUART_RXEMPTY    UINT64_C(0x12001008)
#define LITEUART_EV_ENABLE  UINT64_C(0x12001014)

static uint8_t read8(uint64_t address)
{
    return *(volatile uint8_t *)(uintptr_t)address;
}

static void write8(uint64_t address, uint8_t value)
{
    *(volatile uint8_t *)(uintptr_t)address = value;
}

int main(void)
{
    /* This mirrors the byte accesses used by OpenSBI's LiteUART driver. */
    write8(LITEUART_EV_ENABLE, 0);
    if (read8(LITEUART_TXFULL) != 0u || read8(LITEUART_RXEMPTY) != 1u ||
            read8(LITEUART_EV_ENABLE) != 0u) {
        return 1;
    }
    write8(LITEUART_RXTX, (uint8_t)'L');
    return 0;
}
