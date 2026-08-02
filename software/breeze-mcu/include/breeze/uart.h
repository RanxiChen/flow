#ifndef BREEZE_UART_H
#define BREEZE_UART_H

#include <stdint.h>

void breeze_uart_putc(char value);
void breeze_uart_puts(const char *text);
void breeze_uart_put_hex64(uint64_t value);
void breeze_uart_set_event_enable(uint32_t mask);
uint32_t breeze_uart_get_event_enable(void);

#endif
