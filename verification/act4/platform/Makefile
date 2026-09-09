TOOLCHAIN_ROOT ?= /home/chen/opt/act4/gcc-2026.07.15
CROSS_COMPILE ?= $(TOOLCHAIN_ROOT)/bin/riscv64-unknown-elf-
CC := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy
BUILD := build

.PHONY: all clean

all: $(BUILD)/bootrom.bin

$(BUILD):
	mkdir -p $@

$(BUILD)/bootrom.elf: bootrom.S bootrom.ld | $(BUILD)
	$(CC) -march=rv64im_zicsr -mabi=lp64 -nostdlib -nostartfiles \
		-static -no-pie -Wl,--build-id=none -T bootrom.ld -o $@ bootrom.S

$(BUILD)/bootrom.bin: $(BUILD)/bootrom.elf
	$(OBJCOPY) -O binary $< $@

clean:
	rm -rf $(BUILD)
