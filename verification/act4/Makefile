ACT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
UPSTREAM_DIR ?= $(ACT_DIR)/.upstream
WORK_DIR ?= $(ACT_DIR)/.work
OUT_DIR ?= $(ACT_DIR)/out
ELF_DIR ?= $(WORK_DIR)/breeze-rv64gc/elfs
EXTENSIONS ?= I
MAX_CYCLES ?= 1000000
SOC_A_OUT_DIR ?= $(OUT_DIR)/linux-soc-a
SOC_MAX_CYCLES ?= 2000000
SOC_JOBS ?= 20

.PHONY: fetch build run platform run-a-linux-soc clean-output

fetch:
	ACT4_UPSTREAM_DIR=$(UPSTREAM_DIR) ./scripts/fetch_act4.sh

build:
	ACT4_UPSTREAM_DIR=$(UPSTREAM_DIR) ACT4_WORK_DIR=$(WORK_DIR) \
	ACT4_EXTENSIONS=$(EXTENSIONS) ./scripts/build_tests.sh

run:
	python3 ./scripts/run_suite.py $(ELF_DIR) \
		--output-dir $(OUT_DIR) --max-cycles $(MAX_CYCLES) --keep-going

platform:
	$(MAKE) -C platform

run-a-linux-soc: platform
	python3 ./scripts/run_a_linux_soc.py \
		--elf-dir $(ELF_DIR) \
		--output-dir $(SOC_A_OUT_DIR) \
		--max-cycles $(SOC_MAX_CYCLES) \
		--jobs $(SOC_JOBS) --fresh-build

clean-output:
	rm -rf $(OUT_DIR)
