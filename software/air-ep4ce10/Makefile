CROSS_COMPILE ?= riscv64-linux-gnu-
BUILD_DIR     ?= build
VARIANT       ?= i
TARGET         = $(BUILD_DIR)/$(VARIANT)/air-ep4ce10

CC      := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy
OBJDUMP := $(CROSS_COMPILE)objdump
READELF := $(CROSS_COMPILE)readelf

ifeq ($(VARIANT),ic)
MARCH = rv64ic_zicsr_zifencei
else ifeq ($(VARIANT),i)
MARCH = rv64i_zicsr_zifencei
else
$(error VARIANT must be i or ic)
endif

ARCH_FLAGS = -march=$(MARCH) -mabi=lp64 -mcmodel=medany
AS_FLAGS   = $(ARCH_FLAGS) -mno-relax -nostdlib -nostartfiles
LD_FLAGS   = $(AS_FLAGS) -no-pie -Wl,-T,link.ld -Wl,--gc-sections \
	-Wl,--build-id=none -Wl,-Map,$(TARGET).map

.PHONY: all variants clean check

all: $(TARGET).bin $(TARGET).dis check

variants:
	$(MAKE) VARIANT=i all
	$(MAKE) VARIANT=ic all

$(BUILD_DIR)/$(VARIANT):
	mkdir -p $@

$(BUILD_DIR)/$(VARIANT)/start.o: start.S | $(BUILD_DIR)/$(VARIANT)
	$(CC) $(AS_FLAGS) -c $< -o $@

$(TARGET).elf: $(BUILD_DIR)/$(VARIANT)/start.o link.ld
	$(CC) $(LD_FLAGS) $< -o $@

$(TARGET).bin: $(TARGET).elf
	$(OBJCOPY) -O binary $< $@

$(TARGET).dis: $(TARGET).elf
	$(OBJDUMP) -d $< > $@

check: $(TARGET).elf $(TARGET).dis
	@test "$$($(READELF) -h $(TARGET).elf | awk '/Entry point address:/ {print $$4}')" = "0x10000000"
	@! awk '/^[[:space:]]*[0-9a-f]+:/ {print $$3}' $(TARGET).dis | \
		grep -E '^(mul|mulh|mulhsu|mulhu|div|divu|rem|remu|lr\.|sc\.|amo|f[a-z])'

clean:
	rm -rf $(BUILD_DIR)
