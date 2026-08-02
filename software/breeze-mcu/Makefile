CROSS_COMPILE ?= riscv64-unknown-elf-
BUILD_DIR     ?= build
MAIN          ?= apps/main.c
MTVEC_MODE    ?= 0
TARGET         = $(BUILD_DIR)/breeze-mcu

CC      := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy
OBJDUMP := $(CROSS_COMPILE)objdump
NM      := $(CROSS_COMPILE)nm

ARCH_FLAGS = -march=rv64i_zicsr_zifencei -mabi=lp64 -mcmodel=medany
COMMON_FLAGS = $(ARCH_FLAGS) -mno-relax -msmall-data-limit=0 \
	-ffreestanding -fno-builtin -fno-pic -fno-pie \
	-ffunction-sections -fdata-sections -Os -Wall -Wextra \
	-Iinclude -DBREEZE_MTVEC_MODE=$(MTVEC_MODE)
LINK_FLAGS = $(ARCH_FLAGS) -mno-relax -nostdlib -nostartfiles -no-pie \
	-Wl,-T,link.ld -Wl,--gc-sections -Wl,-Map,$(TARGET).map

OBJECTS = \
	$(BUILD_DIR)/start.o \
	$(BUILD_DIR)/trap_entry.o \
	$(BUILD_DIR)/runtime.o \
	$(BUILD_DIR)/uart.o \
	$(BUILD_DIR)/timer.o \
	$(BUILD_DIR)/main.o

.PHONY: all clean

all: $(TARGET).bin $(TARGET).dis $(TARGET).sym

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/start.o: runtime/start.S | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -c $< -o $@

$(BUILD_DIR)/trap_entry.o: runtime/trap_entry.S | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -c $< -o $@

$(BUILD_DIR)/runtime.o: runtime/runtime.c | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -std=c11 -c $< -o $@

$(BUILD_DIR)/uart.o: lib/uart.c | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -std=c11 -c $< -o $@

$(BUILD_DIR)/timer.o: lib/timer.c | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -std=c11 -c $< -o $@

$(BUILD_DIR)/main.o: $(MAIN) | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -std=c11 -c $< -o $@

$(TARGET).elf: $(OBJECTS) link.ld
	$(CC) $(LINK_FLAGS) $(OBJECTS) -o $@

$(TARGET).bin: $(TARGET).elf
	$(OBJCOPY) -O binary $< $@

$(TARGET).dis: $(TARGET).elf
	$(OBJDUMP) -d -S $< > $@

$(TARGET).sym: $(TARGET).elf
	$(NM) -n $< > $@

clean:
	rm -rf $(BUILD_DIR)
