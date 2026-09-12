/* SPDX-License-Identifier: BSD-2-Clause */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <generated/csr.h>
#include <liblitesdcard/sdcard.h>
#include <liblitesdcard/sdcard_flow.h>
#include <libfatfs/ff.h>
#include <libbase/crc.h>
#include "../command.h"
#include "../helpers.h"

static void status(int argc, char **argv)
{
    printf("SD present=%lu DMA error=%lu address=%08lx cmd=%lx data=%lx last_error=%d\n",
        (unsigned long)sd_dma_present_read(), (unsigned long)sd_dma_error_read(),
        (unsigned long)sd_dma_error_address_read(), (unsigned long)sdcard_core_cmd_event_read(),
        (unsigned long)sdcard_core_data_event_read(), flow_sd_error());
}
define_command(sd_status, status, "SD detect, command and DMA status", LITESDCARD_CMDS);

static void init(int argc, char **argv) { printf("SD init %s\n", sdcard_init() ? "OK" : "FAILED"); }
define_command(sdcard_init, init, "Initialize SD at 400 kHz then 5 MHz", LITESDCARD_CMDS);

static void read_block(int argc, char **argv)
{
    uint8_t buffer[512] __attribute__((aligned(8)));
    char *end;
    if (argc != 1) { printf("sdcard_read <sector>\n"); return; }
    unsigned long sector = strtoul(argv[0], &end, 0);
    if (*end || sector > 0xfffffffful) return;
    if (flow_sd_read(sector, 1, buffer) != SD_OK) { status(0, NULL); return; }
    printf("sector=%lu CRC32=%08lx\n", sector, (unsigned long)crc32(buffer, 512));
    dump_bytes((unsigned int *)buffer, 512, (unsigned long)buffer);
}
define_command(sdcard_read, read_block, "Read one SD sector and print CRC32", LITESDCARD_CMDS);

/* Incremental IEEE CRC32, matching Python zlib.crc32 and the BIOS crc command. */
static uint32_t crc_update(uint32_t crc, const uint8_t *data, unsigned size)
{
    while (size--) {
        crc ^= *data++;
        for (unsigned bit = 0; bit < 8; bit++) crc = (crc >> 1) ^ (0xedb88320u & -(crc & 1));
    }
    return crc;
}
static void file_crc(int argc, char **argv)
{
    FATFS fs;
    FIL file;
    UINT bytes;
    uint8_t buffer[512] __attribute__((aligned(8)));
    uint32_t crc = 0xffffffffu;
    unsigned long total = 0;
    FRESULT result;
    if (argc != 1) { printf("sd_file_crc <FAT filename>\n"); return; }
    fatfs_set_ops_sdcard();
    result = f_mount(&fs, "", 1);
    if (result != FR_OK) { printf("FAT mount failed: %d\n", result); return; }
    result = f_open(&file, argv[0], FA_READ);
    if (result == FR_OK) {
        for (;;) {
            result = f_read(&file, buffer, sizeof(buffer), &bytes);
            if (result != FR_OK || !bytes) break;
            crc = crc_update(crc, buffer, bytes);
            total += bytes;
        }
        f_close(&file);
        if (result == FR_OK) printf("%s bytes=%lu CRC32=%08lx\n", argv[0], total, (unsigned long)~crc);
    }
    if (result != FR_OK) printf("FAT read failed: %d SD error=%d\n", result, flow_sd_error());
    f_mount(NULL, "", 0);
}
define_command(sd_file_crc, file_crc, "Read a FAT file and compute CRC32", LITESDCARD_CMDS);
/* First-board BIOS intentionally exposes no SD erase/write command. */
