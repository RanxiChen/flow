#ifndef FLOW_SDCARD_H
#define FLOW_SDCARD_H
#include <stdint.h>
int flow_sd_error(void);
int flow_sd_read(uint32_t sector, uint32_t count, uint8_t *buf);
#endif
