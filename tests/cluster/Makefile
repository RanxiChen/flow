# Cluster self-check programs (docs/tasks/CLUSTER-sim-abi.md §3).
#   make -C tests/cluster                      # riscv64-unknown-elf-
#   make -C tests/cluster RISCV_PREFIX=riscv64-linux-gnu-
RISCV_PREFIX ?= riscv64-unknown-elf-
CC := $(RISCV_PREFIX)gcc
OBJDUMP := $(RISCV_PREFIX)objdump
# No C: trap handlers skip faulting instructions with epc + 4.
CFLAGS := -march=rv64ima_zicsr_zifencei -mabi=lp64 -mcmodel=medany -fno-pic -fno-pie \
  -static -nostdlib -nostartfiles -no-pie -Wl,--build-id=none -Wl,--no-relax -T link.ld -I.

SINGLE := fencei_smc sv39_basic sv39_ptw_cache trap_misc mmio_console timer_irq lrsc_amo_single
MULTI := mh_amo_lock mh_lrsc_counter mh_message_pass mh_ipi mh_sv39 mh_fencei
HARTS := 2 4

ELFS := $(SINGLE:%=build/%.elf) $(foreach n,$(HARTS),$(MULTI:%=build/%_$(n).elf))

all: $(ELFS) $(ELFS:.elf=.dump)

build/%.elf: %.S common.h link.ld | build
	$(CC) $(CFLAGS) -DNHARTS=1 -o $@ $<

define multi_rule
build/%_$(1).elf: %.S common.h link.ld | build
	$$(CC) $$(CFLAGS) -DNHARTS=$(1) -o $$@ $$<
endef
$(foreach n,$(HARTS),$(eval $(call multi_rule,$(n))))

build/%.dump: build/%.elf
	$(OBJDUMP) -d $< > $@

build:
	mkdir -p build

# Local logic check only (not the harness ABI): qemu virt, sifive_test exit.
QEMU_ELFS := $(SINGLE:%=build/qemu/%.elf) $(foreach n,$(HARTS),$(MULTI:%=build/qemu/%_$(n).elf))
qemu: $(QEMU_ELFS)
build/qemu/%.elf: %.S common.h link.ld
	@mkdir -p build/qemu
	$(CC) $(CFLAGS) -DQEMU -DNHARTS=1 -o $@ $<
define qemu_rule
build/qemu/%_$(1).elf: %.S common.h link.ld
	@mkdir -p build/qemu
	$$(CC) $$(CFLAGS) -DQEMU -DNHARTS=$(1) -o $$@ $$<
endef
$(foreach n,$(HARTS),$(eval $(call qemu_rule,$(n))))

clean:
	rm -rf build

.PHONY: all clean qemu
.SECONDARY:
