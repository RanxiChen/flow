#include <stdint.h>

#include "soc.h"
#include "sdram.h"

/* Fixed by WispSoC.csr_map["sdram"] = 7. */
#define DFII_CONTROL               (CSR_SDRAM_BASE + 0x00u)
#define DFII_PI0_COMMAND           (CSR_SDRAM_BASE + 0x04u)
#define DFII_PI0_COMMAND_ISSUE     (CSR_SDRAM_BASE + 0x08u)
#define DFII_PI0_ADDRESS           (CSR_SDRAM_BASE + 0x0cu)
#define DFII_PI0_BADDRESS          (CSR_SDRAM_BASE + 0x10u)

#define DFII_CONTROL_SEL           0x01u
#define DFII_CONTROL_CKE           0x02u
#define DFII_CONTROL_ODT           0x04u
#define DFII_CONTROL_RESET_N       0x08u

#define DFII_COMMAND_CS            0x01u
#define DFII_COMMAND_WE            0x02u
#define DFII_COMMAND_CAS           0x04u
#define DFII_COMMAND_RAS           0x08u

static void delay_nops(uint32_t count)
{
    while (count-- != 0u) __asm__ volatile ("nop");
}

static void dfii_command(uint32_t address, uint32_t bank, uint32_t command)
{
    mmio_write32(DFII_PI0_ADDRESS, address);
    mmio_write32(DFII_PI0_BADDRESS, bank);
    mmio_write32(DFII_PI0_COMMAND, command);
    mmio_write32(DFII_PI0_COMMAND_ISSUE, 1u);
}

static void sdram_init(void)
{
    const uint32_t precharge_all = DFII_COMMAND_RAS | DFII_COMMAND_WE |
        DFII_COMMAND_CS;
    const uint32_t auto_refresh = DFII_COMMAND_RAS | DFII_COMMAND_CAS |
        DFII_COMMAND_CS;
    const uint32_t mode_register = DFII_COMMAND_RAS | DFII_COMMAND_CAS |
        DFII_COMMAND_WE | DFII_COMMAND_CS;

    /* Software owns DFI during JEDEC power-up initialization. */
    mmio_write32(DFII_CONTROL,
        DFII_CONTROL_CKE | DFII_CONTROL_ODT | DFII_CONTROL_RESET_N);
    delay_nops(20000u);

    dfii_command(0x0400u, 0u, precharge_all);
    dfii_command(0x0120u, 0u, mode_register);
    delay_nops(200u);
    dfii_command(0x0400u, 0u, precharge_all);
    for (uint32_t i = 0; i < 8u; i++) {
        dfii_command(0u, 0u, auto_refresh);
        delay_nops(4u);
    }
    /* Sequential burst length 1, CAS latency 2. */
    dfii_command(0x0020u, 0u, mode_register);
    delay_nops(200u);

    /* Return DFI to the LiteDRAM controller/refresher. */
    mmio_write32(DFII_CONTROL, DFII_CONTROL_SEL);
}

static int sdram_memtest(void)
{
    volatile uint32_t *const base = (volatile uint32_t *)(uintptr_t)MAIN_RAM_BASE;
    static const uint32_t patterns[] = {
        0x00000000u, 0xffffffffu, 0x55aa33ccu, 0xaa55cc33u
    };

    for (uint32_t i = 0; i < sizeof(patterns) / sizeof(patterns[0]); i++) {
        base[0] = patterns[i];
        if (base[0] != patterns[i]) return 1;
    }

    /* Touch every external address bit used by the 32 MiB window. */
    for (uint32_t offset = 1u; offset < MAIN_RAM_SIZE / sizeof(uint32_t);
         offset <<= 1) {
        base[offset] = 0x5a000000u ^ offset;
    }
    for (uint32_t offset = 1u; offset < MAIN_RAM_SIZE / sizeof(uint32_t);
         offset <<= 1) {
        if (base[offset] != (0x5a000000u ^ offset)) return 2;
    }

    volatile uint64_t *const wide = (volatile uint64_t *)(uintptr_t)
        (MAIN_RAM_BASE + 0x100u);
    *wide = UINT64_C(0x0123456789abcdef);
    if (*wide != UINT64_C(0x0123456789abcdef)) return 3;

    volatile uint8_t *const bytes = (volatile uint8_t *)(uintptr_t)
        (MAIN_RAM_BASE + 0x110u);
    for (uint32_t i = 0; i < 8u; i++) bytes[i] = (uint8_t)(0xa0u + i);
    for (uint32_t i = 0; i < 8u; i++) {
        if (bytes[i] != (uint8_t)(0xa0u + i)) return 4;
    }
    return 0;
}

int sdram_init_and_memtest(void)
{
    sdram_init();
    return sdram_memtest();
}
