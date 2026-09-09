CROSS_COMPILE ?= riscv64-linux-gnu-
BUILD_DIR     ?= build
TARGET         = $(BUILD_DIR)/wisp-ep4ce10

CC      := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy
OBJDUMP := $(CROSS_COMPILE)objdump
READELF := $(CROSS_COMPILE)readelf

ARCH_FLAGS = -march=rv64i_zicsr_zifencei -mabi=lp64 -mcmodel=medany
AS_FLAGS   = $(ARCH_FLAGS) -mno-relax -nostdlib -nostartfiles
LD_FLAGS   = $(AS_FLAGS) -no-pie -Wl,-T,link.ld -Wl,--gc-sections \
	-Wl,--build-id=none -Wl,-Map,$(TARGET).map

.PHONY: all clean check

all: $(TARGET).bin $(TARGET).dis check

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/start.o: start.S | $(BUILD_DIR)
	$(CC) $(AS_FLAGS) -c $< -o $@

$(TARGET).elf: $(BUILD_DIR)/start.o link.ld
	$(CC) $(LD_FLAGS) $< -o $@

$(TARGET).bin: $(TARGET).elf
	$(OBJCOPY) -O binary $< $@

$(TARGET).dis: $(TARGET).elf
	$(OBJDUMP) -d $< > $@

check: $(TARGET).elf $(TARGET).dis
	@test "$$($(READELF) -h $(TARGET).elf | awk '/Entry point address:/ {print $$4}')" = "0x10000000"
	@! awk '/^[[:space:]]*[0-9a-f]+:/ {print $$3}' $(TARGET).dis | \
		grep -E '^(mul|mulh|mulhsu|mulhu|div|divu|rem|remu|lr\.|sc\.|amo|f[a-z]|c\.)'

clean:
	rm -rf $(BUILD_DIR)
