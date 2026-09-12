/* KCU105 native SD driver for the coherent Breeze DMA port.
 * SPDX-License-Identifier: BSD-2-Clause
 * Command encoding follows LiteSDCard's public CSR interface.
 */
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <generated/csr.h>
#include <generated/mem.h>
#include <generated/soc.h>
#include <system.h>
#include <libfatfs/ff.h>
#include <libfatfs/diskio.h>
#include "sdcard.h"
#include "sdcard_flow.h"

#ifdef CSR_SDCARD_BASE
static int initialized, block_addressed, last_error;
static uint8_t bounce[512] __attribute__((aligned(8)));
static void barrier(void) { __asm__ volatile("fence iorw,iorw" ::: "memory"); }

int flow_sd_error(void) { return last_error; }

static int wait_event(int data)
{
    for (unsigned i = 0; i < 250000; i++) {
        unsigned e = data ? sdcard_core_data_event_read() : sdcard_core_cmd_event_read();
        if (sd_dma_error_read()) return SD_WRITEERROR;
        if (e & 1) return (e & 4) ? SD_TIMEOUT : (e & (2|8)) ? SD_CRCERROR : SD_OK;
        busy_wait_us(10);
    }
    return SD_TIMEOUT;
}
int sdcard_wait_cmd_done(void) { return wait_event(0); }
int sdcard_wait_data_done(void) { return wait_event(1); }

static int command(uint32_t arg, unsigned cmd, unsigned flags)
{
    sdcard_core_cmd_argument_write(arg);
    sdcard_core_cmd_command_write((cmd << 8) | flags);
    sdcard_core_cmd_send_write(1);
    return sdcard_wait_cmd_done();
}
static uint32_t response(void)
{
    uint32_t words[4];
    csr_rd_buf_uint32(CSR_SDCARD_CORE_CMD_RESPONSE_ADDR, words, 4);
    return words[0];
}
static void print_response(const char *name)
{
    uint32_t w[4];
    csr_rd_buf_uint32(CSR_SDCARD_CORE_CMD_RESPONSE_ADDR, w, 4);
    printf("%s raw CSR words: %08lx %08lx %08lx %08lx\n", name,
        (unsigned long)w[0], (unsigned long)w[1], (unsigned long)w[2], (unsigned long)w[3]);
}

void sdcard_set_clk_freq(unsigned long frequency, int show)
{
    /* First-board profile: never exceed 5 MHz, even from the console. */
    if (!frequency) frequency = 400000;
    if (frequency > 5000000) frequency = 5000000;
    unsigned divider = (CONFIG_CLOCK_FREQUENCY + frequency - 1) / frequency;
    if (divider > 256) divider = 256;
    if (divider < 2) divider = 2;
    sdcard_phy_clocker_divider_write(divider);
    if (show) printf("SD clock %lu Hz\n", (unsigned long)CONFIG_CLOCK_FREQUENCY / ((divider + 1) & ~1));
}

#define R1 (SDCARD_CTRL_RESPONSE_SHORT | SDCARD_CTRL_RESPONSE_CRC)
#define R1B (SDCARD_CTRL_RESPONSE_SHORT_BUSY | SDCARD_CTRL_RESPONSE_CRC)
#define CHECK(call) do { last_error = (call); if (last_error != SD_OK) goto fail; } while (0)

int sdcard_init(void)
{
    unsigned rca;
    initialized = 0;
    last_error = SD_OK;
    sd_dma_reset_write(1);
    busy_wait_us(10);
    if (!sd_dma_present_read()) { last_error = SD_TIMEOUT; printf("SD: no card detected\n"); return 0; }
    sdcard_set_clk_freq(400000, 1);
    sdcard_phy_init_initialize_write(1);
    busy_wait(2);
    CHECK(command(0, 0, SDCARD_CTRL_RESPONSE_NONE));
    CHECK(command(0x1aa, 8, R1));
    if ((response() & 0xfff) != 0x1aa) { last_error = SD_CRCERROR; goto fail; }
    unsigned ocr = 0;
    for (unsigned retry = 0; retry < 1000; retry++) {
        CHECK(command(0, 55, R1));
        /* HCS, standard voltage window; do not request a 1.8 V switch. */
        CHECK(command(0x40ff8000, 41, SDCARD_CTRL_RESPONSE_SHORT));
        ocr = response();
        if (ocr & 0x80000000) break;
        busy_wait(1);
    }
    if (!(ocr & 0x80000000)) { last_error = SD_TIMEOUT; goto fail; }
    block_addressed = !!(ocr & 0x40000000);
    printf("SD OCR=%08lx, %s addressing\n", (unsigned long)ocr, block_addressed ? "sector" : "byte");
    CHECK(command(0, 2, SDCARD_CTRL_RESPONSE_LONG | SDCARD_CTRL_RESPONSE_CRC));
    print_response("CID");
    CHECK(command(0, 3, R1));
    rca = response() >> 16;
    if (!rca) { last_error = SD_CRCERROR; goto fail; }
    CHECK(command(rca << 16, 9, SDCARD_CTRL_RESPONSE_LONG | SDCARD_CTRL_RESPONSE_CRC));
    print_response("CSD");
    CHECK(command(rca << 16, 7, R1B));
    CHECK(command(rca << 16, 55, R1));
    CHECK(command(2, 6, R1)); /* ACMD6, four data wires */
    sdcard_phy_settings_write(SD_PHY_SPEED_4X);
    CHECK(command(512, 16, R1));
    sdcard_set_clk_freq(5000000, 1);
    initialized = 1;
    return 1;
fail:
    printf("SD initialization failed: error=%d cmd_event=%lx data_event=%lx\n",
        last_error, (unsigned long)sdcard_core_cmd_event_read(), (unsigned long)sdcard_core_data_event_read());
    return 0;
}

