CROSS_COMPILE ?= riscv64-unknown-elf-
BUILD_DIR     ?= build
TARGET         = $(BUILD_DIR)/breeze-smoke

CC      := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy
OBJDUMP := $(CROSS_COMPILE)objdump

ARCH_FLAGS = -march=rv64i_zicsr_zifencei -mabi=lp64 -mcmodel=medany
COMMON_FLAGS = $(ARCH_FLAGS) -mno-relax -msmall-data-limit=0 \
	-ffreestanding -fno-builtin -fno-pic -fno-pie \
	-ffunction-sections -fdata-sections -Os -Wall -Wextra
LINK_FLAGS = $(ARCH_FLAGS) -mno-relax -nostdlib -nostartfiles -no-pie \
	-Wl,-T,link.ld -Wl,--gc-sections -Wl,-Map,$(TARGET).map

.PHONY: all clean

all: $(TARGET).bin $(TARGET).dis

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/start.o: start.S | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -c $< -o $@

$(BUILD_DIR)/main.o: main.c | $(BUILD_DIR)
	$(CC) $(COMMON_FLAGS) -std=c11 -c $< -o $@

$(TARGET).elf: $(BUILD_DIR)/start.o $(BUILD_DIR)/main.o link.ld
	$(CC) $(LINK_FLAGS) $(BUILD_DIR)/start.o $(BUILD_DIR)/main.o -o $@

$(TARGET).bin: $(TARGET).elf
	$(OBJCOPY) -O binary $< $@

$(TARGET).dis: $(TARGET).elf
	$(OBJDUMP) -d -S $< > $@

clean:
	rm -rf $(BUILD_DIR)
