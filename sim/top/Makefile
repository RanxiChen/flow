FLOW_ROOT ?= ../../
DESIGN_PRJ := $(FLOW_ROOT)/design
V_FILES_DIR := $(DESIGN_PRJ)/build

verilog:$(DESIGN_PRJ)/src/main/scala/top/top.scala
	rm -rf $(DESIGN_PRJ)/build
	cd $(DESIGN_PRJ) && sbt "runMain top.GenerateTop"

verilator_files:$(V_FILES_DIR)/filelist.f
	verilator --cc -f $< --top-module flow_top -I$(V_FILES_DIR) -DENABLE_INITIAL_MEM_

build:
	verilator --trace \
	--cc -f $(V_FILES_DIR)/filelist.f --top-module flow_top  \
	--exe main.cpp -DENABLE_INITIAL_MEM_ \
	-CFLAGS  "-I$(FLOW_ROOT)/csrc -I$(FLOW_ROOT)/include -Iobj_dir" \
	-I$(V_FILES_DIR)
exe:build
	make -C obj_dir -f Vflow_top.mk Vflow_top

run:exe
	@./obj_dir/Vflow_top 2>&1 | tee flow_top.log
	@grep "Stats" flow_top.log > inst_stats.log
	@grep -E "PiPe|Probe|SRAM" flow_top.log > pipe_stats.log
	@grep -v -E "Stats|PiPe|Probe|INFO|SRAM" flow_top.log > rf_stats.log
	@grep "DEBUG" flow_top.log > retire.log
	@awk '{print $$2,$$8}' retire.log > cycle_inst.raw
	@grep "INFO" flow_top.log > info.log

sync:
	cp $(FLOW_ROOT)/tests/top_asm/*.hex ./

patch:
	sed -E 's/mem\.hex/test02\.hex/g' $(FLOW_ROOT)/design/build/*.sv -i

clean:
	rm -rf $(V_FILES_DIR) obj_dir 
	rm -rf *.vcd
	rm -rf *.log

rebuild: verilog run