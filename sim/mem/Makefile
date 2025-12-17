FLOW_ROOT ?= ../../..
sram_v:
	rm -rf $(FLOW_ROOT)/design/build
	cd $(FLOW_ROOT)/design && sbt "runMain mem.GenerateSRAMMem"
	sed -E  -i 's/mem\.hex/mem01\.hex/g' $(FLOW_ROOT)/design/build/*.sv
	verilator --cc -f $(FLOW_ROOT)/design/build/filelist.f \
	--top-module SRAMMem -I$(FLOW_ROOT)/design/build

sram1:
	rm -rf $(FLOW_ROOT)/design/build
	cd $(FLOW_ROOT)/design && sbt "runMain mem.GenerateSRAMMem"
	sed -E  -i 's/mem\.hex/mem01\.hex/g' $(FLOW_ROOT)/design/build/*.sv
	verilator --cc -f $(FLOW_ROOT)/design/build/filelist.f \
	--top-module SRAMMem -I$(FLOW_ROOT)/design/build -DENABLE_INITIAL_MEM_ \
	--exe sram.cpp --trace
	make -C obj_dir -f VSRAMMem.mk VSRAMMem
	./obj_dir/VSRAMMem

sram_sim:
	sed -E  -i 's/mem\.hex/mem01\.hex/g' $(FLOW_ROOT)/design/build/*.sv
	verilator --cc -f $(FLOW_ROOT)/design/build/filelist.f \
	--top-module SRAMMem -I$(FLOW_ROOT)/design/build -DENABLE_INITIAL_MEM_ \
	--exe sram_ctrl.cpp --trace
	make -C obj_dir -f VSRAMMem.mk VSRAMMem
	./obj_dir/VSRAMMem
