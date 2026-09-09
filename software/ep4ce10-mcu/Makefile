CROSS_COMPILE ?= riscv64-linux-gnu-
CORE          ?= air-ic
BUILD_DIR     ?= build/$(CORE)
TARGET         = $(BUILD_DIR)/led-timer-irq

CC      := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy
OBJDUMP := $(CROSS_COMPILE)objdump
READELF := $(CROSS_COMPILE)readelf

ifeq ($(CORE),air-ic)
MARCH = rv64ic_zicsr_zifencei
else ifeq ($(CORE),air-i)
MARCH = rv64i_zicsr_zifencei
else ifeq ($(CORE),wisp)
MARCH = rv64i_zicsr_zifencei
else
$(error CORE must be air-i, air-ic or wisp)
endif

ARCH_FLAGS = -march=$(MARCH) -mabi=lp64 -mcmodel=medany -mno-relax
CFLAGS     = $(ARCH_FLAGS) -Os -g -ffreestanding -fno-builtin -fno-pic -fno-pie \
	-fno-stack-protector -ffunction-sections -fdata-sections -Wall -Wextra \
	-Iinclude -DMCU_CORE_$(shell echo $(CORE) | tr 'a-z-' 'A-Z_')
ASFLAGS    = $(ARCH_FLAGS) -g -ffreestanding -fno-pic -fno-pie -nostdlib -nostartfiles
LDFLAGS    = $(ARCH_FLAGS) -nostdlib -nostartfiles -no-pie -Wl,-T,link.ld \
	-Wl,--gc-sections -Wl,--build-id=none -Wl,-Map,$(TARGET).map

OBJECTS = $(BUILD_DIR)/start.o $(BUILD_DIR)/trap.o $(BUILD_DIR)/main.o
ifeq ($(CORE),wisp)
OBJECTS += $(BUILD_DIR)/sdram.o
endif

.PHONY: all variants clean check

all: $(TARGET).bin $(TARGET).dis check

variants:
	$(MAKE) CORE=air-i all
	$(MAKE) CORE=air-ic all
	$(MAKE) CORE=wisp all

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/%.o: %.S | $(BUILD_DIR)
	$(CC) $(ASFLAGS) -c $< -o $@

$(BUILD_DIR)/%.o: %.c | $(BUILD_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(TARGET).elf: $(OBJECTS) link.ld
	$(CC) $(LDFLAGS) $(OBJECTS) -o $@

$(TARGET).bin: $(TARGET).elf
	$(OBJCOPY) -O binary $< $@

$(TARGET).dis: $(TARGET).elf
	$(OBJDUMP) -d $< > $@

check: $(TARGET).elf $(TARGET).dis
	@test "$$($(READELF) -h $(TARGET).elf | awk '/Entry point address:/ {print $$4}')" = "0x10000000"
	@! awk '/^[[:space:]]*[0-9a-f]+:/ {print $$3}' $(TARGET).dis | \
		grep -E '^(mul|mulh|mulhsu|mulhu|div|divu|rem|remu|lr\.|sc\.|amo|f[a-z])'

clean:
	rm -rf build
