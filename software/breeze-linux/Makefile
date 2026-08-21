CROSS_COMPILE ?= riscv64-linux-gnu-
CC := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy
DTC ?= dtc
BUILD := build

.PHONY: all clean

all: $(BUILD)/bootrom.bin $(BUILD)/handoff-smoke.bin $(BUILD)/flow-small.dtb

$(BUILD):
	mkdir -p $@

$(BUILD)/bootrom.elf: bootrom.S bootrom.ld | $(BUILD)
	$(CC) -march=rv64im_zicsr -mabi=lp64 -nostdlib -nostartfiles \
		-static -no-pie -Wl,--build-id=none -T bootrom.ld -o $@ bootrom.S

$(BUILD)/bootrom.bin: $(BUILD)/bootrom.elf
	$(OBJCOPY) -O binary $< $@

$(BUILD)/handoff-smoke.elf: handoff-smoke.S handoff-smoke.ld | $(BUILD)
	$(CC) -march=rv64im_zicsr -mabi=lp64 -nostdlib -nostartfiles \
		-static -no-pie -Wl,--build-id=none -T handoff-smoke.ld -o $@ handoff-smoke.S

$(BUILD)/handoff-smoke.bin: $(BUILD)/handoff-smoke.elf
	$(OBJCOPY) -O binary $< $@

$(BUILD)/flow-small.dtb: flow-small.dts | $(BUILD)
	$(DTC) -I dts -O dtb -o $@ $<

clean:
	rm -rf $(BUILD)