static int memory_range(const void *buf, uint32_t bytes)
{
    uint64_t p = (uintptr_t)buf, end = p + bytes;
    return end >= p && ((p >= SRAM_BASE && end <= (uint64_t)SRAM_BASE + SRAM_SIZE) ||
        (p >= MAIN_RAM_BASE && end <= (uint64_t)MAIN_RAM_BASE + MAIN_RAM_SIZE));
}

static int transfer(uint32_t sector, uint32_t count, uint8_t *buf, int write)
{
    int result = SD_OK;
    if (!initialized || !count || count > 0xffffffffu / 512 ||
        !memory_range(buf, count * 512) || (uint64_t)sector + count > 0x100000000ull ||
        (!block_addressed && (uint64_t)sector + count > 0x800000ull))
        return last_error = SD_WRITEERROR;
    while (count) {
        int unaligned = (uintptr_t)buf & 7;
        uint32_t blocks = unaligned ? 1 : (count > 64 ? 64 : count);
        uint8_t *dma_buf = unaligned ? bounce : buf;
        if (write && unaligned) memcpy(bounce, buf, 512);
        barrier();
        if (write) {
            sdcard_mem2block_dma_enable_write(0);
            sdcard_mem2block_dma_base_write((uintptr_t)dma_buf);
            sdcard_mem2block_dma_length_write(512 * blocks);
            sdcard_mem2block_dma_enable_write(1);
        } else {
            sdcard_block2mem_dma_enable_write(0);
            sdcard_block2mem_dma_base_write((uintptr_t)dma_buf);
            sdcard_block2mem_dma_length_write(512 * blocks);
            sdcard_block2mem_dma_enable_write(1);
        }
        sdcard_core_block_length_write(512);
        sdcard_core_block_count_write(blocks);
        result = command(block_addressed ? sector : sector * 512,
            write ? (blocks > 1 ? 25 : 24) : (blocks > 1 ? 18 : 17),
            R1 | ((write ? SDCARD_CTRL_DATA_TRANSFER_WRITE : SDCARD_CTRL_DATA_TRANSFER_READ) << 5));
        if (result == SD_OK && (response() & 0xfdffe008u)) result = SD_WRITEERROR;
        if (result == SD_OK) result = sdcard_wait_data_done();
        if (result == SD_OK) {
            unsigned retry;
            for (retry = 0; retry < 250000; retry++) {
                if (sd_dma_error_read()) { result = SD_WRITEERROR; break; }
                if (write ? sdcard_mem2block_dma_done_read() : sdcard_block2mem_dma_done_read()) break;
                busy_wait_us(10);
            }
            if (retry == 250000) result = SD_TIMEOUT;
        }
        if (blocks > 1) {
            int stop_result = command(0, 12, R1B);
            if (result == SD_OK) result = stop_result;
        }
        sdcard_mem2block_dma_enable_write(0);
        sdcard_block2mem_dma_enable_write(0);
        barrier();
        if (result != SD_OK) {
            printf("SD %s failed at sector %lu: error=%d DMA=%lu\n", write ? "write" : "read",
                (unsigned long)sector, result, (unsigned long)sd_dma_error_read());
            initialized = 0;
            sd_dma_reset_write(1); /* discard incomplete FIFO/PHY transaction */
            break;
        }
        if (!write && unaligned) memcpy(buf, bounce, 512);
        buf += 512 * blocks; sector += blocks; count -= blocks;
    }
    return last_error = result;
}
int flow_sd_read(uint32_t sector, uint32_t count, uint8_t *buf) { return transfer(sector, count, buf, 0); }
void sdcard_read(uint32_t sector, uint32_t count, uint8_t *buf) { (void)flow_sd_read(sector, count, buf); }
void sdcard_write(uint32_t sector, uint32_t count, uint8_t *buf) { (void)transfer(sector, count, buf, 1); }

static DSTATUS flow_disk_status(BYTE drive) { return drive || !initialized ? STA_NOINIT : 0; }
static DSTATUS flow_disk_init(BYTE drive) { return drive ? STA_NOINIT : initialized || sdcard_init() ? 0 : STA_NOINIT; }
static DRESULT flow_disk_read(BYTE drive, BYTE *buf, LBA_t sector, UINT count)
{
    if (drive || (uint64_t)sector > 0xffffffffull) return RES_PARERR;
    return flow_sd_read(sector, count, buf) == SD_OK ? RES_OK : RES_ERROR;
}
static DISKOPS ops = { .disk_initialize = flow_disk_init, .disk_status = flow_disk_status, .disk_read = flow_disk_read };
void fatfs_set_ops_sdcard(void) { FfDiskOps = &ops; }
#endif
