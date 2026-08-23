CROSS_COMPILE ?= riscv64-linux-gnu-
BUILD_DIR     ?= build
TARGET         = $(BUILD_DIR)/breeze-tiny-ep4ce10

CC      := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy
OBJDUMP := $(CROSS_COMPILE)objdump
NM      := $(CROSS_COMPILE)nm

ARCH_FLAGS = -march=rv64i_zicsr_zifencei -mabi=lp64 -mcmodel=medany
COMMON_FLAGS = $(ARCH_FLAGS) -mno-relax -msmall-data-limit=0 \
	-ffreestanding -fno-builtin -fno-pic -fno-pie \
	-ffunction-sections -fdata-sections -Os -Wall -Wextra
LINK_FLAGS = $(ARCH_FLAGS) -mno-relax -nostdlib -nostartfiles -no-pie \
	-Wl,-T,link.ld -Wl,--build-id=none -Wl,--gc-sections \
	-Wl,-Map,$(TARGET).map

OBJECTS = \
	$(BUILD_DIR)/start.o \
	$(BUILD_DIR)/trap_entry.o \
	$(BUILD_DIR)/main.o

.PHONY: all

all: $(TARGET).bin $(TARGET).dis $(TARGET).sym

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/start.o: start.S | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -c $< -o $@

$(BUILD_DIR)/trap_entry.o: trap_entry.S | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -c $< -o $@

$(BUILD_DIR)/main.o: main.c | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -std=c11 -c $< -o $@

$(TARGET).elf: $(OBJECTS) link.ld Makefile
	$(CC) $(LINK_FLAGS) $(OBJECTS) -o $@

$(TARGET).bin: $(TARGET).elf
	$(OBJCOPY) -O binary $< $@

$(TARGET).dis: $(TARGET).elf
	$(OBJDUMP) -d -S $< > $@

$(TARGET).sym: $(TARGET).elf
	$(NM) -n $< > $@
