FLOW_ROOT := ../../../..
DESIGN_DIR := $(abspath $(FLOW_ROOT)/design)

TEST_DIR := $(abspath .)
GEN_DIR := $(TEST_DIR)/generated
OUT_DIR := $(TEST_DIR)/out

RISCV_PREFIX ?= riscv64-unknown-elf
CC := $(RISCV_PREFIX)-gcc
OBJDUMP := $(RISCV_PREFIX)-objdump

ARCH ?= rv64im_zicsr
ABI ?= lp64
TANDEM_LOG ?= true
CORE_PRESET ?= baseline

LD_SCRIPT := $(TEST_DIR)/trap_valid_bug_test.ld

# All test names (stem of .S files)
TESTS := trap_valid_bug_test inst_access_fault_test decoder_zero_inst_test addi_jalr_hazard_test

# ---- Generic build rules ----
.PHONY: all clean run_all $(addprefix run_,$(TESTS)) $(addprefix dump_,$(TESTS))

all: $(addprefix $(GEN_DIR)/,$(addsuffix .json,$(TESTS)))

# ---- Per-test build ----
define test_template
$(GEN_DIR)/$(1).elf: $(TEST_DIR)/$(1).S $(LD_SCRIPT) | $(GEN_DIR)
	$(CC) -nostdlib -nostartfiles -static \
		-march=$(ARCH) -mabi=$(ABI) \
		-T $(LD_SCRIPT) \
		-o $$@ $(TEST_DIR)/$(1).S

$(GEN_DIR)/$(1).json: $(GEN_DIR)/$(1).elf $(TEST_DIR)/gen_case.py
	python3 $(TEST_DIR)/gen_case.py \
		--elf $(GEN_DIR)/$(1).elf \
		--output $$@ \
		--tandem-log $(TANDEM_LOG)

run_$(1): $(GEN_DIR)/$(1).json | $(OUT_DIR)
	cd $(DESIGN_DIR) && \
	sbt "runMain flow.sim.BreezeCoreSimApp $(GEN_DIR)/$(1).json $(CORE_PRESET)" | tee $(OUT_DIR)/$(1).run.log

dump_$(1): $(GEN_DIR)/$(1).elf
	$(OBJDUMP) -d -M no-aliases $$< > $(GEN_DIR)/$(1).dump
endef

$(foreach t,$(TESTS),$(eval $(call test_template,$(t))))

# ---- Convenience targets ----
run_all: $(addprefix run_,$(TESTS))

dump: $(addprefix dump_,$(TESTS))

# ---- Common ----
$(GEN_DIR) $(OUT_DIR):
	mkdir -p $@

clean:
	rm -rf $(GEN_DIR) $(OUT_DIR)
