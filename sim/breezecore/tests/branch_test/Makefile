FLOW_ROOT := ../../../..
DESIGN_DIR := $(abspath $(FLOW_ROOT)/design)

TEST_NAME := branch_test
TEST_DIR := $(abspath .)
GEN_DIR := $(TEST_DIR)/generated
OUT_DIR := $(TEST_DIR)/out

RISCV_PREFIX ?= riscv64-unknown-elf
CC := $(RISCV_PREFIX)-gcc
OBJDUMP := $(RISCV_PREFIX)-objdump

ARCH ?= rv64im
ABI ?= lp64
TANDEM_LOG ?= true
CORE_PRESET ?= baseline

ASM_SRC := $(TEST_DIR)/$(TEST_NAME).S
LD_SCRIPT := $(TEST_DIR)/$(TEST_NAME).ld
ELF := $(GEN_DIR)/$(TEST_NAME).elf
DUMP := $(GEN_DIR)/$(TEST_NAME).dump
JSON := $(GEN_DIR)/$(TEST_NAME).json

.PHONY: all elf dump json run clean

all: json

$(GEN_DIR) $(OUT_DIR):
	mkdir -p $@

elf: $(ELF)

$(ELF): $(ASM_SRC) $(LD_SCRIPT) | $(GEN_DIR)
	$(CC) -nostdlib -nostartfiles -static \
		-march=$(ARCH) -mabi=$(ABI) \
		-T $(LD_SCRIPT) \
		-o $@ $(ASM_SRC)

dump: $(DUMP)

$(DUMP): $(ELF)
	$(OBJDUMP) -d -M no-aliases $< > $@

json: $(JSON)

$(JSON): $(ELF) $(TEST_DIR)/gen_case.py
	python3 $(TEST_DIR)/gen_case.py \
		--elf $(ELF) \
		--output $@ \
		--tandem-log $(TANDEM_LOG)

run: $(JSON) | $(OUT_DIR)
	cd $(DESIGN_DIR) && \
	sbt "runMain flow.sim.BreezeCoreSimApp $(JSON) $(CORE_PRESET)" | tee $(OUT_DIR)/$(TEST_NAME).run.log

clean:
	rm -f $(ELF) $(DUMP) $(JSON) $(OUT_DIR)/$(TEST_NAME).run.log
